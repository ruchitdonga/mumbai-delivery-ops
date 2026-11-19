package com.dispatch.api;

import com.dispatch.model.Models.*;
import com.dispatch.service.BenchmarkService;
import com.dispatch.service.DispatchService;
import com.dispatch.service.ForecastService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final DispatchService dispatch;
    private final ForecastService forecast;
    private final BenchmarkService benchmark;

    public ApiController(DispatchService dispatch, ForecastService forecast, BenchmarkService benchmark) {
        this.dispatch = dispatch;
        this.forecast = forecast;
        this.benchmark = benchmark;
    }

    @GetMapping("/state")
    public StateView state() {
        return dispatch.state();
    }

    /** Upload a whole scenario (hospitals, ambulances, zones, optional emergencies). Replaces current state. */
    @PostMapping("/scenario")
    public StateView upload(@RequestBody Scenario scenario) {
        dispatch.load(scenario);
        return dispatch.state();
    }

    @PostMapping("/reset")
    public StateView reset() {
        dispatch.resetToSample();
        return dispatch.state();
    }

    @PostMapping("/emergencies")
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyView addEmergency(@RequestBody NewEmergency e) {
        return dispatch.addEmergency(e);
    }

    @PostMapping("/emergencies/{id}/escalate")
    public EmergencyView escalate(@PathVariable String id, @RequestBody EscalateRequest r) {
        return dispatch.escalate(id, r == null ? null : r.severity());
    }

    /** Called when an ambulance reaches the hospital: frees it and parks it there. */
    @PostMapping("/ambulances/{id}/arrive")
    public AmbulanceView arrive(@PathVariable String id) {
        return dispatch.arrive(id);
    }

    @PostMapping("/simulate")
    public Map<String, Integer> simulate(@RequestBody(required = false) SimulateRequest r) {
        return Map.of("added", dispatch.simulateCalls(r));
    }

    @PostMapping("/dispatch")
    public DispatchResult dispatch(@RequestBody DispatchRequest r) {
        return dispatch.dispatch(r);
    }

    /** Dry-runs both strategies on the same queue so they can be compared side by side. */
    @PostMapping("/compare")
    public List<DispatchResult> compare(@RequestBody(required = false) DispatchRequest r) {
        DispatchRequest base = r == null ? new DispatchRequest(null, null, null, false) : r;
        return List.of(
                dispatch.dispatch(new DispatchRequest(base.ambulanceRadiusKm(), base.hospitalRadiusKm(), Strategy.MAXFLOW, false)),
                dispatch.dispatch(new DispatchRequest(base.ambulanceRadiusKm(), base.hospitalRadiusKm(), Strategy.GREEDY, false)));
    }

    @PostMapping("/forecast")
    public ForecastResult forecast(@RequestBody(required = false) ForecastRequest r) {
        return forecast.forecast(r);
    }

    @GetMapping("/benchmark")
    public List<BenchmarkRow> benchmark() {
        return benchmark.runAll();
    }
}
