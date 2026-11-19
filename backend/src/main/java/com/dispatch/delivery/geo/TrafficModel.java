package com.dispatch.delivery.geo;

import com.dispatch.algo.Geo;
import com.dispatch.delivery.model.Live.Disruption;
import com.dispatch.delivery.model.Live.Scenario;
import com.dispatch.service.RoadRouter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Simulated traffic: two-wheeler speed by hour of day, scenario, and active disruptions; road distances from OSRM.
 *
 * Road distance: both points are snapped to a ~220 m grid and the OSRM road/straight ratio between the two cell
 * centres is applied to the real straight-line distance. Cell pairs are cached forever (the road network does not
 * change), so after warm-up almost every lookup is a cache hit. Unknown pairs fall back to straight-line x 1.35 and are
 * queued; fetchPending() (run on its own thread, at most one OSRM table request per second) fills them in.
 */
@Component
public class TrafficModel {

    public static final double DEFAULT_DETOUR = 1.35;
    static final double GRID = 0.001;          // ~110 m: finer cells, more pairs to fetch (still 1 OSRM request/s)
    private static final double BASE_KMH = 24.0;
    private static final int MAX_PENDING = 50_000;

    private final RoadRouter router;           // null = straight-line x 1.35 only (tests, planner, benchmarks)
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private volatile long lastFetchMs;
    private volatile Scenario scenario = Scenario.NORMAL;
    private final List<Disruption> disruptions = new CopyOnWriteArrayList<>();

    public TrafficModel() {
        this(null);
    }

    @Autowired
    public TrafficModel(RoadRouter router) {
        this.router = router;
    }

    private RoadGraph graph; // local road network; null in plain unit tests

    @Autowired(required = false)
    public void setRoadGraph(RoadGraph graph) { this.graph = graph; }

    public RoadGraph roadGraph() { return graph; }

    public Scenario scenario() { return scenario; }
    public void setScenario(Scenario s) { scenario = s; }
    public List<Disruption> disruptions() { return disruptions; }
    public void addDisruption(Disruption d) { disruptions.add(d); }

    public void expire(LocalDateTime now) {
        disruptions.removeIf(d -> d.endsAt() != null && !d.endsAt().isAfter(now));
    }

    // ------------------------------------------------------------------ speed

    /** Speed multiplier from time of day + scenario only (no local disruptions). */
    public double hourFactor(LocalDateTime t) {
        int h = t.getHour();
        boolean weekend = t.getDayOfWeek().getValue() >= 6;
        double f;
        if (!weekend && ((h >= 8 && h < 11) || (h >= 17 && h < 21))) f = 0.62;      // office rush
        else if (weekend && h >= 11 && h < 22) f = 0.78;                            // weekend crowds
        else if (h >= 23 || h < 6) f = 1.25;                                        // empty roads
        else f = 0.9;
        return switch (scenario) {
            case NORMAL -> f;
            case PEAK -> f * 0.75;
            case MONSOON -> f * 0.55;
        };
    }

    /** Local factor at a point: waterlogging slows to a crawl, closures are nearly impassable. */
    public double localFactor(double lat, double lng) {
        double f = 1.0;
        for (Disruption d : disruptions) {
            if (Geo.km(lat, lng, d.lat(), d.lng()) * 1000 <= d.radiusM()) {
                f = Math.min(f, "ROAD_CLOSURE".equals(d.type()) ? 0.08 : 0.3);
            }
        }
        return f;
    }

    public boolean blocked(double lat, double lng) {
        for (Disruption d : disruptions) {
            if ("ROAD_CLOSURE".equals(d.type()) && Geo.km(lat, lng, d.lat(), d.lng()) * 1000 <= d.radiusM()) return true;
        }
        return false;
    }

    public double speedKmh(double lat, double lng, LocalDateTime t) {
        return BASE_KMH * hourFactor(t) * localFactor(lat, lng);
    }

    // ------------------------------------------------------------------ distance and time

    static double snap(double v) {
        return Math.round(v / GRID) * GRID;
    }

    /** Road km from a to b: OSRM ratio between the snapped grid cells, else straight-line x 1.35. */
    public double roadKm(double lat1, double lng1, double lat2, double lng2) {
        double straight = Geo.km(lat1, lng1, lat2, lng2);
        if (router == null || straight < 0.3) return straight * DEFAULT_DETOUR;
        double[] a = {snap(lat1), snap(lng1)}, b = {snap(lat2), snap(lng2)};
        double cells = Geo.km(a[0], a[1], b[0], b[1]);
        if (cells < 0.2) return straight * DEFAULT_DETOUR;
        Double road = router.cached(a, b);
        if (road == null) {
            if (pending.size() < MAX_PENDING) pending.add(a[0] + "," + a[1] + ">" + b[0] + "," + b[1]);
            return straight * DEFAULT_DETOUR;
        }
        if (!Double.isFinite(road)) return straight * DEFAULT_DETOUR * 1.5; // OSRM found no road: long way round
        return straight * Math.max(1.0, Math.min(3.0, road / cells));
    }

