package com.dispatch.service;

import com.dispatch.algo.FibonacciHeap;
import com.dispatch.algo.Geo;
import com.dispatch.algo.MaxFlow;
import com.dispatch.model.Emergency;
import com.dispatch.model.Models.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * Holds the live city state in memory and runs dispatch rounds.
 *
 * Waiting calls live in a Fibonacci heap keyed by (severity, arrival). A dispatch round drains the heap in
 * priority order, splits the calls into severity tiers, and solves each tier as a max-flow problem
 * (ambulance -> patient -> hospital) on whatever resources the higher tiers left over.
 */
@Service
public class DispatchService {

    // Service area: Greater Mumbai bounding box.
    public static final double MIN_LAT = 18.88, MAX_LAT = 19.32, MIN_LNG = 72.76, MAX_LNG = 73.02;
    public static final double AMBULANCE_KMPH = 30.0; // average city speed assumption

    private final ObjectMapper mapper;

    private List<Hospital> hospitals = List.of();
    private List<Ambulance> ambulances = List.of();
    private List<Zone> zones = List.of();
    private final Map<String, Integer> beds = new HashMap<>();
    private final Set<String> busy = new HashSet<>();
    /** ambulanceId -> the trip it is on. An ambulance is busy exactly while it has a mission. */
    private final Map<String, Assignment> missions = new LinkedHashMap<>();

    // Demo-time hospital stay: 25 s for severity 1 up to 45 s for severity 5, then the bed frees up.
    static final long STAY_BASE_MS = 20_000, STAY_PER_SEVERITY_MS = 5_000;

    private record Admission(String emergencyId, int severity, String hospitalId, String hospitalName, long dischargeAt) {}

    private final List<Admission> admissions = new ArrayList<>();
    /** Time source; tests replace it to fast-forward. */
    LongSupplier clock = System::currentTimeMillis;
    private FibonacciHeap<Emergency> queue = new FibonacciHeap<>();
    private final Map<String, FibonacciHeap.Node<Emergency>> handles = new HashMap<>();
    private long seq;
    private int nextId;
    private int callsReceived, patientsHelped;
    private double responseMinSum;

    /** Road distances; null (unit tests) = straight-line distances only. */
    private final RoadRouter router;

