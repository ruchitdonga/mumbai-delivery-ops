package com.dispatch.delivery.alerts;

import com.dispatch.delivery.orders.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** Operational alerts: stored in the database, kept in a short in-memory list for the live feed. */
@Service
public class AlertService {

    public record AlertView(long id, String type, String severity, String message, Long orderId, Integer riderId,
                            Integer warehouseId, LocalDateTime at, boolean acknowledged) {}

    private final JdbcTemplate jdbc;
    private final List<AlertView> unsent = new ArrayList<>();

    public AlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS = "SELECT id, type, severity, message, order_id, rider_id, warehouse_id, created_at, acknowledged_at IS NOT NULL FROM alert ";

    private static AlertView row(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new AlertView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), (Long) rs.getObject(5, Long.class),
                (Integer) rs.getObject(6, Integer.class), (Integer) rs.getObject(7, Integer.class), rs.getTimestamp(8).toLocalDateTime(), rs.getBoolean(9));
    }

    public synchronized AlertView raise(String type, String severity, String message, Long orderId, Integer riderId,
                                        Integer warehouseId, LocalDateTime at) {
        Long id = jdbc.queryForObject("INSERT INTO alert (type, severity, message, order_id, rider_id, warehouse_id, created_at) "
                + "VALUES (?,?,?,?,?,?,?) RETURNING id", Long.class, type, severity, message, orderId, riderId, warehouseId, OrderRepository.ts(at));
        AlertView a = new AlertView(id, type, severity, message, orderId, riderId, warehouseId, at, false);
        unsent.add(a);
        return a;
    }

    /** Alerts raised since the last broadcast. */
    public synchronized List<AlertView> drainUnsent() {
        List<AlertView> out = new ArrayList<>(unsent);
        unsent.clear();
        return out;
    }

    /** Latest alerts from the database, so the feed survives restarts. */
    public List<AlertView> recent(int limit) {
        return jdbc.query(COLS + "ORDER BY id DESC LIMIT ?", AlertService::row, Math.max(1, Math.min(limit, 500)));
    }

    /** Acknowledges every open alert; returns how many were open. */
    public int acknowledgeAll(LocalDateTime at) {
        return jdbc.update("UPDATE alert SET acknowledged_at = ? WHERE acknowledged_at IS NULL", OrderRepository.ts(at));
    }

    public AlertView acknowledge(long id, LocalDateTime at) {
        jdbc.update("UPDATE alert SET acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL", OrderRepository.ts(at), id);
        List<AlertView> a = jdbc.query(COLS + "WHERE id = ?", AlertService::row, id);
        if (a.isEmpty()) throw new NoSuchElementException("No alert " + id);
        return a.get(0);
    }
}
