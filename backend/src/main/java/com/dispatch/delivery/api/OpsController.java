package com.dispatch.delivery.api;

import com.dispatch.delivery.alerts.AlertService;
import com.dispatch.delivery.allocation.AllocationBenchmark;
import com.dispatch.delivery.allocation.RiderAllocator;
import com.dispatch.delivery.analytics.AnalyticsService;
import com.dispatch.delivery.forecast.RevenueForecastService;
import com.dispatch.delivery.geo.CoverageService;
import com.dispatch.delivery.model.Live.Scenario;
import com.dispatch.delivery.model.Live.Store;
import com.dispatch.delivery.simulation.SimulationEngine;
import com.dispatch.delivery.warehouses.WarehousePlanner;
import com.dispatch.delivery.warehouses.WarehouseRepository;
import com.dispatch.model.Models.BenchmarkRow;
import com.dispatch.service.BenchmarkService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** REST API of the delivery command center. All business decisions happen in the services; this only validates and routes. */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final SimulationEngine engine;
    private final AnalyticsService analytics;
    private final RevenueForecastService forecast;
    private final WarehousePlanner planner;
    private final WarehouseRepository warehouses;
    private final AlertService alerts;
    private final CoverageService coverage;
    private final BenchmarkService heapBench;

    public OpsController(SimulationEngine engine, AnalyticsService analytics, RevenueForecastService forecast, WarehousePlanner planner,
                         WarehouseRepository warehouses, AlertService alerts, CoverageService coverage, BenchmarkService heapBench) {
        this.engine = engine;
        this.analytics = analytics;
        this.forecast = forecast;
        this.planner = planner;
        this.warehouses = warehouses;
        this.alerts = alerts;
        this.coverage = coverage;
        this.heapBench = heapBench;
    }

    public record Control(Boolean running, Scenario scenario, Double demandMultiplier, Long tickMs) {}
    public record ActiveBody(Boolean active) {}
    public record MinutesBody(Integer minutes) {}
    public record RiderBody(Integer riderId) {}
    public record StoreBody(Integer storeId) {}
    public record DeltaBody(Integer delta) {}
    public record DisruptionBody(String type, Double lat, Double lng, Double radiusM, Integer minutes, String details) {}

    // ---- live state

    @GetMapping("/snapshot")
    public Map<String, Object> snapshot() { return engine.snapshot(false); }

    @GetMapping("/alerts")
    public List<AlertService.AlertView> alerts(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be 1..200");
        return alerts.recent(limit);
    }

    @PostMapping("/alerts/ack-all")
    public Map<String, Object> ackAll() { return Map.of("acknowledged", alerts.acknowledgeAll(engine.now())); }

    @PostMapping("/alerts/{id}/ack")
    public AlertService.AlertView ack(@PathVariable long id) { return alerts.acknowledge(id, engine.now()); }

    // ---- geography

    @GetMapping("/localities")
    public List<Map<String, Object>> localities(@RequestParam(defaultValue = "") String q) { return analytics.searchLocalities(q); }

    @GetMapping("/boundaries")
    public List<Map<String, Object>> boundaries() { return warehouses.boundaries(); }

    @GetMapping("/coverage")
    public List<Map<String, Object>> coverage() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Store s : engine.stores().values()) {
            Map<String, Object> m = new HashMap<>(Map.of("storeId", s.id, "code", s.code, "active", s.active, "polygon", s.coverage,
                    "roadDetoursFromOsrm", coverage.osrmDetoursKnown(s.id)));
            m.put("landGeojson", s.active ? warehouses.clipToLand(s.coverage) : null); // drawn shape: no delivery area over the sea
            out.add(m);
        }
        return out;
    }

    // ---- analytics

    @GetMapping("/analytics/aov")
    public Map<String, Object> aov(@RequestParam(defaultValue = "168") int hours) { return analytics.aov(hours); }

    @GetMapping("/analytics/customers/{id}")
    public Map<String, Object> customer(@PathVariable int id) { return analytics.customer(id); }

    @GetMapping("/analytics/weekday-weekend")
    public List<Map<String, Object>> weekdayWeekend(@RequestParam(defaultValue = "21") int days) { return analytics.weekdayWeekend(days); }

    @GetMapping("/analytics/localities")
    public List<Map<String, Object>> compare(@RequestParam List<Integer> ids, @RequestParam(defaultValue = "168") int hours) {
        return analytics.compareLocalities(ids, hours);
    }

    @GetMapping("/recommendations")
    public List<Map<String, Object>> recommendations() { return analytics.recommendations(); }

    @GetMapping("/localities/{id}/live")
    public Map<String, Object> localityLive(@PathVariable int id) { return analytics.localityLive(id); }

    @GetMapping("/warehouses/needed")
    public List<Map<String, Object>> warehousesNeeded(@RequestParam(defaultValue = "7") int days) { return analytics.warehousesNeeded(days); }

    public record OpenStoreBody(Integer localityId, Double lat, Double lng, Integer capacity, Integer riders) {}
    public record CapacityBody(Integer capacity) {}
    public record MoveRidersBody(Integer fromStoreId, Integer toStoreId, Integer count) {}
    public record HireBody(Integer storeId, Integer count) {}

    /** Where a new store would go and what it would change; creates nothing. */
    @GetMapping("/warehouses/preview")
    public Map<String, Object> previewStore(@RequestParam(required = false) Integer localityId, @RequestParam(required = false) Double lat,
                                            @RequestParam(required = false) Double lng) {
        return analytics.previewStore(localityId, lat, lng);
    }

    /** Opens exactly one dark store: the one the admin picked. */
    @PostMapping("/warehouses")
    public Map<String, Object> openStore(@RequestBody OpenStoreBody b) {
        if (b == null) throw new IllegalArgumentException("body required");
        return engine.openStore(b.localityId(), b.lat(), b.lng(), b.capacity() == null ? 70 : b.capacity(), b.riders() == null ? 4 : b.riders());
    }

    @PostMapping("/warehouses/{id}/capacity")
    public Map<String, Object> setCapacity(@PathVariable int id, @RequestBody CapacityBody b) {
        if (b == null || b.capacity() == null) throw new IllegalArgumentException("capacity required");
        engine.setCapacity(id, b.capacity());
        return Map.of("id", id, "capacity", b.capacity());
    }

    @PostMapping("/warehouses/{id}/restock")
    public Map<String, Object> restock(@PathVariable int id) { return Map.of("restocked", engine.restockStore(id)); }

    @PostMapping("/riders/move-between")
    public Map<String, Object> moveBetween(@RequestBody MoveRidersBody b) {
        if (b == null || b.fromStoreId() == null || b.toStoreId() == null || b.count() == null) throw new IllegalArgumentException("fromStoreId, toStoreId, count required");
        return Map.of("moved", engine.moveRidersBetween(b.fromStoreId(), b.toStoreId(), b.count()));
    }

    @PostMapping("/riders/fill-gaps")
    public Map<String, Object> fillGaps() { return Map.of("added", engine.fillRiderGaps()); }

    @PostMapping("/riders/trim")
    public Map<String, Object> trim() { return Map.of("removed", engine.trimRiders()); }

    @PostMapping("/riders/hire")
    public Map<String, Object> hire(@RequestBody HireBody b) {
        if (b == null || b.storeId() == null || b.count() == null) throw new IllegalArgumentException("storeId and count required");
        return Map.of("hired", engine.hireRiders(b.storeId(), b.count()));
    }

    @GetMapping("/warehouses/health")
    public List<Map<String, Object>> storeHealth(@RequestParam(defaultValue = "7") int days) { return analytics.storeHealth(days); }

    @GetMapping("/riders/staffing")
    public Map<String, Object> staffing() { return engine.staffingPlan(); }

    @PostMapping("/riders/staffing/apply")
    public Map<String, Object> applyStaffing() { return Map.of("moved", engine.applyStaffing()); }

    public record PriorityBody(com.dispatch.delivery.dispatch.SlaQueue.Policy policy) {}

    @PostMapping("/sim/priority")
    public Map<String, Object> priority(@RequestBody PriorityBody b) {
        if (b == null || b.policy() == null) throw new IllegalArgumentException("policy must be SLA or FIFO");
        engine.setPriority(b.policy());
        return Map.of("priority", engine.priority().name());
    }

    @GetMapping("/analytics/heatmap")
    public List<Map<String, Object>> heatmap(@RequestParam(defaultValue = "ALL_UNMET") String type, @RequestParam(defaultValue = "24") int hours) {
        return analytics.heatmap(type, hours);
    }

    @GetMapping("/analytics/revenue")
    public List<Map<String, Object>> revenue(@RequestParam(defaultValue = "24") int hours) { return analytics.revenueByHour(hours); }

    @GetMapping("/analytics/utilization")
    public Map<String, Object> utilization() { return analytics.utilization(); }

    @GetMapping("/forecast/revenue")
    public Map<String, Object> revenueForecast() { return forecast.forecast(); }

    // ---- warehouses

    @GetMapping("/warehouses/plan")
    public Map<String, Object> plan(@RequestParam(defaultValue = "12") double budget, @RequestParam(defaultValue = "95") double target,
                                    @RequestParam(defaultValue = "80") int capacity) {
        return planner.plan(budget, target, capacity);
    }

    @PostMapping("/warehouses/{id}/active")
    public Map<String, Object> setActive(@PathVariable int id, @RequestBody ActiveBody b) {
        if (b == null || b.active() == null) throw new IllegalArgumentException("active (true/false) is required");
        engine.setStoreActive(id, b.active());
        return Map.of("storeId", id, "active", b.active());
    }

    // ---- orders, riders, allocation

    @PostMapping("/orders/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        engine.cancelOrder(id);
        return Map.of("orderId", id, "status", "CANCELLED");
    }

    @PostMapping("/orders/{id}/escalate")
    public Map<String, Object> escalate(@PathVariable long id, @RequestBody(required = false) MinutesBody b) {
        int minutes = b == null || b.minutes() == null ? 10 : b.minutes();
        engine.escalateOrder(id, minutes);
        return Map.of("orderId", id, "escalatedMinutes", minutes);
    }

    @PostMapping("/orders/{id}/assign")
    public Map<String, Object> assign(@PathVariable long id, @RequestBody RiderBody b) {
        if (b == null || b.riderId() == null) throw new IllegalArgumentException("riderId is required");
        engine.assignManually(id, b.riderId());
        return Map.of("orderId", id, "riderId", b.riderId());
    }

    @PostMapping("/riders/{id}/move")
    public Map<String, Object> moveRider(@PathVariable int id, @RequestBody StoreBody b) {
        if (b == null || b.storeId() == null) throw new IllegalArgumentException("storeId is required");
        engine.moveRider(id, b.storeId());
        return Map.of("riderId", id, "homeStoreId", b.storeId());
    }

    @GetMapping("/allocation/compare")
    public List<RiderAllocator.Comparison> compareAllocation() { return engine.compareAllocation(); }

    @GetMapping("/benchmark")
    public Map<String, Object> benchmark() {
        List<BenchmarkRow> heap = new ArrayList<>();
        for (int n : new int[]{10_000, 100_000, 500_000}) heap.add(heapBench.escalationStormBench(n));
        return Map.of("priorityQueue", heap, "allocation", AllocationBenchmark.run(new int[]{20, 60, 150}));
    }

    // ---- simulation / what-if

    @PostMapping("/sim/control")
    public Map<String, Object> control(@RequestBody Control c) {
        if (c == null) throw new IllegalArgumentException("body is required");
        if (c.scenario() != null) engine.setScenario(c.scenario());
        if (c.demandMultiplier() != null) engine.setDemandMultiplier(c.demandMultiplier());
        if (c.tickMs() != null) engine.setTickMs(c.tickMs());
        if (c.running() != null) engine.setRunning(c.running());
        return Map.of("running", engine.running(), "scenario", engine.traffic().scenario(), "demandMultiplier", engine.demandMultiplier(), "tickMs", engine.tickMs());
    }

    @PostMapping("/sim/riders")
    public Map<String, Object> riders(@RequestBody DeltaBody b) {
        if (b == null || b.delta() == null) throw new IllegalArgumentException("delta is required");
        return Map.of("changed", engine.changeRiders(b.delta()));
    }

    @PostMapping("/sim/disruptions")
    public Map<String, Object> disruption(@RequestBody DisruptionBody b) {
        if (b == null || b.type() == null || b.lat() == null || b.lng() == null) throw new IllegalArgumentException("type, lat and lng are required");
        long id = engine.addDisruption(b.type(), b.lat(), b.lng(), b.radiusM() == null ? 500 : b.radiusM(),
                b.minutes() == null ? 60 : b.minutes(), b.details());
        return Map.of("id", id);
    }
}
