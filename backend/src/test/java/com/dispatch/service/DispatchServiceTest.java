package com.dispatch.service;

import com.dispatch.model.Models.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DispatchServiceTest {

    private DispatchService svc;

    // Central Mumbai; 0.01 deg of longitude is about 1 km here.
    private static final double LAT = 19.05, LNG = 72.85;

    @BeforeEach
    void setUp() {
        svc = new DispatchService(JsonMapper.builder().build());
    }

    private static Hospital hospital(String id, int beds, Speciality... s) {
        return new Hospital(id, "Hospital " + id, LAT, LNG + 0.01, beds, Set.of(s));
    }

    private static Ambulance ambulance(String id, double dLng) {
        return new Ambulance(id, LAT, LNG + dLng);
    }

    private static NewEmergency call(int severity, Speciality type, double dLng) {
        return new NewEmergency(LAT, LNG + dLng, severity, type);
    }

    private static DispatchRequest req(Strategy s) {
        return new DispatchRequest(5.0, 5.0, s, true);
    }

    @Test
    void sampleScenarioLoadsAndDispatches() {
        int waiting = svc.state().queue().size();
        assertTrue(waiting > 0);
        DispatchResult r = svc.dispatch(new DispatchRequest(8.0, 10.0, Strategy.MAXFLOW, false));
        assertTrue(r.served() > 0);
        assertEquals(waiting, r.served() + r.unservedCount());
    }

    @Test
    void higherSeverityWinsTheOnlyAmbulance() {
        svc.load(new Scenario(List.of(hospital("H1", 5, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(2, Speciality.GENERAL, 0), call(5, Speciality.GENERAL, 0.001))));
        DispatchResult r = svc.dispatch(req(Strategy.MAXFLOW));
        assertEquals(1, r.served());
        assertEquals(5, r.assignments().get(0).severity());
    }

    @Test
    void escalationUsesDecreaseKeyAndReordersQueue() {
        svc.load(new Scenario(List.of(hospital("H1", 5, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(3, Speciality.GENERAL, 0), call(2, Speciality.GENERAL, 0))));
        assertEquals("E1", svc.state().queue().get(0).id());
        svc.escalate("E2", 5);
        assertEquals("E2", svc.state().queue().get(0).id());
        assertThrows(IllegalArgumentException.class, () -> svc.escalate("E2", 4)); // must increase
        assertThrows(NoSuchElementException.class, () -> svc.escalate("E99", 5));
    }

    @Test
    void maxFlowServesMoreThanGreedyOnTrapInstance() {
        // A1 is nearest to call 1 but is also the only ambulance in range of call 2.
        // Greedy gives A1 to call 1 and strands call 2; max-flow sends A2 to call 1 instead.
        Scenario trap = new Scenario(
                List.of(hospital("H1", 5, Speciality.GENERAL)),
                List.of(ambulance("A1", 0.0), ambulance("A2", -0.04)),
                List.of(),
                List.of(call(3, Speciality.GENERAL, -0.01), call(3, Speciality.GENERAL, 0.04)));
        svc.load(trap);
        int greedy = svc.dispatch(new DispatchRequest(5.0, 10.0, Strategy.GREEDY, false)).served();
        int flow = svc.dispatch(new DispatchRequest(5.0, 10.0, Strategy.MAXFLOW, false)).served();
        assertEquals(1, greedy);
        assertEquals(2, flow);
    }

    @Test
    void unservedCallsGetHonestReasons() { // infeasible: no burns unit anywhere
        svc.load(new Scenario(List.of(hospital("H1", 5, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(5, Speciality.BURNS, 0))));
        DispatchResult r = svc.dispatch(req(Strategy.MAXFLOW));
        assertEquals(0, r.served());
        assertTrue(r.unserved().get(0).reason().contains("No hospital offering BURNS"));
    }

    @Test
    void applyConsumesBedsAndAmbulancesAndDryRunDoesNot() {
        svc.load(new Scenario(List.of(hospital("H1", 1, Speciality.GENERAL)), List.of(ambulance("A1", 0), ambulance("A2", 0.001)), List.of(),
                List.of(call(4, Speciality.GENERAL, 0), call(4, Speciality.GENERAL, 0))));
        DispatchResult dry = svc.dispatch(new DispatchRequest(5.0, 5.0, Strategy.MAXFLOW, false));
        assertEquals(1, dry.served()); // only one bed
        assertEquals(2, svc.state().queue().size());
        svc.dispatch(req(Strategy.MAXFLOW));
        assertEquals(1, svc.state().queue().size());
        assertEquals(0, svc.state().hospitals().get(0).freeBeds());
        assertTrue(svc.dispatch(req(Strategy.MAXFLOW)).unserved().get(0).reason().contains("full"));
    }

    @Test
    void invalidScenariosRejected() { // validation: empty, duplicate, out of area, bad severity, bad radius
        assertThrows(IllegalArgumentException.class, () -> svc.load(new Scenario(List.of(), List.of(ambulance("A", 0)), null, null)));
        assertThrows(IllegalArgumentException.class, () -> svc.load(new Scenario(
                List.of(hospital("H", 1, Speciality.GENERAL), hospital("H", 1, Speciality.GENERAL)), List.of(ambulance("A", 0)), null, null)));
        assertThrows(IllegalArgumentException.class, () -> svc.addEmergency(new NewEmergency(28.6, 77.2, 3, Speciality.GENERAL))); // Delhi
        assertThrows(IllegalArgumentException.class, () -> svc.addEmergency(new NewEmergency(LAT, LNG, 9, Speciality.GENERAL)));
        assertThrows(IllegalArgumentException.class, () -> svc.dispatch(new DispatchRequest(-1.0, 5.0, null, false)));
    }

    @Test
    void maxFlowPlusLocalSearchIsFeasibleAndNeverServesFewerThanGreedy() { // stress on 120 random calls
        svc.simulateCalls(new SimulateRequest(120, 1L));
        DispatchRequest base = new DispatchRequest(6.0, 8.0, Strategy.MAXFLOW, false);
        DispatchResult mf = svc.dispatch(base);
        DispatchResult gr = svc.dispatch(new DispatchRequest(6.0, 8.0, Strategy.GREEDY, false));
        assertTrue(mf.served() >= gr.served(), mf.served() + " < " + gr.served());

        Set<String> ambulancesUsed = new java.util.HashSet<>();
        java.util.Map<String, Integer> bedsUsed = new java.util.HashMap<>();
        for (Assignment a : mf.assignments()) {
            assertTrue(ambulancesUsed.add(a.ambulanceId()), "ambulance used twice: " + a.ambulanceId());
            bedsUsed.merge(a.hospitalId(), 1, Integer::sum);
            assertTrue(a.pickupKm() <= 6.05 && a.transportKm() <= 8.05, "assignment outside radius");
        }
        for (Hospital h : svc.state().hospitals()) {
            assertTrue(bedsUsed.getOrDefault(h.id(), 0) <= h.freeBeds(), "over-filled " + h.name());
        }
    }

    @Test
    void arrivalFreesAmbulanceAtHospitalAndKeepsBedTaken() {
        svc.load(new Scenario(List.of(hospital("H1", 2, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(4, Speciality.GENERAL, 0.002))));
        svc.dispatch(req(Strategy.MAXFLOW));
        assertEquals(1, svc.state().missions().size());
        assertTrue(svc.state().ambulances().get(0).busy());

        AmbulanceView a = svc.arrive("A1");
        assertFalse(a.busy());
        StateView s = svc.state();
        assertTrue(s.missions().isEmpty());
        assertFalse(s.ambulances().get(0).busy());
        assertEquals(LNG + 0.01, s.ambulances().get(0).lng(), 1e-9); // parked at the hospital
        assertEquals(1, s.hospitals().get(0).freeBeds());             // patient admitted, bed still used
        assertThrows(NoSuchElementException.class, () -> svc.arrive("A1")); // second arrival rejected
    }

    @Test
    void bedFreesUpOnlyAfterTheHospitalStayEnds() {
        long[] now = {1_000_000};
        svc.clock = () -> now[0];
        svc.load(new Scenario(List.of(hospital("H1", 1, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(3, Speciality.GENERAL, 0.002))));
        svc.dispatch(req(Strategy.MAXFLOW));
        assertEquals(0, svc.state().hospitals().get(0).freeBeds()); // taken at dispatch
        svc.arrive("A1");

        long stay = DispatchService.STAY_BASE_MS + 3 * DispatchService.STAY_PER_SEVERITY_MS;
        assertEquals(stay / 1000, svc.state().admissions().get(0).secondsLeft());
        now[0] += stay - 1;
        assertEquals(0, svc.state().hospitals().get(0).freeBeds());  // one ms early: still in bed
        now[0] += 1;
        StateView s = svc.state();
        assertEquals(1, s.hospitals().get(0).freeBeds());              // discharged: bed is back
        assertTrue(s.admissions().isEmpty());
    }

    @Test
    void statsCountCallsAndOnlyAppliedDispatches() {
        svc.load(new Scenario(List.of(hospital("H1", 5, Speciality.GENERAL)), List.of(ambulance("A1", 0), ambulance("A2", 0)), List.of(),
                List.of(call(3, Speciality.GENERAL, 0.01), call(2, Speciality.GENERAL, 0.01))));
        assertEquals(2, svc.state().stats().callsReceived());
        svc.dispatch(new DispatchRequest(5.0, 5.0, Strategy.MAXFLOW, false)); // preview: not counted
        assertEquals(0, svc.state().stats().patientsHelped());
        svc.dispatch(req(Strategy.MAXFLOW));
        Stats s = svc.state().stats();
        assertEquals(2, s.patientsHelped());
        assertEquals(2.1, s.avgResponseMin(), 0.2); // ~1 km at 30 km/h
    }

    @Test
    void roadDistancesDriveRangeChecksAndEtas() {
        // Fake router: every road is 3x the straight line (winding streets).
        RoadRouter roads = new RoadRouter(JsonMapper.builder().build(), "unused") {
            @Override public boolean prefetch(List<double[]> f, List<double[]> t) { return true; }
            @Override public Double cached(double[] a, double[] b) { return 3 * com.dispatch.algo.Geo.km(a[0], a[1], b[0], b[1]); }
        };
        DispatchService s = new DispatchService(JsonMapper.builder().build(), roads);
        s.load(new Scenario(List.of(hospital("H1", 5, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(4, Speciality.GENERAL, 0.01)))); // ~1.05 km by air, ~3.15 km by road

        DispatchResult wide = s.dispatch(new DispatchRequest(5.0, 5.0, Strategy.MAXFLOW, false));
        assertTrue(wide.roadDistances());
        assertEquals(3.2, wide.assignments().get(0).pickupKm(), 0.1);       // road km, not air km
        assertEquals(6.3, wide.assignments().get(0).etaToPatientMin(), 0.2); // 3.15 km at 30 km/h

        DispatchResult tight = s.dispatch(new DispatchRequest(2.0, 5.0, Strategy.MAXFLOW, false));
        assertEquals(0, tight.served()); // within 2 km by air, but not by road
        assertTrue(tight.unserved().get(0).reason().contains("No free ambulance within 2 km"));
    }

    @Test
    void unreachableRouterFallsBackToStraightLine() {
        RoadRouter offline = new RoadRouter(JsonMapper.builder().build(), "http://127.0.0.1:1/table/v1/driving/");
        DispatchService s = new DispatchService(JsonMapper.builder().build(), offline);
        s.load(new Scenario(List.of(hospital("H1", 5, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(),
                List.of(call(4, Speciality.GENERAL, 0.01))));
        DispatchResult r = s.dispatch(new DispatchRequest(5.0, 5.0, Strategy.MAXFLOW, false));
        assertFalse(r.roadDistances());
        assertEquals(1, r.served());
        assertEquals(1.1, r.assignments().get(0).pickupKm(), 0.1); // straight-line km

        long t = System.currentTimeMillis();
        s.dispatch(new DispatchRequest(5.0, 5.0, Strategy.MAXFLOW, false));
        assertTrue(System.currentTimeMillis() - t < 500, "router should not be retried right after a failure");
    }

    @Test
    void emptyQueueDispatchIsHarmless() { // boundary
        svc.load(new Scenario(List.of(hospital("H1", 1, Speciality.GENERAL)), List.of(ambulance("A1", 0)), List.of(), List.of()));
        DispatchResult r = svc.dispatch(req(Strategy.MAXFLOW));
        assertEquals(0, r.served());
        assertEquals(0, r.unservedCount());
    }
}
