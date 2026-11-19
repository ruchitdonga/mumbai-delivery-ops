package com.dispatch.delivery.orders;

import com.dispatch.delivery.model.Live.Customer;
import com.dispatch.delivery.model.Live.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orders, their items, lifecycle events, rider assignments, customers and unfulfilled-demand events.
 * The simulation is the only writer, so order ids come from an in-memory counter seeded from the table
 * (avoids a sequence round-trip per order and lets items/events be batched with their order).
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;
    private final AtomicLong nextId = new AtomicLong();

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void initIds() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM orders", Long.class);
        nextId.set(max + 1);
    }

    public long newOrderId() { return nextId.getAndIncrement(); }

    public static Timestamp ts(LocalDateTime t) { return t == null ? null : Timestamp.valueOf(t); }

    public boolean hasOrders() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM orders)", Boolean.class));
    }

    /** Latest simulated moment anything happened (orders or their events): the clock resumes from here. */
    public LocalDateTime lastActivity() {
        Timestamp t = jdbc.queryForObject("SELECT GREATEST((SELECT MAX(placed_at) FROM orders), (SELECT MAX(at) FROM order_event))", Timestamp.class);
        return t(t);
    }

    static LocalDateTime t(Timestamp ts) { return ts == null ? null : ts.toLocalDateTime(); }

    /** An order still open when the previous run stopped, with its latest rider assignment (if any). */
    public record OpenRow(long id, int customerId, int storeId, int localityId, String status, double total, int itemCount,
                          double lat, double lng, LocalDateTime placedAt, LocalDateTime promisedAt, LocalDateTime readyAt,
                          LocalDateTime pickedAt, boolean highRise, Integer riderId, String batchKey) {}

    public List<OpenRow> loadOpen() {
        return jdbc.query("SELECT o.id, o.customer_id, o.warehouse_id, o.locality_id, o.status, o.total, o.item_count, o.lat, o.lng, "
                        + "o.placed_at, o.promised_at, o.ready_at, o.picked_at, c.high_rise, a.rider_id, a.batch_key "
                        + "FROM orders o JOIN customer c ON c.id = o.customer_id "
                        + "LEFT JOIN LATERAL (SELECT rider_id, batch_key FROM delivery_assignment d WHERE d.order_id = o.id ORDER BY d.id DESC LIMIT 1) a ON TRUE "
                        + "WHERE o.status NOT IN ('DELIVERED', 'CANCELLED') ORDER BY o.id",
                (rs, i) -> new OpenRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getDouble(6),
                        rs.getInt(7), rs.getDouble(8), rs.getDouble(9), t(rs.getTimestamp(10)), t(rs.getTimestamp(11)),
                        t(rs.getTimestamp(12)), t(rs.getTimestamp(13)), rs.getBoolean(14), (Integer) rs.getObject(15), rs.getString(16)));
    }

    /** Items of the given orders as [productId, qty, unitPricePaise]. */
    public java.util.Map<Long, List<int[]>> loadItems(List<Long> ids) {
        java.util.Map<Long, List<int[]>> out = new java.util.HashMap<>();
        if (ids.isEmpty()) return out;
        jdbc.query("SELECT order_id, product_id, qty, unit_price FROM order_item WHERE order_id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray())),
                rs -> { out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                        .add(new int[]{rs.getInt(2), rs.getInt(3), (int) Math.round(rs.getDouble(4) * 100)}); });
        return out;
    }

    /** Today's KPI counters: revenue, orders, cancelled, delivered, delivered on time, total delivery minutes. */
    public double[] dayCounters(java.time.LocalDate day) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(total) FILTER (WHERE status <> 'CANCELLED'), 0), COUNT(*), "
                        + "COUNT(*) FILTER (WHERE status = 'CANCELLED'), COUNT(*) FILTER (WHERE status = 'DELIVERED'), "
                        + "COUNT(*) FILTER (WHERE status = 'DELIVERED' AND delivered_at <= promised_at), "
                        + "COALESCE(SUM(EXTRACT(EPOCH FROM (delivered_at - placed_at)) / 60) FILTER (WHERE status = 'DELIVERED'), 0) "
                        + "FROM orders WHERE placed_at >= ? AND placed_at < ?",
                (rs, i) -> new double[]{rs.getDouble(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4), rs.getDouble(5), rs.getDouble(6)},
                ts(day.atStartOfDay()), ts(day.plusDays(1).atStartOfDay()));
    }

    // ---------------------------------------------------------------- customers

    public List<Customer> loadCustomers() {
        return jdbc.query("SELECT id, locality_id, lat, lng, high_rise FROM customer",
                (rs, i) -> new Customer(rs.getInt(1), rs.getInt(2), rs.getDouble(3), rs.getDouble(4), rs.getBoolean(5)));
    }

    /** Average orders per hour of day for every store between `from` and `to`: store id -> double[24]. */
    public java.util.Map<Integer, double[]> storeHourProfile(LocalDateTime from, LocalDateTime to) {
        double days = Math.max(1, java.time.Duration.between(from, to).toHours() / 24.0);
        java.util.Map<Integer, double[]> out = new java.util.HashMap<>();
        jdbc.query("SELECT warehouse_id, EXTRACT(HOUR FROM placed_at)::int, COUNT(*) FROM orders WHERE placed_at >= ? AND placed_at < ? "
                + "AND status <> 'CANCELLED' GROUP BY 1, 2", rs -> {
            out.computeIfAbsent(rs.getInt(1), k -> new double[24])[rs.getInt(2)] = rs.getLong(3) / days;
        }, ts(from), ts(to));
        return out;
    }

    /** Customers not yet moved onto a road: [id, lat, lng]. */
    public List<double[]> customersOffRoad(int limit) {
        return jdbc.query("SELECT id, lat, lng FROM customer WHERE NOT on_road ORDER BY id LIMIT ?",
                (rs, i) -> new double[]{rs.getInt(1), rs.getDouble(2), rs.getDouble(3)}, limit);
    }

    /** rows: lat, lng, customer id. Moves the customer onto the road, and their past orders with them. */
    public void moveCustomers(List<Object[]> rows) {
        jdbc.batchUpdate("UPDATE customer SET lat = ?, lng = ?, on_road = TRUE WHERE id = ?", rows);
        jdbc.batchUpdate("UPDATE orders SET lat = ?, lng = ? WHERE customer_id = ? AND status IN ('DELIVERED', 'CANCELLED')", rows);
    }

    public void insertCustomers(List<Object[]> rows) { // name, locality_id, lat, lng, high_rise
        jdbc.batchUpdate("INSERT INTO customer (name, locality_id, lat, lng, high_rise) VALUES (?,?,?,?,?)", rows);
    }

    // ---------------------------------------------------------------- orders

    public void insertOrders(List<Order> orders, String scenario) {
        if (orders.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>(), items = new ArrayList<>(), events = new ArrayList<>();
        for (Order o : orders) {
            rows.add(new Object[]{o.id, o.customerId, o.storeId, o.localityId, o.status.name(), o.total, o.itemCount,
                    o.lat, o.lng, ts(o.placedAt), ts(o.promisedAt), scenario});
            for (int[] it : o.items) items.add(new Object[]{o.id, it[0], it[1], it[2] / 100.0});
            events.add(new Object[]{o.id, "PLACED", ts(o.placedAt), "store " + o.storeId});
        }
        jdbc.batchUpdate("INSERT INTO orders (id, customer_id, warehouse_id, locality_id, status, total, item_count, lat, lng, "
                + "placed_at, promised_at, scenario) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows);
        jdbc.batchUpdate("INSERT INTO order_item (order_id, product_id, qty, unit_price) VALUES (?,?,?,?)", items);
        insertEvents(events);
    }

    public void updateOrders(java.util.Collection<Order> orders) {
        if (orders.isEmpty()) return;
        List<Object[]> rows = new ArrayList<>();
        for (Order o : orders) {
            rows.add(new Object[]{o.status.name(), o.storeId, ts(o.readyAt), ts(o.pickedAt), ts(o.deliveredAt),
                    ts(o.cancelledAt), o.cancelReason, o.id});
        }
        jdbc.batchUpdate("UPDATE orders SET status = ?, warehouse_id = ?, ready_at = ?, picked_at = ?, delivered_at = ?, "
                + "cancelled_at = ?, cancel_reason = ? WHERE id = ?", rows);
    }

    /** rows: order_id, event_type, at (Timestamp), details */
    public void insertEvents(List<Object[]> rows) {
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO order_event (order_id, event_type, at, details) VALUES (?,?,?,?)", rows);
    }

    /** rows: order_id, rider_id, batch_key, assigned_at, eta_min, algorithm, manual */
    public void insertAssignments(List<Object[]> rows) {
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO delivery_assignment (order_id, rider_id, batch_key, assigned_at, eta_min, "
                + "algorithm, manual) VALUES (?,?,?,?,?,?,?)", rows);
    }

    /** rows: type, lat, lng, locality_id, product_id, query, at */
    public void insertDemand(List<Object[]> rows) {
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO demand_event (type, lat, lng, locality_id, product_id, query, at) "
                + "VALUES (?,?,?,?,?,?,?)", rows);
    }

    /** Bulk insert for the history generator: rows already in table column order. */
    public void insertHistory(List<Object[]> orders, List<Object[]> items, List<Object[]> events, List<Object[]> assigns) {
        jdbc.batchUpdate("INSERT INTO orders (id, customer_id, warehouse_id, locality_id, status, total, item_count, lat, lng, "
                + "placed_at, promised_at, ready_at, picked_at, delivered_at, cancelled_at, cancel_reason, scenario) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", orders);
        jdbc.batchUpdate("INSERT INTO order_item (order_id, product_id, qty, unit_price) VALUES (?,?,?,?)", items);
        insertEvents(events);
        insertAssignments(assigns);
    }
}
