package com.dispatch.delivery;

import com.dispatch.delivery.allocation.RiderAllocator;
import com.dispatch.delivery.dispatch.Batcher;
import com.dispatch.delivery.geo.CoverageService;
import com.dispatch.delivery.geo.TrafficModel;
import com.dispatch.delivery.model.Live.*;
import com.dispatch.delivery.warehouses.WarehousePlanner;
import com.dispatch.service.RoadRouter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Batching, allocation, coverage and warehouse planning without a database. */
class DispatchLogicTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 14, 13, 0); // weekday afternoon, normal traffic
    final TrafficModel traffic = new TrafficModel();
    final Store store = new Store(1, "S1", "Store", 1, 19.18, 72.84, 80, true);
    final Map<Integer, Store> stores = Map.of(1, store);

    Order order(long id, double dLat, double dLng, int promiseMin) {
        Order o = new Order(id, 1, 1, 1, store.lat + dLat, store.lng + dLng, 200, 2, false, List.of(), NOW, NOW.plusMinutes(promiseMin), id);
        o.status = OrderStatus.READY;
        o.prepDoneAt = NOW;
        return o;
    }

    // ---------------------------------------------------------------- batching

    @Test
    void nearbyOrdersFromTheSameStoreAreBatched() {
        List<Batcher.Batch> b = Batcher.build(List.of(order(1, 0.01, 0, 25), order(2, 0.011, 0.001, 25)), stores, traffic, NOW, 3);
        assertEquals(1, b.size());
        assertEquals(2, b.get(0).drops().size());
    }

    @Test
    void farApartOrDifferentStoreOrdersAreNotBatched() {
        Store other = new Store(2, "S2", "Other", 1, 19.13, 72.83, 80, true);
        Order far = order(2, -0.02, 0, 25);
        Order elsewhere = order(3, 0.01, 0, 25);
        elsewhere.storeId = 2;
        List<Batcher.Batch> b = Batcher.build(List.of(order(1, 0.01, 0, 25), far, elsewhere), Map.of(1, store, 2, other), traffic, NOW, 3);
        assertEquals(3, b.size());
    }

    @Test
    void batchingNeverBreaksAnSlaOrExceedsCapacity() {
        // Second order's promise is too tight to wait for a shared trip.
        List<Batcher.Batch> tight = Batcher.build(List.of(order(1, 0.012, 0, 25), order(2, 0.010, 0.003, 4)), stores, traffic, NOW, 3);
        assertEquals(2, tight.size());
        List<Order> many = new ArrayList<>();
        for (int i = 0; i < 6; i++) many.add(order(i, 0.01 + i * 0.0005, 0, 40));
        Batcher.build(many, stores, traffic, NOW, 2).forEach(b -> assertTrue(b.drops().size() <= 2));
        assertThrows(IllegalArgumentException.class, () -> Batcher.build(many, stores, traffic, NOW, 0));
    }

    // ---------------------------------------------------------------- allocation

    @Test
    void minCostBeatsPlainMaxFlowOnEtaWithTheSameNumberAssigned() {
        Random r = new Random(5);
        List<Rider> riders = new ArrayList<>();
        List<Batcher.Batch> batches = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            riders.add(new Rider(i, "R" + i, "R" + i, 1, 2, store.lat + (r.nextDouble() - 0.5) * 0.04, store.lng + (r.nextDouble() - 0.5) * 0.04));
            batches.add(new Batcher.Batch("B" + i, store, List.of(order(i, (r.nextDouble() - 0.5) * 0.04, (r.nextDouble() - 0.5) * 0.04, 20)), 0));
        }
        Map<String, RiderAllocator.Comparison> c = new HashMap<>();
        RiderAllocator.compare(batches, riders, traffic, NOW).forEach(x -> c.put(x.algorithm(), x));
        assertEquals(c.get("EDMONDS_KARP").assigned(), c.get("MIN_COST").assigned());
        assertEquals(c.get("DINIC").assigned(), c.get("MIN_COST").assigned());
        assertTrue(c.get("MIN_COST").totalEtaMin() <= c.get("EDMONDS_KARP").totalEtaMin());
        assertTrue(c.get("MIN_COST").totalEtaMin() <= c.get("DINIC").totalEtaMin());
    }

    @Test
    void allocationRespectsCapacityAndPickupDistance() {
        Rider small = new Rider(1, "R1", "R1", 1, 1, store.lat, store.lng);
        Rider far = new Rider(2, "R2", "R2", 1, 3, 19.30, 72.95); // ~17 km away
        Batcher.Batch pair = new Batcher.Batch("B", store, List.of(order(1, 0.01, 0, 30), order(2, 0.011, 0, 30)), 0);
        assertTrue(RiderAllocator.allocate(List.of(pair), List.of(small, far), traffic, NOW, RiderAllocator.Algo.MIN_COST).isEmpty());
        assertTrue(RiderAllocator.allocate(List.of(), List.of(small), traffic, NOW, RiderAllocator.Algo.MIN_COST).isEmpty());
    }

    // ---------------------------------------------------------------- coverage

    @Test
    void pointInPolygonAndBearingMath() {
        double[][] square = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        assertTrue(CoverageService.contains(square, 0.5, 0.5));
        assertFalse(CoverageService.contains(square, 1.5, 0.5));
        assertFalse(CoverageService.contains(new double[0][], 0, 0));
        double[] p = CoverageService.destination(19.18, 72.84, 90, 2.0);
        assertEquals(2.0, com.dispatch.algo.Geo.km(19.18, 72.84, p[0], p[1]), 0.01);
    }

    @Test
    void coverageShrinksInMonsoonAndAtClosures() {
        RoadRouter offline = new RoadRouter(JsonMapper.builder().build(), "http://127.0.0.1:1/table/v1/driving/");
        CoverageService cov = new CoverageService(traffic, offline);
        cov.recompute(List.of(store), NOW);
        double normal = reach(store);
        assertTrue(CoverageService.contains(store.coverage, store.lat + 0.005, store.lng));
        traffic.setScenario(Scenario.MONSOON);
        cov.recompute(List.of(store), NOW);
        double monsoon = reach(store);
        assertTrue(monsoon < normal * 0.7, "monsoon " + monsoon + " vs normal " + normal);
        traffic.setScenario(Scenario.NORMAL);
        traffic.addDisruption(new Disruption(1, "ROAD_CLOSURE", store.lat + 0.01, store.lng, 500, NOW.plusHours(1), "test"));
        cov.recompute(List.of(store), NOW);
        assertFalse(CoverageService.contains(store.coverage, store.lat + 0.015, store.lng), "nothing beyond a closure");
        store.active = false;
        cov.recompute(List.of(store), NOW);
        assertEquals(0, store.coverage.length);
    }

    private static double reach(Store s) {
        return Arrays.stream(s.coverage).mapToDouble(p -> com.dispatch.algo.Geo.km(s.lat, s.lng, p[0], p[1])).sum();
    }

    // ---------------------------------------------------------------- road distances

    @Test
    void roadDistanceUsesCachedOsrmRatioAndQueuesUnknownPairs() {
        boolean[] known = {false};
        RoadRouter fake = new RoadRouter(JsonMapper.builder().build(), "unused") {
            @Override public Double cached(double[] a, double[] b) {
                return known[0] ? 2 * com.dispatch.algo.Geo.km(a[0], a[1], b[0], b[1]) : null; // roads twice the straight line
            }
        };
        TrafficModel t = new TrafficModel(fake);
        double straight = com.dispatch.algo.Geo.km(19.18, 72.84, 19.20, 72.86);
        assertEquals(straight * TrafficModel.DEFAULT_DETOUR, t.roadKm(19.18, 72.84, 19.20, 72.86), 1e-9); // unknown: fallback
        assertEquals(1, t.pendingRoads());
        known[0] = true;
        assertEquals(straight * 2, t.roadKm(19.18, 72.84, 19.20, 72.86), straight * 0.02);
        assertEquals(2.0, t.detour(19.18, 72.84, 19.20, 72.86), 0.03);
        assertEquals(0.1 * TrafficModel.DEFAULT_DETOUR, t.roadKm(19.18, 72.84, 19.18, 72.84 + 0.1 / 105.0), 0.01); // < 300 m: no lookup
    }

    // ---------------------------------------------------------------- warehouse planning (set cover)

    @Test
    void greedySetCoverOpensOneStorePerFarApartCluster() {
        List<WarehousePlanner.Cell> cells = new ArrayList<>();
        for (int i = 0; i < 5; i++) cells.add(new WarehousePlanner.Cell(19.10 + i * 0.002, 72.83, 4));
        for (int i = 0; i < 5; i++) cells.add(new WarehousePlanner.Cell(19.25 + i * 0.002, 72.87, 4));
        WarehousePlanner planner = new WarehousePlanner(null, null);
        WarehousePlanner.Travel t = (a, b) -> traffic.travelMin(a[0], a[1], b[0], b[1], NOW);
        assertEquals(2, planner.greedy(cells, 12, 100, 80, t).size());
        assertEquals(1, planner.greedy(cells, 12, 50, 80, t).size());
        assertEquals(6, planner.greedy(cells, 12, 100, 10, t).size(), "capacity 10/h absorbs 2 cells (8/h): 3 stores per 5-cell cluster");
        assertTrue(planner.greedy(List.of(), 12, 95, 80, t).isEmpty()); // no demand, no stores
    }
}
