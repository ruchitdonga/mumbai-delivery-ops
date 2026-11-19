package com.dispatch.delivery.analytics;

import com.dispatch.delivery.model.Live.Rider;
import com.dispatch.delivery.model.Live.Store;
import com.dispatch.delivery.orders.OrderRepository;
import com.dispatch.delivery.simulation.SimulationEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** Read-only analytics over the order history (PostgreSQL) and the live rider state. */
@Service
public class AnalyticsService {

    private final JdbcTemplate jdbc;
    private final SimulationEngine engine;

    public AnalyticsService(JdbcTemplate jdbc, SimulationEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    private LocalDateTime since(int hours) {
        if (hours < 1 || hours > 24 * 60) throw new IllegalArgumentException("hours must be 1..1440");
        return engine.now().minusHours(hours);
    }

    // ------------------------------------------------------------------ 1. AOV and customer spend

    public Map<String, Object> aov(int hours) {
        Object from = OrderRepository.ts(since(hours));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("overall", jdbc.queryForMap("SELECT COUNT(*) AS orders, COALESCE(SUM(total),0) AS revenue, "
                + "COALESCE(ROUND(AVG(total),2),0) AS aov FROM orders WHERE status <> 'CANCELLED' AND placed_at >= ?", from));
        out.put("byLocality", jdbc.queryForList("SELECT l.name AS locality, l.pincode, COUNT(*) AS orders, SUM(o.total) AS revenue, "
                + "ROUND(AVG(o.total),2) AS aov FROM orders o JOIN locality l ON l.id = o.locality_id "
                + "WHERE o.status <> 'CANCELLED' AND o.placed_at >= ? GROUP BY l.name, l.pincode ORDER BY aov DESC", from));
        out.put("byPincode", jdbc.queryForList("SELECT l.pincode, COUNT(*) AS orders, ROUND(AVG(o.total),2) AS aov "
                + "FROM orders o JOIN locality l ON l.id = o.locality_id WHERE o.status <> 'CANCELLED' AND o.placed_at >= ? "
                + "GROUP BY l.pincode ORDER BY l.pincode", from));
        out.put("topCustomers", jdbc.queryForList("SELECT c.id, c.name, l.name AS locality, COUNT(*) AS orders, SUM(o.total) AS spend, "
                + "ROUND(AVG(o.total),2) AS aov, MAX(o.placed_at) AS last_order FROM orders o JOIN customer c ON c.id = o.customer_id "
                + "JOIN locality l ON l.id = c.locality_id WHERE o.status <> 'CANCELLED' AND o.placed_at >= ? "
                + "GROUP BY c.id, c.name, l.name ORDER BY spend DESC LIMIT 15", from));
        // Customers grouped by how much they bought in the window: AOV and order frequency per spend tier.
        out.put("spendTiers", jdbc.queryForList("SELECT tier, COUNT(*) AS customers, SUM(n) AS orders, SUM(spend) AS revenue, "
                + "ROUND(SUM(spend) / SUM(n), 2) AS aov, ROUND(AVG(n), 1) AS orders_per_customer FROM ("
                + "SELECT customer_id, COUNT(*) AS n, SUM(total) AS spend, CASE WHEN SUM(total) < 1000 THEN '1 Occasional (< ₹1k)' "
                + "WHEN SUM(total) < 5000 THEN '2 Regular (₹1k-5k)' WHEN SUM(total) < 15000 THEN '3 Loyal (₹5k-15k)' "
                + "ELSE '4 VIP (₹15k+)' END AS tier FROM orders WHERE status <> 'CANCELLED' AND placed_at >= ? GROUP BY customer_id) x "
                + "GROUP BY tier ORDER BY tier", from));
        return out;
    }

    public Map<String, Object> customer(int id) {
        List<Map<String, Object>> c = jdbc.queryForList("SELECT c.id, c.name, l.name AS locality, l.pincode, c.high_rise, c.simulated "
                + "FROM customer c JOIN locality l ON l.id = c.locality_id WHERE c.id = ?", id);
        if (c.isEmpty()) throw new NoSuchElementException("No customer " + id);
        Map<String, Object> out = new LinkedHashMap<>(c.get(0));
        out.put("summary", jdbc.queryForMap("SELECT COUNT(*) FILTER (WHERE status <> 'CANCELLED') AS orders, "
                + "COALESCE(SUM(total) FILTER (WHERE status <> 'CANCELLED'),0) AS lifetime_spend, "
                + "COALESCE(ROUND(AVG(total) FILTER (WHERE status <> 'CANCELLED'),2),0) AS aov, "
                + "COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled FROM orders WHERE customer_id = ?", id));
        out.put("orders", jdbc.queryForList("SELECT id, status, total, item_count, placed_at, delivered_at, "
                + "ROUND(EXTRACT(EPOCH FROM (delivered_at - placed_at)) / 60) AS delivery_min "
                + "FROM orders WHERE customer_id = ? ORDER BY placed_at DESC LIMIT 50", id));
        return out;
    }

    // ------------------------------------------------------------------ 4. Weekday vs weekend

    public List<Map<String, Object>> weekdayWeekend(int days) {
        if (days < 7 || days > 90) throw new IllegalArgumentException("days must be 7..90");
        Object from = OrderRepository.ts(engine.now().toLocalDate().atStartOfDay().minusDays(days));
        Object to = OrderRepository.ts(engine.now().toLocalDate().atStartOfDay());
        String dayType = "CASE WHEN EXTRACT(ISODOW FROM placed_at) >= 6 THEN 'WEEKEND' ELSE 'WEEKDAY' END";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + dayType + " AS day_type, COUNT(DISTINCT placed_at::date) AS days, "
                + "COUNT(*) FILTER (WHERE status <> 'CANCELLED') AS orders, COALESCE(SUM(total) FILTER (WHERE status <> 'CANCELLED'),0) AS revenue, "
                + "ROUND(AVG(total) FILTER (WHERE status <> 'CANCELLED'),2) AS aov, "
                + "ROUND(AVG(EXTRACT(EPOCH FROM (delivered_at - placed_at)) / 60) FILTER (WHERE status = 'DELIVERED'),1) AS avg_delivery_min, "
                + "ROUND(100.0 * COUNT(*) FILTER (WHERE delivered_at <= promised_at) / NULLIF(COUNT(*) FILTER (WHERE status = 'DELIVERED'),0),1) AS sla_pct, "
                + "ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'CANCELLED') / COUNT(*),1) AS cancel_pct "
                + "FROM orders WHERE placed_at >= ? AND placed_at < ? GROUP BY 1 ORDER BY 1 DESC", from, to);
        List<Map<String, Object>> peaks = jdbc.queryForList("SELECT day_type, hr, ROUND(AVG(n),1) AS orders_per_hour FROM ("
                + "SELECT " + dayType + " AS day_type, placed_at::date AS d, EXTRACT(HOUR FROM placed_at) AS hr, COUNT(*) AS n "
                + "FROM orders WHERE placed_at >= ? AND placed_at < ? AND status <> 'CANCELLED' GROUP BY 1, 2, 3) x "
                + "GROUP BY day_type, hr ORDER BY day_type, hr", from, to);
        List<Map<String, Object>> items = jdbc.queryForList("SELECT " + dayType.replace("placed_at", "o.placed_at") + " AS day_type, p.name, p.category, "
                + "SUM(i.qty) AS units FROM order_item i JOIN orders o ON o.id = i.order_id JOIN product p ON p.id = i.product_id "
                + "WHERE o.placed_at >= ? AND o.placed_at < ? AND o.status <> 'CANCELLED' GROUP BY 1, 2, 3 ORDER BY units DESC", from, to);
        List<Map<String, Object>> unmet = jdbc.queryForList("SELECT " + dayType.replace("placed_at", "at") + " AS day_type, type, COUNT(*) AS n "
                + "FROM demand_event WHERE at >= ? AND at < ? AND type <> 'SEARCH' GROUP BY 1, 2", from, to);
        List<Map<String, Object>> daily = jdbc.queryForList("SELECT placed_at::date AS d, " + dayType + " AS day_type, "
                + "COALESCE(SUM(total) FILTER (WHERE status <> 'CANCELLED'),0) AS revenue FROM orders WHERE placed_at >= ? AND placed_at < ? "
                + "GROUP BY 1, 2 ORDER BY 1", from, to);
        java.time.LocalDate today = engine.now().toLocalDate();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String type = (String) r.get("day_type");
            double nDays = ((Number) r.get("days")).doubleValue();
            // Estimate for the next day of this type: 60% the last 3 such days (recent trend) + 40% the whole window.
            double[] rev = daily.stream().filter(d -> type.equals(d.get("day_type"))).mapToDouble(d -> ((Number) d.get("revenue")).doubleValue()).toArray();
            double all = Arrays.stream(rev).average().orElse(0);
            double recent = Arrays.stream(rev, Math.max(0, rev.length - 3), rev.length).average().orElse(all);
            java.time.LocalDate next = today.plusDays(1);
            while ((next.getDayOfWeek().getValue() >= 6) != type.equals("WEEKEND")) next = next.plusDays(1);
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("orders_per_day", Math.round(((Number) r.get("orders")).doubleValue() / nDays));
            m.put("revenue_per_day", Math.round(((Number) r.get("revenue")).doubleValue() / nDays));
            m.put("next_date", next.toString());
            m.put("next_day_revenue_estimate", Math.round(0.6 * recent + 0.4 * all));
            List<Map<String, Object>> hours = peaks.stream().filter(p -> type.equals(p.get("day_type"))).toList();
            Map<String, Object> peak = hours.stream().max(Comparator.comparingDouble(p -> ((Number) p.get("orders_per_hour")).doubleValue())).orElse(Map.of());
            m.put("hourly", hours);
            m.put("peak_hour", peak.get("hr"));
            double peakPerHour = peak.isEmpty() ? 0 : ((Number) peak.get("orders_per_hour")).doubleValue();
            m.put("peak_orders_per_hour", peakPerHour);
            // A rider completes ~2.2 deliveries an hour at peak (ride out, hand over, ride back), batching included.
            m.put("riders_needed_at_peak", (int) Math.ceil(peakPerHour / 2.2));
            m.put("top_products_per_day", items.stream().filter(i -> type.equals(i.get("day_type"))).limit(6)
                    .map(i -> Map.of("name", i.get("name"), "category", i.get("category"),
                            "units_per_day", Math.round(((Number) i.get("units")).doubleValue() / nDays))).toList());
            Map<String, Object> u = new LinkedHashMap<>();
            unmet.stream().filter(x -> type.equals(x.get("day_type")))
                    .forEach(x -> u.put((String) x.get("type"), Math.round(((Number) x.get("n")).doubleValue() / nDays)));
            m.put("unfulfilled_per_day", u);
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------ 7. Locality comparison + search

    public List<Map<String, Object>> searchLocalities(String q) {
        String like = "%" + (q == null ? "" : q.trim().toLowerCase()) + "%";
        return jdbc.queryForList("SELECT id, name, area, side, pincode, center_lat AS lat, center_lng AS lng, population, approximate "
                + "FROM locality WHERE LOWER(name) LIKE ? OR pincode LIKE ? OR LOWER(area) LIKE ? ORDER BY name", like, like, like);
    }

    public List<Map<String, Object>> compareLocalities(List<Integer> ids, int hours) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Pick at least one locality");
        if (ids.size() > 10) throw new IllegalArgumentException("Compare at most 10 localities");
        Object from = OrderRepository.ts(since(hours));
        String in = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(from);
        args.addAll(ids);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT l.id, l.name, l.pincode, l.side, l.population, "
                + "COUNT(o.id) FILTER (WHERE o.status <> 'CANCELLED') AS orders, COALESCE(SUM(o.total) FILTER (WHERE o.status <> 'CANCELLED'),0) AS revenue, "
                + "ROUND(AVG(o.total) FILTER (WHERE o.status <> 'CANCELLED'),2) AS aov, "
                + "ROUND(AVG(EXTRACT(EPOCH FROM (o.delivered_at - o.placed_at)) / 60) FILTER (WHERE o.status = 'DELIVERED'),1) AS avg_delivery_min, "
                + "ROUND(100.0 * COUNT(o.id) FILTER (WHERE o.delivered_at <= o.promised_at) / NULLIF(COUNT(o.id) FILTER (WHERE o.status = 'DELIVERED'),0),1) AS sla_pct, "
                + "ROUND(100.0 * COUNT(o.id) FILTER (WHERE o.status = 'CANCELLED') / NULLIF(COUNT(o.id),0),1) AS cancel_pct, "
                + "COUNT(DISTINCT o.customer_id) AS customers "
                + "FROM locality l LEFT JOIN orders o ON o.locality_id = l.id AND o.placed_at >= ? "
                + "WHERE l.id IN (" + in + ") GROUP BY l.id ORDER BY l.name", args.toArray());
        List<Object> args2 = new ArrayList<>(args);
        List<Map<String, Object>> unmet = jdbc.queryForList("SELECT locality_id, type, COUNT(*) AS n FROM demand_event "
                + "WHERE at >= ? AND locality_id IN (" + in + ") GROUP BY locality_id, type", args2.toArray());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            Map<String, Object> u = new LinkedHashMap<>();
            unmet.stream().filter(x -> Objects.equals(((Number) x.get("locality_id")).intValue(), ((Number) r.get("id")).intValue()))
                    .forEach(x -> u.put((String) x.get("type"), x.get("n")));
            m.put("demand_events", u);
            Number pop = (Number) r.get("population");
            if (pop != null && pop.intValue() > 0) { // per-capita view: fair comparison of East vs West
                m.put("orders_per_1k_residents", Math.round(10000.0 * ((Number) r.get("orders")).doubleValue() / pop.doubleValue()) / 10.0);
                m.put("revenue_per_1k_residents", Math.round(1000.0 * ((Number) r.get("revenue")).doubleValue() / pop.doubleValue()));
                m.put("buyer_penetration_pct", Math.round(10000.0 * ((Number) r.get("customers")).doubleValue() / pop.doubleValue()) / 100.0);
            }
            out.add(m);
        }
        return out;
    }

    /** Point 7: live panel for a searched locality: engine state now + the last hour from the database. */
    public Map<String, Object> localityLive(int id) {
        Map<String, Object> m = engine.localityLive(id);
        Object from = OrderRepository.ts(engine.now().minusHours(1));
        m.put("lastHour", jdbc.queryForMap("SELECT COUNT(*) FILTER (WHERE status <> 'CANCELLED') AS orders, "
                + "COALESCE(SUM(total) FILTER (WHERE status <> 'CANCELLED'),0) AS revenue, "
                + "COUNT(*) FILTER (WHERE status = 'DELIVERED') AS delivered, "
                + "ROUND(AVG(EXTRACT(EPOCH FROM (delivered_at - placed_at)) / 60) FILTER (WHERE status = 'DELIVERED'),1) AS avg_delivery_min "
                + "FROM orders WHERE locality_id = ? AND placed_at >= ?", id, from));
        Map<String, Object> ev = new LinkedHashMap<>();
        jdbc.queryForList("SELECT type, COUNT(*) AS n FROM demand_event WHERE locality_id = ? AND at >= ? GROUP BY type", id, from)
                .forEach(x -> ev.put((String) x.get("type"), x.get("n")));
        m.put("demandEventsLastHour", ev);
        m.put("topSearches", jdbc.queryForList("SELECT query, COUNT(*) AS n FROM demand_event WHERE locality_id = ? AND at >= ? "
                + "AND type = 'SEARCH' AND query IS NOT NULL GROUP BY query ORDER BY n DESC LIMIT 5", id, from));
        return m;
    }

    /** Radius a new store would deliver to (about a 12-minute ride in normal traffic). */
    static final int PREVIEW_RADIUS_M = 2500;

    /**
     * What opening one store at a site would change, from the last 7 days: the orders it would take over (within reach and
     * closer to it than to the store that served them), how slow those were, the lost orders it would have rescued, and
     * the nearest existing stores. Nothing is created.
     */
    public Map<String, Object> previewStore(Integer localityId, Double lat, Double lng) {
        var site = engine.site(localityId, lat, lng);
        Object from = OrderRepository.ts(engine.now().minusDays(7));
        String pt = "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography";
        Map<String, Object> takeover = jdbc.queryForMap("SELECT COUNT(*) AS orders, "
                + "COUNT(*) FILTER (WHERE o.status = 'DELIVERED' AND o.delivered_at > o.promised_at) AS late, "
                + "COUNT(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered, "
                + "ROUND(AVG(EXTRACT(EPOCH FROM (o.delivered_at - o.placed_at)) / 60) FILTER (WHERE o.status = 'DELIVERED')::numeric, 1) AS avg_min, "
                + "ROUND(AVG(ST_Distance(o.geo, w.geo))::numeric / 1000, 2) AS km_now, ROUND(AVG(ST_Distance(o.geo, " + pt + "))::numeric / 1000, 2) AS km_new, "
                + "COALESCE(SUM(o.total), 0) AS revenue "
                + "FROM orders o JOIN warehouse w ON w.id = o.warehouse_id "
                + "WHERE o.placed_at >= ? AND ST_DWithin(o.geo, " + pt + ", ?) AND ST_Distance(o.geo, " + pt + ") < ST_Distance(o.geo, w.geo)",
                site.lng(), site.lat(), from, site.lng(), site.lat(), PREVIEW_RADIUS_M, site.lng(), site.lat());
        List<Map<String, Object>> fromStores = jdbc.queryForList("SELECT w.code, COUNT(*) AS orders FROM orders o JOIN warehouse w ON w.id = o.warehouse_id "
                + "WHERE o.placed_at >= ? AND ST_DWithin(o.geo, " + pt + ", ?) AND ST_Distance(o.geo, " + pt + ") < ST_Distance(o.geo, w.geo) "
                + "GROUP BY w.code ORDER BY orders DESC LIMIT 4", from, site.lng(), site.lat(), PREVIEW_RADIUS_M, site.lng(), site.lat());
        long lost = jdbc.queryForObject("SELECT COUNT(*) FROM demand_event WHERE at >= ? AND type = 'UNSERVICEABLE' AND ST_DWithin(geo, " + pt + ", ?)",
                Long.class, from, site.lng(), site.lat(), PREVIEW_RADIUS_M);
        List<Map<String, Object>> nearest = jdbc.queryForList("SELECT code, name, active, ROUND((ST_Distance(geo, " + pt + ") / 1000)::numeric, 1) AS km "
                + "FROM warehouse ORDER BY geo <-> " + pt + " LIMIT 3", site.lng(), site.lat(), site.lng(), site.lat());

        double orders = num(takeover.get("orders")), delivered = num(takeover.get("delivered"));
        double perDay = orders / 7.0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", site.code());
        out.put("name", site.name());
        out.put("localityId", site.locality().id);
        out.put("locality", site.locality().name);
        out.put("lat", site.lat());
        out.put("lng", site.lng());
        out.put("radiusM", PREVIEW_RADIUS_M);
        out.put("ordersPerDay", Math.round(perDay));
        out.put("revenuePerDay", Math.round(num(takeover.get("revenue")) / 7.0));
        out.put("takenFrom", fromStores);
        out.put("avgDeliveryMinNow", takeover.get("avg_min"));
        out.put("latePctNow", delivered == 0 ? null : Math.round(1000 * num(takeover.get("late")) / delivered) / 10.0);
        out.put("kmNow", takeover.get("km_now"));
        out.put("kmNew", takeover.get("km_new"));
        out.put("lostOrdersPerDay", Math.round(lost / 7.0));
        out.put("nearestStores", nearest);
        out.put("startingRiders", 4);
        out.put("capacityPerHour", 70);
        String verdict;
        if (perDay + lost / 7.0 < 15) verdict = "Weak site: few orders would come here. Drag the pin towards more customers or pick another area.";
        else if (num(takeover.get("km_now")) - num(takeover.get("km_new")) < 0.4 && lost == 0) verdict = "Little gain: these customers already have a store almost as close.";
        else verdict = "Good site: its orders would ride about " + fmt(Math.max(0, num(takeover.get("km_now")) - num(takeover.get("km_new"))))
                + " km less" + (lost > 0 ? " and about " + Math.round(lost / 7.0) + " lost orders a day would become deliverable" : "") + ".";
        out.put("verdict", verdict);
        return out;
    }

    /** Orders one dark store can pack and hand over per hour (typical capacity in this city). */
    static final int STORE_CAPACITY_PER_HOUR = 70;

    /**
     * Point 3: dark stores each locality should have, from its own shopping data: the busiest hour of an average day
     * (orders placed + orders lost because nobody could deliver there) with 15% headroom, over one store's capacity.
     */
    public List<Map<String, Object>> warehousesNeeded(int days) {
        if (days < 1 || days > 60) throw new IllegalArgumentException("days must be 1..60");
        Object from = OrderRepository.ts(engine.now().minusDays(days));
        List<Map<String, Object>> rows = jdbc.queryForList("WITH h AS ("
                + " SELECT locality_id, EXTRACT(HOUR FROM placed_at) AS hr, COUNT(*)::float / ? AS n FROM orders "
                + " WHERE placed_at >= ? AND status <> 'CANCELLED' GROUP BY 1, 2),"
                + " lost AS (SELECT locality_id, EXTRACT(HOUR FROM at) AS hr, COUNT(*)::float / ? AS n FROM demand_event "
                + " WHERE at >= ? AND type = 'UNSERVICEABLE' GROUP BY 1, 2),"
                + " peak AS (SELECT COALESCE(h.locality_id, lost.locality_id) AS locality_id, MAX(COALESCE(h.n,0) + COALESCE(lost.n,0)) AS peak, "
                + "  MAX(COALESCE(lost.n,0)) AS lost FROM h FULL JOIN lost ON lost.locality_id = h.locality_id AND lost.hr = h.hr GROUP BY 1),"
                + " buy AS (SELECT locality_id, COUNT(DISTINCT customer_id) AS buyers, SUM(total) AS revenue, AVG(total) AS aov FROM orders "
                + " WHERE placed_at >= ? AND status <> 'CANCELLED' GROUP BY 1),"
                + " st AS (SELECT locality_id, COUNT(*) AS n, SUM(capacity_per_hour) AS cap FROM warehouse WHERE active GROUP BY 1)"
                + " SELECT l.id, l.name, l.pincode, l.population, ROUND(COALESCE(peak.peak,0)::numeric, 1) AS peak_orders_per_hour, "
                + " ROUND(COALESCE(peak.lost,0)::numeric, 1) AS lost_per_hour_at_peak, COALESCE(buy.buyers,0) AS buyers, "
                + " ROUND(COALESCE(buy.revenue,0)::numeric / ?::numeric, 0) AS revenue_per_day, ROUND(COALESCE(buy.aov,0)::numeric, 0) AS aov, "
                + " COALESCE(st.n,0) AS stores, COALESCE(st.cap,0) AS capacity_per_hour "
                + " FROM locality l LEFT JOIN peak ON peak.locality_id = l.id LEFT JOIN buy ON buy.locality_id = l.id "
                + " LEFT JOIN st ON st.locality_id = l.id ORDER BY peak_orders_per_hour DESC",
                (double) days, from, (double) days, from, from, (double) days);
        for (Map<String, Object> r : rows) {
            double peak = ((Number) r.get("peak_orders_per_hour")).doubleValue();
            // Any area with real demand (>= 3 orders at its busiest hour) gets its own store; more once it outgrows one.
            int need = peak < 3 ? 0 : Math.max(1, (int) Math.ceil(peak * 1.15 / STORE_CAPACITY_PER_HOUR));
            int have = ((Number) r.get("stores")).intValue();
            r.put("stores_needed", need);
            r.put("change", need - have);
            double cap = ((Number) r.get("capacity_per_hour")).doubleValue();
            double lost = ((Number) r.get("lost_per_hour_at_peak")).doubleValue();
            var l = engine.localities().get(((Number) r.get("id")).intValue());
            List<String> reach = l == null ? List.of() : engine.stores().values().stream()
                    .filter(s -> s.active && com.dispatch.delivery.geo.CoverageService.contains(s.coverage, l.lat, l.lng)).map(s -> s.code).toList();
            r.put("served_by", reach);
            if (l != null) { r.put("lat", l.lat); r.put("lng", l.lng); }
            String advice;
            String reason;
            if (need > have) {
                advice = "Open " + (need - have) + " more";
                reason = "Busiest hour: about " + fmt(peak) + " orders/h" + (lost > 0 ? " (" + fmt(lost) + " of them lost: no store delivers here)" : "")
                        + ". " + (have == 0 ? "It has no store of its own" + (reach.isEmpty() ? " and no store reaches it right now."
                                : "; " + String.join(", ", reach) + " cover" + (reach.size() == 1 ? "s" : "") + " it from outside, so rides are long.")
                        : "Its " + have + " store(s) pack " + (int) cap + " orders/h, not enough with 15% headroom.")
                        + " Needs " + need + ".";
            } else if (need < have && peak < 0.3 * cap) {
                advice = "Over-served";
                reason = have + " stores here, but even the busiest hour (" + fmt(peak) + " orders/h) uses only "
                        + Math.round(100 * peak / cap) + "% of their " + (int) cap + "/h capacity. One could close or move to an area that needs it.";
            } else if (need == 0) {
                advice = "OK";
                reason = "Few orders (busiest hour " + fmt(peak) + "/h); " + (reach.isEmpty() ? "no store reaches it now, but demand is too low to justify one."
                        : "served from " + String.join(", ", reach) + ".");
            } else {
                advice = "OK";
                reason = have + " store(s) pack " + (int) cap + "/h; the busiest hour needs about " + fmt(peak * 1.15) + "/h with headroom ("
                        + Math.round(100 * peak / Math.max(1, cap)) + "% used).";
            }
            r.put("advice", advice);
            r.put("reason", reason);
        }
        return rows;
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    /**
     * Health of every dark store over the last `days` days plus right now: status (OVERLOADED / BUSY / OK / UNDERUSED),
     * the reasons for it in plain words, and concrete fixes (some with a dashboard action).
     */
    public List<Map<String, Object>> storeHealth(int days) {
        if (days < 1 || days > 60) throw new IllegalArgumentException("days must be 1..60");
        Object from = OrderRepository.ts(engine.now().minusDays(days));
        Map<Integer, Map<String, Object>> stats = new HashMap<>();
        jdbc.queryForList("WITH h AS (SELECT warehouse_id, EXTRACT(HOUR FROM placed_at) AS hr, COUNT(*)::float / ? AS n FROM orders "
                + " WHERE placed_at >= ? AND status <> 'CANCELLED' GROUP BY 1, 2), pk AS (SELECT warehouse_id, MAX(n) AS peak FROM h GROUP BY 1) "
                + "SELECT o.warehouse_id AS id, COALESCE(MAX(pk.peak),0) AS peak, COUNT(*) FILTER (WHERE o.status = 'DELIVERED') AS delivered, "
                + " COUNT(*) FILTER (WHERE o.status = 'DELIVERED' AND o.delivered_at > o.promised_at) AS late, "
                + " AVG(EXTRACT(EPOCH FROM (o.delivered_at - o.placed_at)) / 60) FILTER (WHERE o.status = 'DELIVERED') AS avg_min, "
                + " COUNT(*) FILTER (WHERE o.status = 'CANCELLED') AS cancelled, COUNT(*) AS total "
                + "FROM orders o LEFT JOIN pk ON pk.warehouse_id = o.warehouse_id WHERE o.placed_at >= ? GROUP BY o.warehouse_id",
                (double) days, from, from).forEach(r -> stats.put(((Number) r.get("id")).intValue(), r));
        // Where each store's orders come from: the biggest outside area without its own store is the place to open a second one.
        Map<Integer, List<Map<String, Object>>> sources = new HashMap<>();
        jdbc.queryForList("SELECT o.warehouse_id AS id, l.id AS area_id, l.name, l.center_lat AS lat, l.center_lng AS lng, COUNT(*) AS n, "
                + " EXISTS (SELECT 1 FROM warehouse w WHERE w.locality_id = l.id AND w.active) AS has_store "
                + "FROM orders o JOIN locality l ON l.id = o.locality_id WHERE o.placed_at >= ? GROUP BY 1, 2, 3, 4, 5 ORDER BY 1, n DESC", from)
                .forEach(r -> sources.computeIfAbsent(((Number) r.get("id")).intValue(), k -> new ArrayList<>()).add(r));

        Map<Integer, Integer> riders = new HashMap<>();
        engine.riders().forEach(r -> riders.merge(r.homeStoreId, 1, Integer::sum));
        Map<Integer, Long> waiting = new HashMap<>();
        engine.openOrders().stream().filter(o -> o.waitingForRider() && o.riderId == null).forEach(o -> waiting.merge(o.storeId, 1L, Long::sum));
        Map<Integer, com.dispatch.delivery.model.Live.Locality> locs = engine.localities();
        // Riders each store can spare (beyond its busiest-hour need + 1) and how many each is short, for concrete moves.
        Map<Integer, Integer> spare = new HashMap<>(), shortBy = new HashMap<>();
        for (Store s : engine.stores().values()) {
            double pk = num(stats.getOrDefault(s.id, Map.of()).get("peak"));
            int need = (int) Math.ceil(pk / com.dispatch.delivery.simulation.SimulationEngine.DELIVERIES_PER_RIDER_HOUR) + 1;
            int have = riders.getOrDefault(s.id, 0);
            if (have - need - 1 > 0) spare.put(s.id, have - need - 1);
            int shortNow = Math.max(need - have, waiting.getOrDefault(s.id, 0L) >= Math.max(4, have) ? 1 : 0); // same rule as `gap` below
            if (shortNow > 0) shortBy.put(s.id, shortNow);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Store s : engine.stores().values()) {
            Map<String, Object> st = stats.getOrDefault(s.id, Map.of());
            double peak = num(st.get("peak")), delivered = num(st.get("delivered")), total = num(st.get("total"));
            double latePct = delivered == 0 ? 0 : 100 * num(st.get("late")) / delivered;
            double cancelPct = total == 0 ? 0 : 100 * num(st.get("cancelled")) / total;
            double peakUtil = 100 * peak / s.capacityPerHour, nowUtil = 100.0 * s.recentOrders.size() / s.capacityPerHour;
            int have = riders.getOrDefault(s.id, 0);
            int needAtPeak = (int) Math.ceil(peak / com.dispatch.delivery.simulation.SimulationEngine.DELIVERIES_PER_RIDER_HOUR) + 1;
            long wait = waiting.getOrDefault(s.id, 0L);
            long low = s.stock.values().stream().filter(q -> q < 12).count();
            Double avgMin = st.get("avg_min") == null ? null : Math.round(num(st.get("avg_min")) * 10) / 10.0;

            List<String> reasons = new ArrayList<>();
            List<Map<String, Object>> fixes = new ArrayList<>();
            int severity = 0; // 0 ok, 1 busy, 2 overloaded, -1 underused
            if (!s.active) {
                reasons.add("Switched off: its area is served by neighbours (longer rides) or not at all.");
                fixes.add(fix("Re-open the store", "ACTIVATE_STORE", s.id, s.lat, s.lng));
            }
            if (peakUtil >= 90) { severity = 2; reasons.add("Packing: the busiest hour brings " + fmt(peak) + " orders/h, " + Math.round(peakUtil) + "% of the " + s.capacityPerHour + "/h it can pack."); }
            else if (peakUtil >= 70) { severity = Math.max(severity, 1); reasons.add("Packing: busiest hour uses " + Math.round(peakUtil) + "% of capacity (" + fmt(peak) + " of " + s.capacityPerHour + "/h)."); }
            if (latePct >= 15) { severity = 2; reasons.add("Late deliveries: " + Math.round(latePct) + "% arrived after the promised time (target under 10%)."); }
            else if (latePct >= 10) { severity = Math.max(severity, 1); reasons.add("Late deliveries: " + Math.round(latePct) + "% missed the promise (target under 10%)."); }
            if (have < needAtPeak) { severity = Math.max(severity, latePct >= 10 ? 2 : 1); reasons.add("Riders: " + have + " based here, about " + needAtPeak + " needed at the busiest hour (" + fmt(peak) + " orders/h ÷ 2.2 per rider + 1 spare)."); }
            if (wait >= Math.max(4, have)) { severity = 2; reasons.add("Right now " + wait + " orders are waiting for a rider."); }
            // ~4-5% customer cancellations is the normal background rate here; above 8% points at slow service.
            if (cancelPct >= 8) { severity = Math.max(severity, 1); reasons.add(Math.round(cancelPct) + "% of orders were cancelled by customers (normal is about 4%): they give up when an order waits."); }
            if (low >= 6) { severity = Math.max(severity, 1); reasons.add(low + " products are nearly sold out (fewer than 12 left), so those orders get turned away."); }
            if (severity == 0 && s.active && peakUtil < 20 && have > needAtPeak + 2) {
                severity = -1;
                reasons.add("Quiet: even the busiest hour uses " + Math.round(peakUtil) + "% of capacity, and " + have + " riders are based here for about " + needAtPeak + " needed.");
            }
            if (reasons.isEmpty()) reasons.add("Healthy: busiest hour at " + Math.round(peakUtil) + "% of capacity, " + Math.round(latePct) + "% late, " + have + " riders for about " + needAtPeak + " needed.");

            // Fixes, most direct first; each one acts on this store only.
            int gap = Math.max(needAtPeak - have, wait >= Math.max(4, have) ? 1 : 0);
            if (gap > 0) {
                Store donor = spareDonor(s.id, spare);
                if (donor != null) {
                    int n = Math.min(gap, spare.get(donor.id));
                    fixes.add(fix("Move " + n + " rider" + (n > 1 ? "s" : "") + " from " + donor.code + " (" + spare.get(donor.id) + " spare there) to here",
                            "MOVE_RIDERS", Map.of("fromStoreId", donor.id, "toStoreId", s.id, "count", n), null, null));
                }
                fixes.add(fix("Hire " + gap + " new rider" + (gap > 1 ? "s" : "") + " for this store", "HIRE_RIDERS", Map.of("storeId", s.id, "count", gap), null, null));
            }
            if (peakUtil >= 70) {
                int cap = (int) Math.ceil(peak * 1.4 / 10) * 10; // busiest hour at ~70% of the new capacity
                fixes.add(fix("Raise packing capacity from " + s.capacityPerHour + " to " + cap + " orders/h (more packers, faster picking)",
                        "SET_CAPACITY", Map.of("storeId", s.id, "capacity", cap), null, null));
            }
            if (peakUtil >= 70 || latePct >= 10) {
                sources.getOrDefault(s.id, List.of()).stream().filter(a -> !(Boolean) a.get("has_store")).findFirst().ifPresent(a -> {
                    long share = Math.round(100 * num(a.get("n")) / Math.max(1, total));
                    if (share >= 15) fixes.add(fix("Open a second store in " + a.get("name") + ": " + share + "% of this store's orders come from there and it has no store",
                            "OPEN_STORE", Map.of("localityId", a.get("area_id")), num(a.get("lat")), num(a.get("lng"))));
                });
            }
            if (latePct >= 10 && avgMin != null && avgMin > 18) fixes.add(fix("Deliveries average " + avgMin + " min: switch to \"promised deadline first\" so tight orders go first", "PRIORITY_SLA", null, null, null));
            if (latePct >= 10 && gap == 0)
                fixes.add(fix("It has enough riders on paper, so lateness comes from long rides at the busiest hour: hire 2 extra riders here",
                        "HIRE_RIDERS", Map.of("storeId", s.id, "count", 2), null, null));
            if (cancelPct >= 8 && gap == 0)
                fixes.add(fix("Customers give up waiting: hire 2 riders to get orders out faster", "HIRE_RIDERS", Map.of("storeId", s.id, "count", 2), null, null));
            if (low >= 6) fixes.add(fix("Restock " + low + " nearly sold-out products now", "RESTOCK", Map.of("storeId", s.id), null, null));
            if (severity == -1) {
                Store needy = engine.stores().values().stream().filter(x -> x.active && x.id != s.id && shortBy.getOrDefault(x.id, 0) > 0)
                        .max(Comparator.comparingInt(x -> shortBy.get(x.id))).orElse(null);
                int n = have - needAtPeak - 1;
                if (needy != null) fixes.add(fix("Move " + Math.min(n, shortBy.get(needy.id)) + " spare riders to " + needy.code + " (short of riders)",
                        "MOVE_RIDERS", Map.of("fromStoreId", s.id, "toStoreId", needy.id, "count", Math.min(n, shortBy.get(needy.id))), null, null));
                else fixes.add(fix("No store is short of riders: take " + n + " idle riders off shift to save cost", "REMOVE_RIDERS", Map.of("count", n), null, null));
            }

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id); m.put("code", s.code); m.put("name", s.name); m.put("active", s.active);
            m.put("area", locs.get(s.localityId) == null ? null : locs.get(s.localityId).name);
            m.put("lat", s.lat); m.put("lng", s.lng);
            m.put("status", severity == 2 ? "OVERLOADED" : severity == 1 ? "BUSY" : severity == -1 ? "UNDERUSED" : "OK");
            m.put("capacityPerHour", s.capacityPerHour);
            m.put("peakOrdersPerHour", Math.round(peak * 10) / 10.0);
            m.put("peakUtilPct", Math.round(peakUtil));
            m.put("nowUtilPct", Math.round(nowUtil));
            m.put("latePct", Math.round(latePct * 10) / 10.0);
            m.put("avgDeliveryMin", avgMin);
            m.put("riders", have);
            m.put("ridersNeededAtPeak", needAtPeak);
            m.put("waitingNow", wait);
            m.put("lowStock", low);
            m.put("reasons", reasons);
            m.put("fixes", fixes);
            out.add(m);
        }
        Map<String, Integer> rank = Map.of("OVERLOADED", 0, "BUSY", 1, "UNDERUSED", 2, "OK", 3);
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> rank.get((String) m.get("status")))
                .thenComparingDouble(m -> -((Number) m.get("latePct")).doubleValue()));
        return out;
    }

    /** The store with the most spare riders (nearest to `toStoreId` on ties), or null if nobody can spare one. */
    private Store spareDonor(int toStoreId, Map<Integer, Integer> spare) {
        Store to = engine.stores().get(toStoreId);
        return spare.entrySet().stream().filter(e -> e.getKey() != toStoreId && e.getValue() > 0)
                .map(e -> engine.stores().get(e.getKey()))
                .min(Comparator.comparingInt((Store x) -> -spare.get(x.id)).thenComparingDouble(x -> com.dispatch.algo.Geo.km(x.lat, x.lng, to.lat, to.lng)))
                .orElse(null);
    }

    private static double num(Object o) { return o == null ? 0 : ((Number) o).doubleValue(); }

    private static Map<String, Object> fix(String text, String action, Object target, Double lat, Double lng) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text); m.put("action", action); m.put("target", target); m.put("lat", lat); m.put("lng", lng);
        return m;
    }

    // ------------------------------------------------------------------ Recommendations (plain-language, one click to act)

    private static Map<String, Object> rec(int priority, String title, String why, String action, Object target, Double lat, Double lng) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("priority", priority); // 1 = act now, 2 = this week, 3 = keep an eye on
        m.put("title", title);
        m.put("why", why);
        m.put("action", action);     // what the dashboard button does (null = information only)
        m.put("target", target);
        m.put("lat", lat);
        m.put("lng", lng);
        return m;
    }

    /** Rules over live state and recent history that turn the numbers into "do this next". */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recommendations() {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime now = engine.now();
        List<com.dispatch.delivery.model.Live.Order> open = engine.openOrders();
        long nearlyLate = open.stream().filter(o -> now.isAfter(o.promisedAt.minusMinutes(5))).count();
        long free = engine.riders().stream().filter(Rider::available).count();
        long waiting = open.stream().filter(o -> o.waitingForRider() && o.riderId == null).count();

        List<Map<String, Object>> moves = (List<Map<String, Object>>) engine.staffingPlan().get("moves");
        if (!moves.isEmpty()) out.add(rec(1, "Move " + moves.size() + " free rider" + (moves.size() > 1 ? "s" : "") + " to busy stores",
                "Some stores got more orders in the last hour than their riders can carry, while other stores have riders with nothing to do.",
                "APPLY_STAFFING", null, null, null));
        if (nearlyLate >= 3 && engine.priority() == com.dispatch.delivery.dispatch.SlaQueue.Policy.FIFO)
            out.add(rec(1, "Switch priority to \"promised deadline first\"", nearlyLate + " orders are within 5 minutes of their promised time. "
                    + "First-come-first-served ignores how close each order is to being late.", "PRIORITY_SLA", null, null, null));
        // Staffing from expected demand, with a gap between the add and remove rules so they never ping-pong:
        // add = stores short for the NEXT hour (after moving spare riders); remove = only riders spare for the next THREE hours.
        Map<Integer, Integer> perStore = new HashMap<>();
        engine.riders().forEach(r -> perStore.merge(r.homeStoreId, 1, Integer::sum));
        int shortNext = 0, spareNext = 0, spare3h = 0;
        List<String> shortStores = new ArrayList<>();
        for (Store s : engine.stores().values()) {
            if (!s.active) continue;
            int have = perStore.getOrDefault(s.id, 0);
            int needNext = com.dispatch.delivery.simulation.SimulationEngine.ridersFor(Math.max(s.recentOrders.size(), engine.expectedOrders(s.id, 1)));
            if (needNext > have) { shortNext += needNext - have; shortStores.add(s.code); }
            else spareNext += have - needNext;
            spare3h += Math.max(0, have - engine.ridersNeeded(s, 3) - 1);
        }
        if (shortNext > spareNext && shortNext >= 2)
            out.add(rec(1, "Bring " + (shortNext - spareNext) + " more riders on shift at the short stores",
                    "Expected orders for the next hour need " + shortNext + " more riders at " + String.join(", ", shortStores.subList(0, Math.min(5, shortStores.size())))
                            + (shortStores.size() > 5 ? "…" : "") + ", and other stores can spare only " + spareNext + ". Each store gets exactly its gap.",
                    "FILL_GAPS", null, null, null));
        else if (spare3h >= 6)
            out.add(rec(3, "Take " + spare3h + " idle riders off shift",
                    "Even at the busiest of the next 3 hours (usual demand for this time × the scenario), these riders would have nothing to do. "
                            + "Riders still needed for the coming peak are kept.", "TRIM_RIDERS", null, null, null));

        Map<Integer, com.dispatch.delivery.model.Live.Locality> locs = engine.localities();
        warehousesNeeded(7).stream().filter(r -> ((Number) r.get("change")).intValue() > 0)
                .sorted(Comparator.comparingDouble(r -> -((Number) r.get("peak_orders_per_hour")).doubleValue())).limit(4)
                .forEach(r -> {
                    var l = locs.get(((Number) r.get("id")).intValue());
                    double lost = ((Number) r.get("lost_per_hour_at_peak")).doubleValue();
                    out.add(rec(2, "Open a dark store in " + r.get("name"),
                            "At its busiest hour about " + r.get("peak_orders_per_hour") + " orders an hour come from here"
                                    + (lost > 0 ? ", and about " + lost + " an hour are lost because no store delivers here" : "")
                                    + ". It has no store of its own yet.", "OPEN_STORE", r.get("id"), l.lat, l.lng));
                });

        for (Store s : engine.stores().values()) {
            if (!s.active) out.add(rec(2, "Re-open " + s.name, "This store is switched off, so the area around it may not be served.",
                    "ACTIVATE_STORE", s.id, s.lat, s.lng));
        }
        // Restock: the 3 emptiest stores get their own button; the rest are summed up (Planning > Store health lists all).
        List<Store> lowStores = engine.stores().values().stream().filter(x -> x.stock.values().stream().filter(q -> q < 12).count() >= 6)
                .sorted(Comparator.comparingLong((Store x) -> -x.stock.values().stream().filter(q -> q < 12).count())).toList();
        for (Store s : lowStores.subList(0, Math.min(3, lowStores.size()))) {
            long low = s.stock.values().stream().filter(q -> q < 12).count();
            out.add(rec(3, "Restock " + s.name, low + " products are nearly sold out there (fewer than 12 left). Orders for them get turned away.",
                    "RESTOCK", s.id, s.lat, s.lng));
        }
        if (lowStores.size() > 3) out.add(rec(3, (lowStores.size() - 3) + " more stores are running low on stock",
                "Planning → Store health has a Restock button for each. Delivery trucks refill every store at 06:00 and 15:00.", null, null, null, null));
        if (out.isEmpty()) out.add(rec(3, "All good right now", "Orders are on time, riders are where the demand is and every store is open.", null, null, null, null));
        out.sort(Comparator.comparingInt(m -> (int) m.get("priority")));
        return out;
    }

    // ------------------------------------------------------------------ 11. Unfulfilled demand heatmap

    public List<Map<String, Object>> heatmap(String type, int hours) {
        Set<String> ok = Set.of("ALL_UNMET", "SEARCH", "ABANDONED_CART", "OUT_OF_STOCK", "UNSERVICEABLE");
        if (!ok.contains(type)) throw new IllegalArgumentException("type must be one of " + ok);
        Object from = OrderRepository.ts(since(hours));
        String filter = type.equals("ALL_UNMET") ? "type <> 'SEARCH'" : "type = '" + type + "'"; // value checked against the allow-list above
        return jdbc.queryForList("SELECT ST_Y(cell) AS lat, ST_X(cell) AS lng, n FROM ("
                + "SELECT ST_SnapToGrid(geo::geometry, 0.004) AS cell, COUNT(*) AS n FROM demand_event "
                + "WHERE at >= ? AND " + filter + " GROUP BY cell) g ORDER BY n DESC", from);
    }

    public List<Map<String, Object>> revenueByHour(int hours) {
        return jdbc.queryForList("SELECT date_trunc('hour', placed_at) AS hour, COUNT(*) FILTER (WHERE status <> 'CANCELLED') AS orders, "
                + "COALESCE(SUM(total) FILTER (WHERE status <> 'CANCELLED'),0) AS revenue FROM orders WHERE placed_at >= ? "
                + "GROUP BY 1 ORDER BY 1", OrderRepository.ts(since(hours)));
    }

    // ------------------------------------------------------------------ 12. Rider utilisation + rebalancing

    public Map<String, Object> utilization() {
        Collection<Rider> riders = engine.riders();
        Map<Integer, Store> stores = engine.stores();
        Map<Integer, Long> waiting = new HashMap<>();
        engine.openOrders().stream().filter(o -> o.waitingForRider() && o.riderId == null).forEach(o -> waiting.merge(o.storeId, 1L, Long::sum));
        List<Map<String, Object>> rows = new ArrayList<>();
        int idle = 0, active = 0, overloaded = 0;
        for (Rider r : riders) {
            long shift = r.busyMinutes + r.idleMinutes;
            double util = shift == 0 ? 0 : (double) r.busyMinutes / shift;
            String cls;
            if (shift >= 60 && util < 0.3) { cls = "IDLE"; idle++; }
            else if (util > 0.85 || (r.load.size() >= r.capacity && waiting.getOrDefault(r.homeStoreId, 0L) > 2)) { cls = "OVERLOADED"; overloaded++; }
            else { cls = "ACTIVE"; active++; }
            rows.add(Map.of("id", r.id, "code", r.code, "status", r.status.name(), "homeStore", stores.get(r.homeStoreId).code,
                    "utilPct", Math.round(util * 1000) / 10.0, "deliveriesToday", r.deliveriesToday, "load", r.load.size(),
                    "shiftMinutes", shift, "class", cls));
        }
        // Rebalance: move free riders from stores with spare riders to stores with a backlog.
        Map<Integer, List<Rider>> free = new HashMap<>();
        for (Rider r : riders) if (r.available()) free.computeIfAbsent(r.homeStoreId, k -> new ArrayList<>()).add(r);
        List<Map<String, Object>> recs = new ArrayList<>();
        List<Store> deficit = stores.values().stream().filter(s -> s.active)
                .filter(s -> waiting.getOrDefault(s.id, 0L) - free.getOrDefault(s.id, List.of()).size() >= 2)
                .sorted(Comparator.comparingLong(s -> -waiting.getOrDefault(s.id, 0L))).toList();
        for (Store need : deficit) {
            long gap = waiting.getOrDefault(need.id, 0L) - free.getOrDefault(need.id, List.of()).size();
            for (Store has : stores.values()) {
                if (has.id == need.id || gap <= 0) continue;
                List<Rider> spare = free.getOrDefault(has.id, new ArrayList<>());
                while (gap > 0 && spare.size() - waiting.getOrDefault(has.id, 0L) > 1) {
                    Rider r = spare.remove(spare.size() - 1);
                    recs.add(Map.of("riderId", r.id, "rider", r.code, "from", has.code, "to", need.code, "toStoreId", need.id,
                            "reason", need.code + " has " + waiting.getOrDefault(need.id, 0L) + " orders waiting"));
                    gap--;
                }
            }
        }
        rows.sort(Comparator.comparing(m -> (String) m.get("class")));
        return Map.of("summary", Map.of("idle", idle, "active", active, "overloaded", overloaded, "onShift", riders.size()),
                "riders", rows, "recommendations", recs);
    }
}