    public DispatchService(ObjectMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public DispatchService(ObjectMapper mapper, RoadRouter router) {
        this.mapper = mapper;
        this.router = router;
        resetToSample();
    }

    // ------------------------------------------------------------------ scenario

    public synchronized void resetToSample() {
        try (InputStream in = new ClassPathResource("data/mumbai.json").getInputStream()) {
            load(mapper.readValue(in, Scenario.class));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read sample data", e);
        }
    }

    public synchronized void load(Scenario s) {
        require(s != null, "Scenario body is required");
        require(s.hospitals() != null && !s.hospitals().isEmpty(), "Scenario must contain at least one hospital");
        require(s.ambulances() != null && !s.ambulances().isEmpty(), "Scenario must contain at least one ambulance");

        Set<String> ids = new HashSet<>();
        for (Hospital h : s.hospitals()) {
            require(h.id() != null && !h.id().isBlank(), "Every hospital needs an id");
            require(ids.add(h.id()), "Duplicate hospital id: " + h.id());
            require(h.name() != null && !h.name().isBlank(), "Hospital " + h.id() + " needs a name");
            requireInArea(h.lat(), h.lng(), "Hospital " + h.id());
            require(h.freeBeds() != null && h.freeBeds() >= 0, "Hospital " + h.id() + " must have freeBeds >= 0");
            require(h.specialities() != null && !h.specialities().isEmpty(), "Hospital " + h.id() + " needs at least one speciality");
        }
        ids.clear();
        for (Ambulance a : s.ambulances()) {
            require(a.id() != null && !a.id().isBlank(), "Every ambulance needs an id");
            require(ids.add(a.id()), "Duplicate ambulance id: " + a.id());
            requireInArea(a.lat(), a.lng(), "Ambulance " + a.id());
        }
        List<Zone> z = s.zones() == null ? List.of() : s.zones();
        ids.clear();
        for (Zone zone : z) {
            require(zone.name() != null && !zone.name().isBlank(), "Every zone needs a name");
            require(ids.add(zone.name()), "Duplicate zone: " + zone.name());
            requireInArea(zone.lat(), zone.lng(), "Zone " + zone.name());
            require(zone.callsPerDay() != null && zone.callsPerDay() > 0 && zone.callsPerDay() <= 500,
                    "Zone " + zone.name() + " callsPerDay must be in (0, 500]");
        }
        if (s.emergencies() != null) s.emergencies().forEach(this::validateNewEmergency);

        // Everything validated: swap state atomically.
        hospitals = List.copyOf(s.hospitals());
        ambulances = List.copyOf(s.ambulances());
        zones = List.copyOf(z);
        beds.clear();
        hospitals.forEach(h -> beds.put(h.id(), h.freeBeds()));
        busy.clear();
        missions.clear();
        admissions.clear();
        queue = new FibonacciHeap<>();
        handles.clear();
        seq = 0;
        nextId = 0;
        callsReceived = patientsHelped = 0;
        responseMinSum = 0;
        if (s.emergencies() != null) s.emergencies().forEach(this::addEmergency);
    }

    public synchronized List<Zone> zones() { return zones; }

    public synchronized StateView state() {
        dischargeDue();
        List<Hospital> hv = hospitals.stream()
                .map(h -> new Hospital(h.id(), h.name(), h.lat(), h.lng(), beds.get(h.id()), h.specialities()))
                .toList();
        List<AmbulanceView> av = ambulances.stream()
                .map(a -> new AmbulanceView(a.id(), a.lat(), a.lng(), busy.contains(a.id())))
                .toList();
        // Display only: sort a copy by heap key so the UI shows the order dispatch will use.
        List<Emergency> waiting = handles.values().stream().map(FibonacciHeap.Node::value)
                .sorted(Comparator.comparingLong(Emergency::priorityKey)).toList();
        List<EmergencyView> qv = new ArrayList<>();
        for (int i = 0; i < waiting.size(); i++) {
            Emergency e = waiting.get(i);
            qv.add(new EmergencyView(e.id, e.lat, e.lng, e.severity, e.type, i + 1));
        }
        long now = clock.getAsLong();
        List<AdmissionView> adm = admissions.stream()
                .map(a -> new AdmissionView(a.emergencyId(), a.severity(), a.hospitalId(), a.hospitalName(),
                        Math.max(0, (a.dischargeAt() - now + 999) / 1000)))
                .toList();
        Stats stats = new Stats(callsReceived, patientsHelped,
                patientsHelped == 0 ? 0 : round1(responseMinSum / patientsHelped));
        return new StateView(hv, av, qv, zones, List.copyOf(missions.values()), adm, stats);
    }

    /** Patients whose stay is over go home: their beds become free again. */
    private void dischargeDue() {
        long now = clock.getAsLong();
        admissions.removeIf(a -> {
            if (a.dischargeAt() > now) return false;
            beds.merge(a.hospitalId(), 1, Integer::sum);
            return true;
        });
    }

    /**
     * The ambulance has delivered its patient: it is now parked at that hospital and free again.
     * The hospital bed stays occupied (the patient was admitted).
     */
    public synchronized AmbulanceView arrive(String ambulanceId) {
        Assignment m = missions.remove(ambulanceId);
        if (m == null) throw new NoSuchElementException("Ambulance " + ambulanceId + " is not on a mission");
        busy.remove(ambulanceId);
        // Patient admitted: the bed (taken at dispatch) stays taken until the stay ends.
        admissions.add(new Admission(m.emergencyId(), m.severity(), m.hospitalId(), m.hospitalName(),
                clock.getAsLong() + STAY_BASE_MS + m.severity() * STAY_PER_SEVERITY_MS));
        List<Ambulance> updated = new ArrayList<>(ambulances.size());
        for (Ambulance a : ambulances) {
            updated.add(a.id().equals(ambulanceId) ? new Ambulance(a.id(), m.hospitalLat(), m.hospitalLng()) : a);
        }
        ambulances = List.copyOf(updated);
        return new AmbulanceView(ambulanceId, m.hospitalLat(), m.hospitalLng(), false);
    }

    // ------------------------------------------------------------------ queue operations

    public synchronized EmergencyView addEmergency(NewEmergency in) {
        validateNewEmergency(in);
        Emergency e = new Emergency("E" + (++nextId), in.lat(), in.lng(), in.severity(), in.type(), ++seq);
        callsReceived++;
        handles.put(e.id, queue.insert(e.priorityKey(), e));
        return new EmergencyView(e.id, e.lat, e.lng, e.severity, e.type, 0);
    }

    /** Patient got worse: raise severity, which lowers the heap key -> Fibonacci heap decreaseKey, O(1) amortized. */
    public synchronized EmergencyView escalate(String id, Integer newSeverity) {
        FibonacciHeap.Node<Emergency> node = handles.get(id);
        if (node == null) throw new NoSuchElementException("No waiting emergency with id " + id);
        Emergency e = node.value();
        require(newSeverity != null && newSeverity >= 1 && newSeverity <= 5, "Severity must be between 1 and 5");
        require(newSeverity > e.severity, "Escalation must raise severity (currently " + e.severity + ")");
        e.severity = newSeverity;
        queue.decreaseKey(node, e.priorityKey());
        return new EmergencyView(e.id, e.lat, e.lng, e.severity, e.type, 0);
    }

    public synchronized int simulateCalls(SimulateRequest r) {
        int count = r == null || r.count() == null ? 10 : r.count();
        require(count >= 1 && count <= 500, "count must be between 1 and 500");
        Random rnd = new Random(r == null || r.seed() == null ? System.nanoTime() : r.seed());
        Speciality[] types = Speciality.values();
        for (int i = 0; i < count; i++) {
            double lat, lng;
            if (zones.isEmpty()) {
                lat = MIN_LAT + rnd.nextDouble() * (MAX_LAT - MIN_LAT);
                lng = MIN_LNG + rnd.nextDouble() * (MAX_LNG - MIN_LNG);
            } else {
                Zone z = pickZoneWeighted(rnd);
                lat = clamp(z.lat() + (rnd.nextDouble() - 0.5) * 0.04, MIN_LAT, MAX_LAT);
                lng = clamp(z.lng() + (rnd.nextDouble() - 0.5) * 0.04, MIN_LNG, MAX_LNG);
            }
            // Skewed toward less severe calls, like real call mixes.
            int severity = 1 + (int) Math.floor(5 * Math.pow(rnd.nextDouble(), 1.6));
            addEmergency(new NewEmergency(lat, lng, severity, types[rnd.nextInt(types.length)]));
        }
        return count;
    }

    // ------------------------------------------------------------------ dispatch

    public synchronized DispatchResult dispatch(DispatchRequest r) {
        require(r != null, "Request body is required");
        double ambR = r.ambulanceRadiusKm() == null ? 8 : r.ambulanceRadiusKm();
        double hospR = r.hospitalRadiusKm() == null ? 10 : r.hospitalRadiusKm();
        require(ambR > 0 && ambR <= 50, "ambulanceRadiusKm must be in (0, 50]");
        require(hospR > 0 && hospR <= 50, "hospitalRadiusKm must be in (0, 50]");
        Strategy strategy = r.strategy() == null ? Strategy.MAXFLOW : r.strategy();
        boolean apply = Boolean.TRUE.equals(r.apply());
        dischargeDue();

        // Drain the heap: extractMin gives calls in exact priority order.
        List<Emergency> ordered = new ArrayList<>(queue.size());
        while (!queue.isEmpty()) ordered.add(queue.extractMin().value());
        handles.clear();

        Set<String> usedAmb = new HashSet<>(busy);
        Map<String, Integer> bedsLeft = new HashMap<>(beds);
        List<Match> matches = new ArrayList<>();
        List<Emergency> notServed = new ArrayList<>();
        Set<String> bottlenecks = new LinkedHashSet<>();
        int paths = 0, moves = 0;
        boolean roads = prefetchRoads(ordered, usedAmb, ambR, hospR); // network time stays out of the runtime below

        long t0 = System.nanoTime();
        int i = 0;
        while (i < ordered.size()) {
            int j = i;
            while (j < ordered.size() && ordered.get(j).severity == ordered.get(i).severity) j++;
            List<Emergency> tier = ordered.subList(i, j);
            if (strategy == Strategy.MAXFLOW) {
                paths += matchTierMaxFlow(tier, ambR, hospR, usedAmb, bedsLeft, matches, notServed, bottlenecks);
            } else {
                matchTierGreedy(tier, ambR, hospR, usedAmb, bedsLeft, matches, notServed);
            }
            i = j;
        }
        if (strategy == Strategy.MAXFLOW) moves = shortenTrips(matches, usedAmb, bedsLeft, ambR, hospR);
        long micros = (System.nanoTime() - t0) / 1000;
        List<Assignment> assignments = matches.stream().map(m -> assignment(m.e, m.a, m.h)).toList();

        List<Unserved> unserved = notServed.stream()
                .map(e -> new Unserved(e.id, e.severity, e.type, reason(e, ambR, hospR))).toList();

        // Put calls back: everything on a dry run, only unserved ones when applying.
        Set<String> servedIds = new HashSet<>();
        if (apply) {
            for (Assignment a : assignments) {
                busy.add(a.ambulanceId());
                missions.put(a.ambulanceId(), a);
                servedIds.add(a.emergencyId());
                patientsHelped++;
                responseMinSum += a.etaToPatientMin();
            }
            beds.putAll(bedsLeft);
        }
        for (Emergency e : ordered) {
            if (!servedIds.contains(e.id)) handles.put(e.id, queue.insert(e.priorityKey(), e));
        }

        double avgEta = assignments.stream().mapToDouble(Assignment::etaToPatientMin).average().orElse(0);
        return new DispatchResult(strategy, apply, assignments.size(), unserved.size(), round1(avgEta), micros,
                paths, moves, roads, List.copyOf(bottlenecks), assignments, unserved);
    }

    /** One served call: mutable so the local search can swap its ambulance or hospital. */
    static final class Match {
        final Emergency e;
        Ambulance a;
        Hospital h;

        Match(Emergency e, Ambulance a, Hospital h) {
            this.e = e;
            this.a = a;
            this.h = h;
        }
    }

    /**
     * Phase 2 after max-flow: local search that shortens trips without changing WHO is served.
     * Moves: take a closer still-free ambulance / hospital bed, or swap ambulances or hospitals between two calls.
     * Every move strictly lowers total km, so it terminates; it never drops a call, so the max-flow count and
     * the severity-tier guarantee are preserved. It is a heuristic: total distance is not guaranteed minimal.
     */
    int shortenTrips(List<Match> ms, Set<String> usedAmb, Map<String, Integer> bedsLeft, double ambR, double hospR) {
        final double eps = 1e-9;
        int moves = 0;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Match m : ms) {
                for (Ambulance a : ambulances) {
                    if (!usedAmb.contains(a.id()) && dist(a, m.e) < dist(m.a, m.e) - eps) {
                        usedAmb.remove(m.a.id());
                        usedAmb.add(a.id());
                        m.a = a;
                        changed = true;
                        moves++;
                    }
                }
                for (Hospital h : hospitals) {
                    if (bedsLeft.get(h.id()) > 0 && supports(h, m.e.type) && dist(h, m.e) < dist(m.h, m.e) - eps) {
                        bedsLeft.merge(m.h.id(), 1, Integer::sum);
                        bedsLeft.merge(h.id(), -1, Integer::sum);
                        m.h = h;
                        changed = true;
                        moves++;
                    }
                }
            }
            for (int x = 0; x < ms.size(); x++) {
                for (int y = x + 1; y < ms.size(); y++) {
                    Match p = ms.get(x), q = ms.get(y);
                    if (dist(q.a, p.e) <= ambR && dist(p.a, q.e) <= ambR
                            && dist(q.a, p.e) + dist(p.a, q.e) < dist(p.a, p.e) + dist(q.a, q.e) - eps) {
                        Ambulance t = p.a; p.a = q.a; q.a = t;
                        changed = true;
                        moves++;
                    }
                    if (supports(q.h, p.e.type) && supports(p.h, q.e.type)
                            && dist(q.h, p.e) <= hospR && dist(p.h, q.e) <= hospR
                            && dist(q.h, p.e) + dist(p.h, q.e) < dist(p.h, p.e) + dist(q.h, q.e) - eps) {
                        Hospital t = p.h; p.h = q.h; q.h = t;
                        changed = true;
                        moves++;
                    }
                }
            }
        }
        return moves;
    }

    /**
     * Network for one severity tier:
     *   S -> ambulance (cap 1) -> patientIn (cap 1, if within ambulance radius)
     *   patientIn -> patientOut (cap 1: one ambulance + one bed per patient)
     *   patientOut -> hospital (cap 1, if speciality offered and within radius) -> T (cap = free beds)
     * Max flow = max number of patients in this tier who get both an ambulance and a bed.
     */
    int matchTierMaxFlow(List<Emergency> tier, double ambR, double hospR, Set<String> usedAmb,
                         Map<String, Integer> bedsLeft, List<Match> out, List<Emergency> notServed,
                         Set<String> bottlenecks) {
        List<Ambulance> free = ambulances.stream().filter(a -> !usedAmb.contains(a.id())).toList();
        int A = free.size(), P = tier.size(), H = hospitals.size();
        int S = 0, aBase = 1, pIn = aBase + A, pOut = pIn + P, hBase = pOut + P, T = hBase + H;
        MaxFlow mf = new MaxFlow(T + 1);

        for (int a = 0; a < A; a++) mf.addEdge(S, aBase + a, 1);
        // Per patient: the candidate edges and which ambulance/hospital index each one leads to.
        List<List<MaxFlow.Edge>> pickE = new ArrayList<>(), dropE = new ArrayList<>();
        List<List<Integer>> pickIdx = new ArrayList<>(), dropIdx = new ArrayList<>();
        for (int p = 0; p < P; p++) {
            Emergency e = tier.get(p);
            List<MaxFlow.Edge> pe = new ArrayList<>(), de = new ArrayList<>();
            List<Integer> pi = new ArrayList<>(), di = new ArrayList<>();
            for (int a = 0; a < A; a++) {
                if (dist(free.get(a), e) <= ambR) { pe.add(mf.addEdge(aBase + a, pIn + p, 1)); pi.add(a); }
            }
            mf.addEdge(pIn + p, pOut + p, 1);
            for (int h = 0; h < H; h++) {
                Hospital hs = hospitals.get(h);
                if (supports(hs, e.type) && dist(hs, e) <= hospR) { de.add(mf.addEdge(pOut + p, hBase + h, 1)); di.add(h); }
            }
            pickE.add(pe); pickIdx.add(pi); dropE.add(de); dropIdx.add(di);
        }
        for (int h = 0; h < H; h++) mf.addEdge(hBase + h, T, bedsLeft.get(hospitals.get(h).id()));

        int flow = mf.maxFlow(S, T);

        for (int p = 0; p < P; p++) {
            Emergency e = tier.get(p);
            Ambulance amb = null;
            Hospital hos = null;
            for (int k = 0; k < pickE.get(p).size(); k++) if (pickE.get(p).get(k).flow() == 1) amb = free.get(pickIdx.get(p).get(k));
            for (int k = 0; k < dropE.get(p).size(); k++) if (dropE.get(p).get(k).flow() == 1) hos = hospitals.get(dropIdx.get(p).get(k));
            if (amb != null && hos != null) {
                usedAmb.add(amb.id());
                bedsLeft.merge(hos.id(), -1, Integer::sum);
                out.add(new Match(e, amb, hos));
            } else {
                notServed.add(e);
            }
        }

        // Min cut explains what limited this tier (only interesting if someone was left out).
        if (flow < P) {
            boolean[] side = mf.minCutSourceSide(S);
            int ambulanceCut = 0;
            for (int a = 0; a < A; a++) if (!side[aBase + a]) ambulanceCut++;
            if (A == 0 || ambulanceCut == A) bottlenecks.add("Ambulance supply: every free ambulance is in use");
            for (int h = 0; h < H; h++) {
                if (side[hBase + h]) bottlenecks.add("Hospital beds: " + hospitals.get(h).name() + " is full");
            }
            bottlenecks.add("Coverage: some severity-" + tier.get(0).severity + " calls had no reachable ambulance or suitable hospital left");
        }
        return mf.augmentingPaths();
    }

    /** Baseline: each call, in priority order, grabs its nearest free ambulance and nearest suitable hospital with a bed. */
    void matchTierGreedy(List<Emergency> tier, double ambR, double hospR, Set<String> usedAmb,
                         Map<String, Integer> bedsLeft, List<Match> out, List<Emergency> notServed) {
        for (Emergency e : tier) {
            Ambulance bestA = null;
            double bestAd = Double.MAX_VALUE;
            for (Ambulance a : ambulances) {
                double d = dist(a, e);
                if (!usedAmb.contains(a.id()) && d <= ambR && d < bestAd) { bestA = a; bestAd = d; }
            }
            Hospital bestH = null;
            double bestHd = Double.MAX_VALUE;
            for (Hospital h : hospitals) {
                double d = dist(h, e);
                if (bedsLeft.get(h.id()) > 0 && supports(h, e.type) && d <= hospR && d < bestHd) { bestH = h; bestHd = d; }
            }
            if (bestA != null && bestH != null) {
                usedAmb.add(bestA.id());
                bedsLeft.merge(bestH.id(), -1, Integer::sum);
                out.add(new Match(e, bestA, bestH));
            } else {
                notServed.add(e);
            }
        }
    }

    private String reason(Emergency e, double ambR, double hospR) {
        boolean anyHospital = false, anyBed = false, anyAmbulance = false;
        for (Hospital h : hospitals) {
            if (supports(h, e.type) && dist(h, e) <= hospR) {
                anyHospital = true;
                if (beds.get(h.id()) > 0) anyBed = true;
            }
        }
        for (Ambulance a : ambulances) if (!busy.contains(a.id()) && dist(a, e) <= ambR) anyAmbulance = true;
        if (!anyHospital) return "No hospital offering " + e.type + " care within " + fmt(hospR) + " km";
        if (!anyBed) return "All hospitals offering " + e.type + " care within " + fmt(hospR) + " km are full";
        if (!anyAmbulance) return "No free ambulance within " + fmt(ambR) + " km";
        return "Reachable ambulances/beds were allocated to other calls of equal or higher priority";
    }

    // ------------------------------------------------------------------ helpers

    private Assignment assignment(Emergency e, Ambulance a, Hospital h) {
        double pickup = dist(a, e), transport = dist(h, e);
        return new Assignment(e.id, e.severity, e.type, e.lat, e.lng,
                a.id(), a.lat(), a.lng(), h.id(), h.name(), h.lat(), h.lng(),
                round1(pickup), round1(transport),
                round1(pickup / AMBULANCE_KMPH * 60), round1((pickup + transport) / AMBULANCE_KMPH * 60));
    }

    static boolean supports(Hospital h, Speciality type) {
        return type == Speciality.GENERAL || h.specialities().contains(type);
    }

    /** Ambulance → patient km: by road when known, else straight-line. */
    private double dist(Ambulance a, Emergency e) {
        return road(new double[]{a.lat(), a.lng()}, new double[]{e.lat, e.lng});
    }

    /** Patient → hospital km: by road when known, else straight-line. */
    private double dist(Hospital h, Emergency e) {
        return road(new double[]{e.lat, e.lng}, new double[]{h.lat(), h.lng()});
    }

    private double road(double[] from, double[] to) {
        Double km = router == null ? null : router.cached(from, to);
        return km != null ? km : Geo.km(from[0], from[1], to[0], to[1]);
    }

    /**
     * Fetches road distances for every pair this round could use. Road distance is never shorter than straight-line,
     * so pairs already out of range by air are skipped. Returns true only if every needed road distance is known.
     */
    private boolean prefetchRoads(List<Emergency> calls, Set<String> used, double ambR, double hospR) {
        if (router == null || calls.isEmpty()) return false;
        List<double[]> patients = calls.stream().map(e -> new double[]{e.lat, e.lng}).toList();
        List<double[]> amb = ambulances.stream().filter(a -> !used.contains(a.id())
                        && calls.stream().anyMatch(e -> Geo.km(a.lat(), a.lng(), e.lat, e.lng) <= ambR))
                .map(a -> new double[]{a.lat(), a.lng()}).toList();
        List<double[]> hos = hospitals.stream().filter(h ->
                        calls.stream().anyMatch(e -> Geo.km(h.lat(), h.lng(), e.lat, e.lng) <= hospR))
                .map(h -> new double[]{h.lat(), h.lng()}).toList();
        boolean toPatients = router.prefetch(amb, patients);
        boolean toHospitals = router.prefetch(patients, hos);
        return toPatients && toHospitals;
    }

    private Zone pickZoneWeighted(Random rnd) {
        double total = zones.stream().mapToDouble(Zone::callsPerDay).sum();
        double x = rnd.nextDouble() * total;
        for (Zone z : zones) {
            x -= z.callsPerDay();
            if (x <= 0) return z;
        }
        return zones.get(zones.size() - 1);
    }

    private void validateNewEmergency(NewEmergency e) {
        require(e != null, "Emergency body is required");
        require(e.severity() != null && e.severity() >= 1 && e.severity() <= 5, "Severity must be between 1 and 5");
        require(e.type() != null, "Emergency type is required (GENERAL, CARDIAC, TRAUMA, BURNS, NEURO)");
        requireInArea(e.lat(), e.lng(), "Emergency location");
    }

    private static void requireInArea(Double lat, Double lng, String what) {
        require(lat != null && lng != null, what + " needs lat and lng");
        require(lat >= MIN_LAT && lat <= MAX_LAT && lng >= MIN_LNG && lng <= MAX_LNG,
                what + " (" + lat + ", " + lng + ") is outside the Mumbai service area");
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static String fmt(double v) { return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v); }
}
