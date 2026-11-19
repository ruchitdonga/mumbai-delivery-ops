package com.dispatch.delivery.riders;

import com.dispatch.delivery.model.Live.Rider;
import com.dispatch.delivery.orders.OrderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.*;

/** Delivery partners and their location history. */
@Repository
public class RiderRepository {

    private final JdbcTemplate jdbc;

    public RiderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Riders start each run at their home store. Only on-shift riders are loaded. */
    public List<Rider> loadOnShift() {
        return jdbc.query("SELECT r.id, r.code, r.name, r.home_warehouse_id, r.capacity, w.lat, w.lng "
                        + "FROM rider r JOIN warehouse w ON w.id = r.home_warehouse_id WHERE r.on_shift ORDER BY r.id",
                (rs, i) -> new Rider(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getDouble(6), rs.getDouble(7)));
    }

    /** Last recorded position per rider (location history is written every 5 simulated minutes). */
    public java.util.Map<Integer, double[]> lastPositions() {
        java.util.Map<Integer, double[]> out = new java.util.HashMap<>();
        jdbc.query("SELECT DISTINCT ON (rider_id) rider_id, lat, lng FROM rider_location ORDER BY rider_id, recorded_at DESC",
                rs -> { out.put(rs.getInt(1), new double[]{rs.getDouble(2), rs.getDouble(3)}); });
        return out;
    }

    public Set<Integer> homeStores() {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT home_warehouse_id FROM rider", Integer.class));
    }

    public int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM rider", Integer.class);
        return n == null ? 0 : n;
    }

    public int insert(String code, String name, int homeStoreId, int capacity) {
        Integer id = jdbc.queryForObject("INSERT INTO rider (code, name, home_warehouse_id, capacity) VALUES (?,?,?,?) RETURNING id",
                Integer.class, code, name, homeStoreId, capacity);
        if (id == null) throw new IllegalStateException("rider insert returned no id");
        return id;
    }

    /** Puts a rider back on shift (used when the admin adds riders and off-shift ones exist). Returns the id or null. */
    public Integer reactivateOne() {
        List<Integer> ids = jdbc.queryForList("UPDATE rider SET on_shift = TRUE WHERE id = (SELECT id FROM rider WHERE NOT on_shift ORDER BY id LIMIT 1) RETURNING id", Integer.class);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public Rider load(int id) {
        return jdbc.queryForObject("SELECT r.id, r.code, r.name, r.home_warehouse_id, r.capacity, w.lat, w.lng "
                        + "FROM rider r JOIN warehouse w ON w.id = r.home_warehouse_id WHERE r.id = ?",
                (rs, i) -> new Rider(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
                        rs.getDouble(6), rs.getDouble(7)), id);
    }

    public void setOnShift(int id, boolean onShift) {
        jdbc.update("UPDATE rider SET on_shift = ? WHERE id = ?", onShift, id);
    }

    public void setHome(int id, int storeId) {
        jdbc.update("UPDATE rider SET home_warehouse_id = ? WHERE id = ?", storeId, id);
    }

    /** rows: rider_id, lat, lng, status, recorded_at */
    public void insertLocations(List<Object[]> rows) {
        if (!rows.isEmpty()) jdbc.batchUpdate("INSERT INTO rider_location (rider_id, lat, lng, status, recorded_at) VALUES (?,?,?,?,?)", rows);
    }

    public static Object[] location(Rider r, LocalDateTime at) {
        return new Object[]{r.id, r.lat, r.lng, r.status.name(), OrderRepository.ts(at)};
    }
}
