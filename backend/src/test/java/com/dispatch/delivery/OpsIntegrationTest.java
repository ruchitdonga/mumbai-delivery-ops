package com.dispatch.delivery;

import com.dispatch.PostgisTestBase;
import com.dispatch.delivery.model.Live;
import com.dispatch.delivery.simulation.SimulationEngine;
import com.dispatch.delivery.warehouses.WarehouseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** End to end against a real PostGIS database: migrations, history, live ticks, persistence and the REST API. */
class OpsIntegrationTest extends PostgisTestBase {

    @Autowired SimulationEngine engine;
    @Autowired JdbcTemplate jdbc;
    @Autowired WarehouseRepository warehouses;
    @Value("${local.server.port}") int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void migrationsReferenceDataAndHistoryAreInPlace() {
        assertEquals(58, jdbc.queryForObject("SELECT COUNT(*) FROM locality", Integer.class)); // V2 suburbs + V3 rest of Mumbai + V7 gaps
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM warehouse", Integer.class) >= 21); // 21 from migrations (+ any a test opened)
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE code IN ('DS-BOR-W', 'DS-MUL-W')", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM locality WHERE population IS NULL", Integer.class));
        // Every locality and every store got customers / riders, including the ones V3 added.
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM locality l WHERE NOT EXISTS (SELECT 1 FROM customer c WHERE c.locality_id = l.id)", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM warehouse w WHERE NOT EXISTS (SELECT 1 FROM rider r WHERE r.home_warehouse_id = w.id)", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class) > 500, "2 days of history expected");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM order_event", Integer.class) > 1000);
        // Generated geography column + GiST index work: every order lies inside some locality boundary or near it.
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN locality l ON ST_DWithin(l.boundary, o.geo, 50)", Integer.class) > 0);
    }

    @Test
    void postgisPointInPolygonFindsTheLocality() {
        Integer id = warehouses.localityAt(19.1840, 72.8380); // Malad West dark store
        assertNotNull(id);
        assertEquals("Malad West", jdbc.queryForObject("SELECT name FROM locality WHERE id = ?", String.class, id));
        assertEquals("Colaba", jdbc.queryForObject("SELECT name FROM locality WHERE id = ?", String.class, warehouses.localityAt(18.91, 72.82)));
        assertNull(warehouses.localityAt(18.95, 72.78)); // Arabian Sea: outside every locality
    }

    @Test
    void liveTicksPlaceAndPersistOrdersWithFullEventHistory() {
        long before = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        engine.setDemandMultiplier(3.0);
        for (int i = 0; i < 90; i++) engine.tick();
        engine.setDemandMultiplier(1.0);
        long after = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        assertTrue(after > before, "ticks should create orders");
        // Some live order went through the whole lifecycle, and each step is an event row.
        List<String> types = jdbc.queryForList("SELECT e.event_type FROM order_event e WHERE e.order_id = ("
                + "SELECT id FROM orders WHERE id > ? AND status = 'DELIVERED' ORDER BY id LIMIT 1) ORDER BY e.id", String.class, before);
        assertTrue(types.containsAll(List.of("PLACED", "PREPARING", "ASSIGNED", "READY", "PICKED_UP", "DELIVERED")), types.toString());
        assertEquals("PLACED", types.get(0));
        assertEquals("DELIVERED", types.get(types.size() - 1));
        assertTrue(types.indexOf("PICKED_UP") > types.indexOf("READY"), "never picked up before packing finished");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_assignment WHERE algorithm = 'MIN_COST_MAX_FLOW'", Integer.class) > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM rider_location", Integer.class) > 0);
        // In memory and in the database agree on which orders are still open.
        long openDb = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status NOT IN ('DELIVERED','CANCELLED')", Long.class);
        assertEquals(engine.openOrders().size(), openDb);
    }

    @Test
    void restartRestoresInFlightOrdersRidersAndTodaysKpis() {
        engine.setDemandMultiplier(3.0);
        for (int i = 0; i < 40; i++) engine.tick();
        engine.setDemandMultiplier(1.0);
        Map<Long, Integer> riderOf = new java.util.HashMap<>();
        engine.openOrders().forEach(o -> riderOf.put(o.id, o.riderId));
        assertFalse(riderOf.isEmpty(), "need orders in flight to test a restart");
        Object revenue = engine.kpis().get("revenueToday");

        engine.reloadFromDatabase();

        Map<Long, Integer> after = new java.util.HashMap<>();
        engine.openOrders().forEach(o -> after.put(o.id, o.riderId));
        assertEquals(riderOf.keySet(), after.keySet(), "every open order comes back");
        riderOf.forEach((id, rider) -> { if (rider != null) assertEquals(rider, after.get(id), "order " + id + " keeps its rider"); });
        assertEquals(revenue, engine.kpis().get("revenueToday"), "today's revenue survives the restart");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE cancel_reason LIKE 'SIM_RESTART%'", Integer.class));
        for (int i = 0; i < 60; i++) engine.tick(); // restored riders finish their deliveries
        assertTrue(riderOf.keySet().stream().anyMatch(id -> engine.openOrders().stream().noneMatch(o -> o.id == id)));
    }

    @Test
    void restApiServesAnalyticsAndValidatesInput() throws Exception {
        for (String p : List.of("/api/ops/snapshot", "/api/ops/analytics/aov?hours=48", "/api/ops/analytics/weekday-weekend?days=7",
                "/api/ops/analytics/localities?ids=1,2", "/api/ops/analytics/heatmap?type=UNSERVICEABLE&hours=48",
                "/api/ops/analytics/utilization", "/api/ops/forecast/revenue", "/api/ops/warehouses/plan", "/api/ops/allocation/compare",
                "/api/ops/coverage", "/api/ops/localities?q=400064")) {
            assertEquals(200, call("GET", p, null).statusCode(), p);
        }
        assertEquals(400, call("GET", "/api/ops/analytics/heatmap?type=DROP%20TABLE", null).statusCode());
        assertEquals(400, call("GET", "/api/ops/warehouses/plan?budget=1", null).statusCode());
        assertEquals(404, call("POST", "/api/ops/orders/999999999/cancel", null).statusCode());
        assertEquals(400, call("POST", "/api/ops/sim/disruptions", "{\"type\":\"ROAD_CLOSURE\",\"lat\":28.6,\"lng\":77.2}").statusCode()); // Delhi
        assertEquals(400, call("POST", "/api/ops/sim/control", "{\"tickMs\":5}").statusCode());
        assertTrue(call("GET", "/api/ops/localities?q=400064", null).body().contains("Malad West"));
    }

    @Test
    void pointsApiCoversStaffingStoresNeededLiveLocalityAndPriority() throws Exception {
        HttpResponse<String> r = call("GET", "/api/ops/warehouses/needed?days=2", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("stores_needed") && r.body().contains("Borivali West"), r.body());
        int id = jdbc.queryForObject("SELECT id FROM locality WHERE name = 'Borivali East'", Integer.class);
        r = call("GET", "/api/ops/localities/" + id + "/live", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"population\":270000") && r.body().contains("lastHour"), r.body());
        assertEquals(404, call("GET", "/api/ops/localities/999999/live", null).statusCode());
        r = call("GET", "/api/ops/warehouses/health?days=2", null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"status\"") && r.body().contains("\"reasons\"") && r.body().contains("DS-BOR-W"), r.body());
        assertTrue(call("GET", "/api/ops/warehouses/needed?days=2", null).body().contains("\"reason\""));
        r = call("GET", "/api/ops/recommendations", null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"priority\"") && r.body().contains("\"why\""), r.body());
        r = call("GET", "/api/ops/riders/staffing", null);
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("ridersNeeded"), r.body());
        assertEquals(200, call("POST", "/api/ops/riders/staffing/apply", null).statusCode());
        r = call("GET", "/api/ops/analytics/aov?hours=48", null);
        assertTrue(r.body().contains("spendTiers"), r.body());
        r = call("GET", "/api/ops/analytics/weekday-weekend?days=7", null);
        assertTrue(r.body().contains("next_day_revenue_estimate"), r.body());
        r = call("POST", "/api/ops/sim/priority", "{\"policy\":\"FIFO\"}");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("FIFO", engine.priority().name());
        assertEquals(400, call("POST", "/api/ops/sim/priority", "{\"policy\":\"NOPE\"}").statusCode());
        call("POST", "/api/ops/sim/priority", "{\"policy\":\"SLA\"}");
    }

    @Test
    void oneClickFixesOpenOneStoreHireMoveRestockAndRaiseCapacity() throws Exception {
        int area = jdbc.queryForObject("SELECT id FROM locality WHERE name = 'Vile Parle East'", Integer.class);
        int storesBefore = engine.stores().size(), ridersBefore = engine.riders().size();
        HttpResponse<String> r = call("GET", "/api/ops/warehouses/preview?localityId=" + area, null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"verdict\"") && r.body().contains("DS-VIL-E") && r.body().contains("\"nearestStores\""), r.body());
        assertEquals(storesBefore, engine.stores().size(), "a preview must not create a store");
        r = call("POST", "/api/ops/warehouses", "{\"localityId\":" + area + ",\"riders\":3}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("DS-VIL-E"), r.body());
        assertEquals(storesBefore + 1, engine.stores().size()); // exactly the one picked
        assertEquals(ridersBefore + 3, engine.riders().size());
        int id = jdbc.queryForObject("SELECT id FROM warehouse WHERE code = 'DS-VIL-E'", Integer.class);
        assertEquals(24, jdbc.queryForObject("SELECT COUNT(*) FROM inventory WHERE warehouse_id = ?", Integer.class, id));

        assertEquals(200, call("POST", "/api/ops/riders/hire", "{\"storeId\":" + id + ",\"count\":2}").statusCode());
        assertEquals(5, engine.riders().stream().filter(x -> x.homeStoreId == id).count());
        int other = jdbc.queryForObject("SELECT id FROM warehouse WHERE code = 'DS-AND-E'", Integer.class);
        r = call("POST", "/api/ops/riders/move-between", "{\"fromStoreId\":" + id + ",\"toStoreId\":" + other + ",\"count\":2}");
        assertTrue(r.body().contains("\"moved\":2"), r.body());
        assertEquals(3, engine.riders().stream().filter(x -> x.homeStoreId == id).count());

        engine.stores().get(id).stock.replaceAll((p, q) -> 5);
        assertTrue(call("POST", "/api/ops/warehouses/" + id + "/restock", null).body().contains("\"restocked\":24"));
        assertEquals(120, engine.stores().get(id).stock.values().iterator().next());
        assertEquals(200, call("POST", "/api/ops/warehouses/" + id + "/capacity", "{\"capacity\":110}").statusCode());
        assertEquals(110, jdbc.queryForObject("SELECT capacity_per_hour FROM warehouse WHERE id = ?", Integer.class, id));

        assertEquals(400, call("POST", "/api/ops/warehouses/" + id + "/capacity", "{\"capacity\":5}").statusCode());
        assertEquals(400, call("POST", "/api/ops/riders/hire", "{\"storeId\":" + id + ",\"count\":99}").statusCode());
        assertEquals(404, call("POST", "/api/ops/warehouses", "{\"localityId\":999999}").statusCode());
        engine.setStoreActive(id, false); // leave the shared test city as it was for coverage-based tests
    }

    @Autowired com.dispatch.delivery.alerts.AlertService alertService;

    @Test
    void alertFeedComesFromTheDatabaseSoItSurvivesRestarts() throws Exception {
        var a = alertService.raise("SLA_RISK", "WARNING", "test alert", null, null, null, engine.now());
        HttpResponse<String> r = call("GET", "/api/ops/alerts?limit=5", null);
        assertTrue(r.body().contains("\"id\":" + a.id()), r.body()); // read back from the table, not from memory
        r = call("POST", "/api/ops/alerts/" + a.id() + "/ack", null);
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.body().contains("\"acknowledged\":true"), r.body());
        assertEquals(404, call("POST", "/api/ops/alerts/999999999/ack", null).statusCode());
    }

    @Test
    void fillingGapsThenTrimmingNeverUndoesTheFill() throws Exception {
        assertEquals(200, call("POST", "/api/ops/riders/fill-gaps", null).statusCode());
        assertEquals(200, call("POST", "/api/ops/riders/trim", null).statusCode());
        for (var s : engine.stores().values()) {
            if (!s.active) continue;
            long have = engine.riders().stream().filter(r -> r.homeStoreId == s.id).count();
            int need = com.dispatch.delivery.simulation.SimulationEngine.ridersFor(Math.max(s.recentOrders.size(), engine.expectedOrders(s.id, 1)));
            assertTrue(have >= need, s.code + " has " + have + " riders after trim but needs " + need + " next hour");
        }
    }

    @Test
    void warehouseToggleIsPersistedAndReroutesCoverage() throws Exception {
        int id = engine.stores().values().iterator().next().id;
        assertEquals(200, call("POST", "/api/ops/warehouses/" + id + "/active", "{\"active\":false}").statusCode());
        assertFalse(jdbc.queryForObject("SELECT active FROM warehouse WHERE id = ?", Boolean.class, id));
        engine.tick(); // coverage recomputed
        Live.Store s = engine.stores().get(id);
        assertEquals(0, s.coverage.length, "inactive store serves nobody");
        assertEquals(200, call("POST", "/api/ops/warehouses/" + id + "/active", "{\"active\":true}").statusCode());
        engine.tick();
        assertTrue(engine.stores().get(id).coverage.length > 0);
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM operational_event WHERE type = 'WAREHOUSE_TOGGLE'", Integer.class) >= 2);
    }

    @Test
    void roadClosureCreatesAlertAndShrinksCoverage() throws Exception {
        Live.Store s = engine.stores().values().iterator().next();
        engine.tick();
        int before = (int) java.util.Arrays.stream(s.coverage).mapToDouble(p -> com.dispatch.algo.Geo.km(s.lat, s.lng, p[0], p[1])).sum();
        HttpResponse<String> r = call("POST", "/api/ops/sim/disruptions",
                "{\"type\":\"ROAD_CLOSURE\",\"lat\":" + (s.lat + 0.01) + ",\"lng\":" + s.lng + ",\"radiusM\":600,\"minutes\":30}");
        assertEquals(200, r.statusCode());
        engine.tick();
        int after = (int) java.util.Arrays.stream(s.coverage).mapToDouble(p -> com.dispatch.algo.Geo.km(s.lat, s.lng, p[0], p[1])).sum();
        assertTrue(after < before, "closure north of the store must cut its reach: " + before + " -> " + after);
        List<Map<String, Object>> alerts = jdbc.queryForList("SELECT type FROM alert WHERE type = 'ROAD_CLOSURE'");
        assertFalse(alerts.isEmpty());
    }
}