    /** Road km / straight km for a leg (>= 1). */
    public double detour(double lat1, double lng1, double lat2, double lng2) {
        double straight = Geo.km(lat1, lng1, lat2, lng2);
        return straight < 1e-6 ? DEFAULT_DETOUR : roadKm(lat1, lng1, lat2, lng2) / straight;
    }

    /** Minutes to ride from a to b now: road km over the slowest of start, middle and end speeds. */
    public double travelMin(double lat1, double lng1, double lat2, double lng2, LocalDateTime t) {
        double km = roadKm(lat1, lng1, lat2, lng2);
        if (km < 1e-6) return 0;
        double midLat = (lat1 + lat2) / 2, midLng = (lng1 + lng2) / 2;
        double local = Math.min(localFactor(lat1, lng1), Math.min(localFactor(midLat, midLng), localFactor(lat2, lng2)));
        return km / (BASE_KMH * hourFactor(t) * local) * 60.0;
    }

    /**
     * One OSRM table request for up to 25 origins x 50 destinations from the queue, at most once per second.
     * Thread-safe; called from a background thread so a slow router never stalls the simulation.
     */
    public int fetchPending() {
        if (router == null || pending.isEmpty() || System.currentTimeMillis() - lastFetchMs < 1000) return 0;
        lastFetchMs = System.currentTimeMillis();
        LinkedHashSet<String> froms = new LinkedHashSet<>(), tos = new LinkedHashSet<>();
        List<String> taken = new ArrayList<>();
        for (String k : pending) {
            String[] p = k.split(">");
            if (!froms.contains(p[0]) && froms.size() >= 25) continue;
            if (!tos.contains(p[1]) && tos.size() >= 50) continue;
            froms.add(p[0]);
            tos.add(p[1]);
            taken.add(k);
            if (froms.size() >= 25 && tos.size() >= 50) break;
        }
        if (router.prefetch(parse(froms), parse(tos))) taken.forEach(pending::remove);
        return taken.size();
    }

    public int pendingRoads() { return pending.size() + (router == null ? 0 : router.shapesQueued()); }

    /**
     * Road polyline from a to b with the exact endpoints, or null if not fetched yet (it is queued).
     * Riders drive along it; on a miss they drive the straight line at road pace instead.
     */
    public double[][] roadShape(double lat1, double lng1, double lat2, double lng2) {
        if (Geo.km(lat1, lng1, lat2, lng2) < 0.15) return null;
        if (graph != null && graph.ready()) {                  // local OSM graph + A*: always available once loaded
            double[][] r = graph.route(lat1, lng1, lat2, lng2);
            if (r != null) return r;
        }
        if (router == null) return null;
        double[] a = {lat1, lng1}, b = {lat2, lng2};
        double[][] s = router.peekShape(b, a); // the way back along the same roads (one-way streets aside) saves a request
        if (s != null) {
            s = s.clone();
            Collections.reverse(Arrays.asList(s));
        } else {
            s = router.shape(a, b);
        }
        if (s == null || s.length < 2) return null;
        double[][] out = s.clone();
        out[0] = new double[]{lat1, lng1};          // cached shapes are per ~110 m cell: pin the real endpoints
        out[out.length - 1] = new double[]{lat2, lng2};
        return out;
    }

    /** Nearest road point for up to 99 points ([lat, lng, metres moved] each), or null if OSRM is unavailable. */
    public List<double[]> snapToRoads(List<double[]> pts) { return router == null ? null : router.snapToRoads(pts); }

    /** True while road shapes can be fetched (router configured and reachable). */
    public boolean shapesAvailable() { return (graph != null && graph.ready()) || (router != null && !router.down()); }

    /** A rider is waiting for this shape: fetch it before anything else. */
    public void urgentShape(double lat1, double lng1, double lat2, double lng2) {
        if (router != null) router.urgentShape(new double[]{lat1, lng1}, new double[]{lat2, lng2});
    }

    public Map<String, double[][]> drainFreshShapes() { return router == null ? Map.of() : router.drainFreshShapes(); }
    public void preloadShapes(Map<String, double[][]> m) { if (router != null) router.preloadShapes(m); }

    /** Queues the road shape of a future leg so it is ready when the rider gets there. */
    public void prefetchShape(double lat1, double lng1, double lat2, double lng2) {
        roadShape(lat1, lng1, lat2, lng2);
    }

    /** Background: a few road shapes per second, plus one distance table. */
    public void fetchShapes() {
        if (router != null) router.fetchShapes(4);
    }

    private static List<double[]> parse(Collection<String> pts) {
        List<double[]> out = new ArrayList<>(pts.size());
        for (String s : pts) {
            String[] p = s.split(",");
            out.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])});
        }
        return out;
    }

    /** Drop-off minutes at the door: lifts and security desks in high-rises take longer, worse in monsoon. */
    public double dropMin(boolean highRise) {
        double base = highRise ? 4.0 : 2.0;
        return scenario == Scenario.MONSOON && highRise ? base + 3.0 : base;
    }

    /** 0 = free flowing, 1 = gridlock; for the traffic overlay. */
    public double congestion(double lat, double lng, LocalDateTime t) {
        return Math.max(0, Math.min(1, 1 - hourFactor(t) * localFactor(lat, lng) / 1.25));
    }
}
