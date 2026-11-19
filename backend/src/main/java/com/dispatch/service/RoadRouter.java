package com.dispatch.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Road distances (km) between points, from the OSRM "table" service (OpenStreetMap road network).
 * Results are cached per point pair, so repeated previews cost nothing.
 *
 * shortcut: public OSRM demo server (fair-use, needs internet, no live traffic); for production run your own OSRM.
 * If it can't be reached, callers fall back to straight-line distance and the router rests for a minute.
 */
@Service
public class RoadRouter {

    static final String DEFAULT_BASE = "https://router.project-osrm.org/table/v1/driving/";
    private static final int MAX_PER_SIDE = 50; // demo server allows ~100 coordinates per table request
    private static final long RETRY_AFTER_MS = 60_000;

    private final ObjectMapper mapper;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Double> km = new ConcurrentHashMap<>();
    private volatile long downUntil;
    private volatile long shapesDownUntil;   // route API backs off separately (and shorter) from the table API
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RoadRouter.class);

    @Autowired
    public RoadRouter(ObjectMapper mapper, @org.springframework.beans.factory.annotation.Value("${osrm.url:" + DEFAULT_BASE + "}") String base) {
        this.mapper = mapper;
        this.base = base.endsWith("/") ? base : base + "/";
    }

    public RoadRouter(ObjectMapper mapper) {
        this(mapper, DEFAULT_BASE);
    }


    // ---- road geometry (OSRM route service) for riders to drive along; cached per ~110 m endpoint cells.
    private final Map<String, double[][]> shapes = new ConcurrentHashMap<>();
    private final Map<String, double[][]> shapeQueue = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<String> queueOrder = new java.util.concurrent.ConcurrentLinkedDeque<>(); // arrival order
    private final java.util.Set<String> urgent = ConcurrentHashMap.newKeySet();      // a rider is waiting for these
    private final Map<String, double[][]> fresh = new ConcurrentHashMap<>();          // fetched, not yet saved

    public boolean down() { return System.currentTimeMillis() < shapesDownUntil; }

    /** Queues a shape and moves it to the front of the line (a rider is waiting at its start). */
    public void urgentShape(double[] a, double[] b) {
        String k = cell(a) + ">" + cell(b);
        if (shapes.containsKey(k)) return;
        if (shapeQueue.putIfAbsent(k, new double[][]{a, b}) == null) queueOrder.add(k);
        urgent.add(k);
    }

    /** Shapes fetched since the last call, for persisting. */
    public Map<String, double[][]> drainFreshShapes() {
        Map<String, double[][]> out = new java.util.HashMap<>();
        for (String k : fresh.keySet()) { double[][] v = fresh.remove(k); if (v != null) out.put(k, v); }
        return out;
    }

    public void preloadShapes(Map<String, double[][]> m) { shapes.putAll(m); }

    private static String cell(double[] p) {
        return Math.round(p[0] / 0.001) + ":" + Math.round(p[1] / 0.001);
    }

    /** Road polyline [lat, lng][] from a to b if already fetched; otherwise queues it and returns null. */
    public double[][] shape(double[] a, double[] b) {
        String k = cell(a) + ">" + cell(b);
        double[][] s = shapes.get(k);
        if (s == null && shapeQueue.size() < 5000 && shapeQueue.putIfAbsent(k, new double[][]{a, b}) == null) queueOrder.add(k);
        return s;
    }

    /** Cached shape only; never queues. */
    public double[][] peekShape(double[] a, double[] b) {
        return shapes.get(cell(a) + ">" + cell(b));
    }

    public int shapesQueued() { return shapeQueue.size(); }

    /**
     * Fetches up to `max` queued shapes in parallel: shapes a rider is waiting for first, then the newest prefetches
     * (the soonest needed; old ones may belong to orders already delivered). Points closer than ~30 m are thinned out.
     */
    public int fetchShapes(int max) {
        if (System.currentTimeMillis() < shapesDownUntil) return 0;
        java.util.LinkedHashMap<String, double[][]> order = new java.util.LinkedHashMap<>();
        for (String k : urgent) { double[][] v = shapeQueue.get(k); if (v != null) order.put(k, v); else urgent.remove(k); }
        for (var it = queueOrder.descendingIterator(); it.hasNext() && order.size() < max; ) {
            String k = it.next();
            double[][] v = shapeQueue.get(k);
            if (v == null) it.remove(); else order.putIfAbsent(k, v);
        }
        java.util.Map<String, java.util.concurrent.CompletableFuture<HttpResponse<String>>> calls = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, double[][]> e : order.entrySet()) {
            if (calls.size() >= max) break;
            double[] a = e.getValue()[0], b = e.getValue()[1];
            String url = base.replace("/table/", "/route/") + a[1] + "," + a[0] + ";" + b[1] + "," + b[0] + "?overview=full&geometries=geojson";
            calls.put(e.getKey(), http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
        }
        int done = 0;
        for (Map.Entry<String, java.util.concurrent.CompletableFuture<HttpResponse<String>>> c : calls.entrySet()) {
            String key = c.getKey();
            double[] a = order.get(key)[0], b = order.get(key)[1];
            try {
                HttpResponse<String> res = c.getValue().get();
                if (res.statusCode() != 200) throw new IllegalStateException("OSRM route " + res.statusCode());
                JsonNode routes = mapper.readTree(res.body()).path("routes");
                double[][] shape;
                if (routes.isEmpty()) {
                    shape = new double[][]{a, b}; // no road found: straight
                } else {
                    java.util.List<double[]> pts = new java.util.ArrayList<>();
                    for (JsonNode p0 : routes.get(0).get("geometry").get("coordinates")) {
                        double[] p = {p0.get(1).asDouble(), p0.get(0).asDouble()};
                        if (pts.isEmpty() || com.dispatch.algo.Geo.km(pts.get(pts.size() - 1)[0], pts.get(pts.size() - 1)[1], p[0], p[1]) > 0.03) pts.add(p);
                    }
                    shape = pts.toArray(new double[0][]);
                }
                shapes.put(key, shape);
                fresh.put(key, shape);
                shapeQueue.remove(key);
                queueOrder.remove(key); // O(n) on a deque of at most ~5000 keys, 4 times a second
                urgent.remove(key);
                done++;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                shapesDownUntil = System.currentTimeMillis() + 15_000; // stays queued; try again after the pause
                log.warn("OSRM route fetch failed ({}); pausing road shapes for 15 s", ex.getMessage());
            }
        }
        return done;
    }

    /**
     * Nearest road point for each input (up to 99 per call), from the waypoints of one OSRM table request.
     * Returns [lat, lng, metres moved] per point, or null if OSRM could not be reached.
     */
    public List<double[]> snapToRoads(List<double[]> pts) {
        if (pts.isEmpty() || pts.size() > 99 || System.currentTimeMillis() < downUntil) return null;
        String coords = pts.stream().map(p -> p[1] + "," + p[0]).collect(Collectors.joining(";"));
        String dests = IntStream.range(0, pts.size()).mapToObj(String::valueOf).collect(Collectors.joining(";"));
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + coords + "?sources=0&destinations=" + dests))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode w = res.statusCode() == 200 ? mapper.readTree(res.body()).get("destinations") : null;
            if (w == null || w.size() != pts.size()) throw new IllegalStateException("bad OSRM snap response " + res.statusCode());
            List<double[]> out = new java.util.ArrayList<>();
            for (JsonNode d : w) out.add(new double[]{d.get("location").get(1).asDouble(), d.get("location").get(0).asDouble(), d.path("distance").asDouble()});
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("OSRM snap failed: {}", e.getMessage());
            return null;
        }
    }

    /** Cached road km from one point to another, or null if not known. Infinity = no road connects them. */
    public Double cached(double[] from, double[] to) {
        return km.get(key(from, to));
    }

    /** Makes sure road distances for every from × to pair are cached. Returns false if any could not be fetched. */
    public boolean prefetch(List<double[]> froms, List<double[]> tos) {
        boolean ok = true;
        for (int i = 0; i < froms.size(); i += MAX_PER_SIDE) {
            for (int j = 0; j < tos.size(); j += MAX_PER_SIDE) {
                List<double[]> f = froms.subList(i, Math.min(i + MAX_PER_SIDE, froms.size()));
                List<double[]> t = tos.subList(j, Math.min(j + MAX_PER_SIDE, tos.size()));
                boolean allKnown = f.stream().allMatch(a -> t.stream().allMatch(b -> km.containsKey(key(a, b))));
                if (!allKnown) ok &= fetch(f, t);
            }
        }
        return ok;
    }

    private boolean fetch(List<double[]> f, List<double[]> t) {
        if (System.currentTimeMillis() < downUntil) return false;
        String coords = Stream.concat(f.stream(), t.stream())
                .map(p -> p[1] + "," + p[0]).collect(Collectors.joining(";")); // OSRM wants lng,lat
        String sources = IntStream.range(0, f.size()).mapToObj(String::valueOf).collect(Collectors.joining(";"));
        String dests = IntStream.range(f.size(), f.size() + t.size()).mapToObj(String::valueOf).collect(Collectors.joining(";"));
        URI uri = URI.create(base + coords + "?sources=" + sources + "&destinations=" + dests + "&annotations=distance");
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode d = res.statusCode() == 200 ? mapper.readTree(res.body()).get("distances") : null;
            if (d == null || d.size() != f.size()) throw new IllegalStateException("bad OSRM response " + res.statusCode());
            for (int i = 0; i < f.size(); i++) {
                for (int j = 0; j < t.size(); j++) {
                    JsonNode v = d.get(i).get(j);
                    km.put(key(f.get(i), t.get(j)), v == null || v.isNull() ? Double.POSITIVE_INFINITY : v.asDouble() / 1000.0);
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            downUntil = System.currentTimeMillis() + RETRY_AFTER_MS;
            return false;
        }
    }

    private static String key(double[] a, double[] b) {
        return a[0] + "," + a[1] + ">" + b[0] + "," + b[1];
    }
}
