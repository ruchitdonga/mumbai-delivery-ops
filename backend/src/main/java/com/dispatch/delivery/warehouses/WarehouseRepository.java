package com.dispatch.delivery.warehouses;

import com.dispatch.delivery.model.Live.Locality;
import com.dispatch.delivery.model.Live.Product;
import com.dispatch.delivery.model.Live.Store;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Localities (with PostGIS boundaries), dark stores, products and stock. */
@Repository
public class WarehouseRepository {

    // Relative demand per locality (simulated; denser, more commercial areas order more).
    private final JdbcTemplate jdbc;

    public WarehouseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Locality> loadLocalities() {
        return jdbc.query("SELECT id, name, area, side, pincode, center_lat, center_lng, "
                        + "ST_YMin(boundary::geometry), ST_YMax(boundary::geometry), ST_XMin(boundary::geometry), ST_XMax(boundary::geometry), COALESCE(population, 300000) "
                        + "FROM locality ORDER BY id",
                (rs, i) -> new Locality(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getDouble(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9), rs.getDouble(10), rs.getDouble(11),
                        rs.getInt(12)));
    }

    /** PostGIS point-in-polygon: which locality contains this point (null if outside all). */
    public Integer localityAt(double lat, double lng) {
        List<Integer> ids = jdbc.queryForList("SELECT id FROM locality WHERE ST_Covers(boundary, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) LIMIT 1",
                Integer.class, lng, lat);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // Roads over water that OSRM may snap a customer onto: Bandra-Worli Sea Link.
    private static final String SEA_ROADS = "ST_GeogFromText('SRID=4326;LINESTRING(72.8185 19.0420, 72.8160 19.0300, 72.8155 19.0150)')";

    /**
     * Once every customer is on a road: replace each locality's hand-drawn box by the land it actually covers, i.e. the
     * concave hull of its road-snapped customers, widened by 120 m and clipped to the old box (so areas never overlap
     * and never reach into the sea). Customers now standing in another locality's box are moved to that locality first.
     * Returns how many localities were reshaped.
     */
    public int shapeLocalitiesToLand() {
        jdbc.update("UPDATE customer c SET "
                + "lat = (SELECT o.lat FROM customer o WHERE o.locality_id = c.locality_id AND NOT ST_DWithin(o.geo, " + SEA_ROADS + ", 80) ORDER BY o.geo <-> c.geo LIMIT 1), "
                + "lng = (SELECT o.lng FROM customer o WHERE o.locality_id = c.locality_id AND NOT ST_DWithin(o.geo, " + SEA_ROADS + ", 80) ORDER BY o.geo <-> c.geo LIMIT 1) "
                + "WHERE ST_DWithin(c.geo, " + SEA_ROADS + ", 80)");
        jdbc.update("UPDATE customer c SET locality_id = l.id FROM locality l "
                + "WHERE NOT l.land_shaped AND c.locality_id <> l.id AND ST_Covers(COALESCE(l.box, l.boundary), c.geo)");
        return jdbc.update("WITH pts AS (SELECT locality_id, ST_Collect(geo::geometry) AS g, COUNT(*) AS n FROM customer GROUP BY locality_id), "
                // land = union of 200 m discs round every on-road customer, gaps < 800 m closed (dilate 400 m, erode 400 m) so areas are solid,
                // in UTM 43N metres; ST_ConcaveHull was ~8 s per locality, this is ~25 ms
                + "shaped AS (SELECT l.id, (SELECT d.geom FROM ST_Dump(ST_Intersection(ST_SimplifyPreserveTopology(ST_Transform("
                + "  ST_Buffer(ST_Buffer(ST_Buffer(ST_Transform(p.g, 32643), 200, 4), 400, 4), -400, 4), 4326), 0.0002), "
                + "  COALESCE(l.box, l.boundary)::geometry)) d "
                + "  ORDER BY ST_Area(d.geom) DESC LIMIT 1) AS shape "
                + " FROM locality l JOIN pts p ON p.locality_id = l.id WHERE NOT l.land_shaped AND p.n >= 10) "
                + "UPDATE locality l SET boundary = s.shape::geography, land_shaped = TRUE, "
                + " center_lat = ST_Y(ST_PointOnSurface(s.shape)), center_lng = ST_X(ST_PointOnSurface(s.shape)) "
                + "FROM shaped s WHERE s.id = l.id AND s.shape IS NOT NULL AND GeometryType(s.shape) = 'POLYGON'");
    }

    /** A store's delivery polygon cut to land (the reshaped localities) as GeoJSON, for drawing; null before reshaping. */
    public String clipToLand(double[][] ring) {
        if (ring.length < 3) return null;
        StringBuilder wkt = new StringBuilder("SRID=4326;POLYGON((");
        for (double[] p : ring) wkt.append(p[1]).append(' ').append(p[0]).append(',');
        wkt.append(ring[0][1]).append(' ').append(ring[0][0]).append("))");
        List<String> r = jdbc.queryForList("SELECT ST_AsGeoJSON(ST_Intersection(ST_MakeValid(ST_GeomFromEWKT(?)), "
                + "(SELECT ST_Union(boundary::geometry) FROM locality WHERE land_shaped)), 6) "
                + "WHERE EXISTS (SELECT 1 FROM locality WHERE land_shaped)", String.class, wkt.toString());
        return r.isEmpty() ? null : r.get(0);
    }

    /** Cached OSRM road shapes (see V4). */
    public Map<String, double[][]> loadShapes() {
        Map<String, double[][]> out = new java.util.HashMap<>();
        jdbc.query("SELECT cell_key, pts FROM road_shape", rs -> {
            String[] p = rs.getString(2).split(";");
            double[][] s = new double[p.length][];
            for (int i = 0; i < p.length; i++) {
                String[] ll = p[i].split(",");
                s[i] = new double[]{Double.parseDouble(ll[0]), Double.parseDouble(ll[1])};
            }
            out.put(rs.getString(1), s);
        });
        return out;
    }

    public void saveShapes(Map<String, double[][]> shapes) {
        if (shapes.isEmpty()) return;
        List<Object[]> rows = new java.util.ArrayList<>();
        shapes.forEach((k, s) -> {
            StringBuilder b = new StringBuilder();
            for (double[] p : s) b.append(b.isEmpty() ? "" : ";").append(String.format(java.util.Locale.ROOT, "%.5f,%.5f", p[0], p[1]));
            rows.add(new Object[]{k, b.toString()});
        });
        jdbc.batchUpdate("INSERT INTO road_shape (cell_key, pts) VALUES (?, ?) ON CONFLICT (cell_key) DO NOTHING", rows);
    }

    /** Locality boundary as GeoJSON for the map. */
    public List<Map<String, Object>> boundaries() {
        return jdbc.queryForList("SELECT id, name, pincode, ST_AsGeoJSON(boundary) AS geojson FROM locality ORDER BY id");
    }

    public List<Store> loadStores() {
        return jdbc.query("SELECT id, code, name, locality_id, lat, lng, capacity_per_hour, active FROM warehouse ORDER BY id",
                (rs, i) -> new Store(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getDouble(5),
                        rs.getDouble(6), rs.getInt(7), rs.getBoolean(8)));
    }

    public List<Product> loadProducts() {
        return jdbc.query("SELECT id, sku, name, category, price FROM product ORDER BY id",
                (rs, i) -> new Product(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getDouble(5)));
    }

    public void loadStock(Map<Integer, Store> stores) {
        jdbc.query("SELECT warehouse_id, product_id, qty FROM inventory", rs -> {
            Store s = stores.get(rs.getInt(1));
            if (s != null) s.stock.put(rs.getInt(2), rs.getInt(3));
        });
    }

    /** rows: qty, warehouse_id, product_id */
    public void saveStock(List<Object[]> rows) {
        if (!rows.isEmpty()) jdbc.batchUpdate("UPDATE inventory SET qty = ? WHERE warehouse_id = ? AND product_id = ?", rows);
    }

    /** Operational event log (closures, waterlogging, toggles, overrides). Returns the id. */
    public long insertOpEvent(String type, Double lat, Double lng, Double radiusM, String details,
                              java.time.LocalDateTime starts, java.time.LocalDateTime ends) {
        Long id = jdbc.queryForObject("INSERT INTO operational_event (type, lat, lng, radius_m, details, starts_at, ends_at) "
                        + "VALUES (?,?,?,?,?,?,?) RETURNING id", Long.class, type, lat, lng, radiusM, details,
                com.dispatch.delivery.orders.OrderRepository.ts(starts), com.dispatch.delivery.orders.OrderRepository.ts(ends));
        if (id == null) throw new IllegalStateException("operational_event insert returned no id");
        return id;
    }

    /** New dark store with the standard starting stock; returns its id. */
    public int insertStore(String code, String name, int localityId, double lat, double lng, int capacity) {
        Integer id = jdbc.queryForObject("INSERT INTO warehouse (code, name, locality_id, lat, lng, capacity_per_hour) VALUES (?,?,?,?,?,?) RETURNING id",
                Integer.class, code, name, localityId, lat, lng, capacity);
        if (id == null) throw new IllegalStateException("warehouse insert returned no id");
        jdbc.update("INSERT INTO inventory (warehouse_id, product_id, qty) SELECT ?, id, 80 FROM product", id);
        return id;
    }

    public boolean storeCodeExists(String code) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM warehouse WHERE code = ?)", Boolean.class, code));
    }

    public void setCapacity(int storeId, int capacity) {
        jdbc.update("UPDATE warehouse SET capacity_per_hour = ? WHERE id = ?", capacity, storeId);
    }

    public void setActive(int storeId, boolean active) {
        jdbc.update("UPDATE warehouse SET active = ? WHERE id = ?", active, storeId);
    }
}
