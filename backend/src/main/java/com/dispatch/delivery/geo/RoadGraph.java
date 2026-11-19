package com.dispatch.delivery.geo;

import com.dispatch.algo.FibonacciHeap;
import com.dispatch.algo.Geo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mumbai's drivable road network (OpenStreetMap), routed locally with A* on our own Fibonacci heap.
 *
 * Riders used to get their road geometry from the public OSRM server one leg at a time; at peak (200 riders) that
 * queue fell behind and riders cut across blocks. This graph is downloaded once from Overpass in tiles, reduced to its
 * largest connected component, saved as a compact binary file and loaded from disk on later starts. After that every
 * leg is routed in-process: no network, no rate limit, always on a road.
 *
 * Graph: CSR adjacency (undirected, two-wheelers; one-way rules ignored), edge weight = metres.
 * Search: A* with the straight-line distance as heuristic (admissible: no road is shorter than the straight line).
 */
@Component
public class RoadGraph {

    private static final Logger log = LoggerFactory.getLogger(RoadGraph.class);
    private static final int MAGIC = 0x524F4144; // "ROAD"
    private static final double CELL = 0.005;     // ~550 m nearest-node grid
    // Lat/lng box of Greater Mumbai (BMC), downloaded as 3 x 3 tiles so each Overpass request stays small.
    private static final double S = 18.88, N = 19.29, W = 72.77, E = 73.00;
    private static final String HIGHWAYS = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street)(_link)?$";

    private final ObjectMapper mapper;
    private final Path file;
    private final String overpass;
    private volatile Graph g;
    private final Map<String, double[][]> routes = new ConcurrentHashMap<>(); // per ~110 m cell pair

    record Graph(float[] lat, float[] lng, int[] off, int[] adj, float[] w, Map<Long, int[]> grid) {}

    public RoadGraph(ObjectMapper mapper, @Value("${roads.file:data/mumbai-roads.bin}") String file,
                     @Value("${roads.overpass-url:https://overpass-api.de/api/interpreter}") String overpass) {
        this.mapper = mapper;
        this.file = Path.of(file);
        this.overpass = overpass;
    }

    public boolean ready() { return g != null; }

    public int nodes() { return g == null ? 0 : g.lat.length; }

    /** Loads the saved graph, or downloads and builds it; runs on its own thread so startup is not delayed. */
    public void loadAsync() {
        if (g != null) return;
        Thread t = new Thread(() -> {
            try {
                if (Files.exists(file)) {
                    long t0 = System.currentTimeMillis();
                    g = read(file);
                    log.info("Road graph loaded from {}: {} junctions/bends, {} road segments in {} ms", file, g.lat.length, g.adj.length / 2, System.currentTimeMillis() - t0);
                } else if (overpass != null && !overpass.isBlank()) {
                    log.info("Downloading Mumbai's road network from OpenStreetMap (one time, ~1-3 min)...");
                    Graph built = download();
                    write(built, file);
                    g = built;
                    log.info("Road graph built: {} nodes, {} segments, saved to {}", built.lat.length, built.adj.length / 2, file);
                }
            } catch (Exception e) {
                log.warn("Road graph unavailable ({}); riders use OSRM road shapes instead", e.toString());
            }
        }, "road-graph");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ routing

    /** Road polyline [lat, lng][] from a to b with the exact endpoints, or null if the graph is not loaded. */
    public double[][] route(double lat1, double lng1, double lat2, double lng2) {
        Graph gr = g;
        if (gr == null) return null;
        String key = Math.round(lat1 / 0.001) + ":" + Math.round(lng1 / 0.001) + ">" + Math.round(lat2 / 0.001) + ":" + Math.round(lng2 / 0.001);
        double[][] cached = routes.get(key);
        if (cached == null) {
            int s = nearest(gr, lat1, lng1), t = nearest(gr, lat2, lng2);
            if (s < 0 || t < 0) return null;
            cached = astar(gr, s, t);
            if (cached == null) return null;
            if (routes.size() > 50_000) routes.clear(); // shortcut: crude bound on memory; fine for a demo-sized city
            routes.put(key, cached);
        }
        double[][] out = new double[cached.length + 2][];
        out[0] = new double[]{lat1, lng1};
        System.arraycopy(cached, 0, out, 1, cached.length);
        out[out.length - 1] = new double[]{lat2, lng2};
        return out;
    }

    /** A* from node s to node t; keys are centimetres so they fit the heap's long keys. Points thinned to >= 25 m apart. */
    static double[][] astar(Graph gr, int s, int t) {
        if (s == t) return new double[][]{{gr.lat[s], gr.lng[s]}};
        FibonacciHeap<Integer> open = new FibonacciHeap<>();
        Map<Integer, Long> dist = new HashMap<>();
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, FibonacciHeap.Node<Integer>> handle = new HashMap<>();
        Set<Integer> closed = new HashSet<>();
        double tLat = gr.lat[t], tLng = gr.lng[t];
        dist.put(s, 0L);
        handle.put(s, open.insert(h(gr, s, tLat, tLng), s));
        while (!open.isEmpty()) {
            int u = open.extractMin().value();
            if (u == t) break;
            closed.add(u);
            long du = dist.get(u);
            for (int e = gr.off[u]; e < gr.off[u + 1]; e++) {
                int v = gr.adj[e];
                if (closed.contains(v)) continue;
                long dv = du + Math.round(gr.w[e] * 100);
                Long old = dist.get(v);
                if (old != null && dv >= old) continue;
                dist.put(v, dv);
                parent.put(v, u);
                long f = dv + h(gr, v, tLat, tLng);
                FibonacciHeap.Node<Integer> hn = handle.get(v);
                if (hn != null && hn.inHeap()) open.decreaseKey(hn, f); // the O(1) amortized step the heap exists for
                else handle.put(v, open.insert(f, v));
            }
        }
        if (!parent.containsKey(t)) return null;
        ArrayDeque<double[]> path = new ArrayDeque<>();
        for (int v = t; ; v = parent.get(v)) {
            double[] p = {gr.lat[v], gr.lng[v]};
            if (path.isEmpty() || v == s || Geo.km(path.peekFirst()[0], path.peekFirst()[1], p[0], p[1]) >= 0.025) path.addFirst(p);
            if (v == s) break;
        }
        return path.toArray(new double[0][]);
    }

    private static long h(Graph gr, int v, double tLat, double tLng) {
        return Math.round(Geo.km(gr.lat[v], gr.lng[v], tLat, tLng) * 100_000); // km -> cm
    }

    static long cell(double lat, double lng) {
        return ((long) Math.floor(lat / CELL)) * 1_000_000L + (long) Math.floor(lng / CELL);
    }

    static int nearest(Graph gr, double lat, double lng) {
        int best = -1;
        double bestKm = Double.MAX_VALUE;
        long cy = (long) Math.floor(lat / CELL), cx = (long) Math.floor(lng / CELL);
        for (int ring = 0; ring <= 3 && best < 0; ring++) { // widen until something is found (~1.6 km max)
            for (long y = cy - ring; y <= cy + ring; y++) {
                for (long x = cx - ring; x <= cx + ring; x++) {
                    int[] ids = gr.grid.get(y * 1_000_000L + x);
                    if (ids == null) continue;
                    for (int i : ids) {
                        double d = Geo.km(lat, lng, gr.lat[i], gr.lng[i]);
                        if (d < bestKm) { bestKm = d; best = i; }
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ build

    private Graph download() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        Map<Long, double[]> nodes = new HashMap<>();
        List<long[]> ways = new ArrayList<>();
        int tiles = 3;
        for (int i = 0; i < tiles; i++) {
            for (int j = 0; j < tiles; j++) {
                double s = S + (N - S) * i / tiles, n = S + (N - S) * (i + 1) / tiles;
                double w = W + (E - W) * j / tiles, e = W + (E - W) * (j + 1) / tiles;
                String q = String.format(Locale.ROOT, "[out:json][timeout:180];way[\"highway\"~\"%s\"](%.4f,%.4f,%.4f,%.4f);(._;>;);out skel qt;",
                        HIGHWAYS, s, w, n, e);
                JsonNode els = fetchTile(http, q, i * tiles + j + 1, tiles * tiles);
                for (JsonNode el : els) {
                    if ("node".equals(el.path("type").asString())) nodes.put(el.get("id").asLong(), new double[]{el.get("lat").asDouble(), el.get("lon").asDouble()});
                    else if ("way".equals(el.path("type").asString())) {
                        JsonNode ns = el.get("nodes");
                        long[] ids = new long[ns.size()];
                        for (int k = 0; k < ids.length; k++) ids[k] = ns.get(k).asLong();
                        ways.add(ids);
                    }
                }
            }
        }
        return build(nodes, ways);
    }

    private JsonNode fetchTile(HttpClient http, String q, int n, int of) throws Exception {
        for (int attempt = 1; ; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(overpass)).timeout(Duration.ofMinutes(4))
                    .header("User-Agent", "mumbai-delivery-ops/1.0 (student project)")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(q, StandardCharsets.UTF_8))).build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() == 200) {
                try (InputStream in = res.body()) {
                    JsonNode els = mapper.readTree(in).get("elements");
                    log.info("Road tile {}/{}: {} elements", n, of, els.size());
                    return els;
                }
            }
            res.body().close();
            if (attempt >= 4) throw new IOException("Overpass tile " + n + " failed with HTTP " + res.statusCode());
            log.info("Overpass busy (HTTP {}), retrying tile {} in {} s", res.statusCode(), n, 20 * attempt);
            Thread.sleep(20_000L * attempt);
        }
    }

    /** Ways -> undirected graph, keep only the largest connected component (islands can't reach anything). */
    static Graph build(Map<Long, double[]> nodes, List<long[]> ways) {
        Map<Long, Integer> idx = new HashMap<>();
        List<double[]> pts = new ArrayList<>();
        List<int[]> edges = new ArrayList<>();
        for (long[] w : ways) {
            for (int k = 0; k + 1 < w.length; k++) {
                double[] a = nodes.get(w[k]), b = nodes.get(w[k + 1]);
                if (a == null || b == null) continue;
                int ia = idx.computeIfAbsent(w[k], id -> { pts.add(a); return pts.size() - 1; });
                int ib = idx.computeIfAbsent(w[k + 1], id -> { pts.add(b); return pts.size() - 1; });
                if (ia != ib) edges.add(new int[]{ia, ib});
            }
        }
        int n = pts.size();
        List<List<Integer>> nb = new ArrayList<>(n);
        for (int i = 0; i < n; i++) nb.add(new ArrayList<>(3));
        for (int[] e : edges) { nb.get(e[0]).add(e[1]); nb.get(e[1]).add(e[0]); }
        // largest component by BFS
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int bestComp = -1, bestSize = 0, c = 0;
        for (int i = 0; i < n; i++) {
            if (comp[i] >= 0) continue;
            int size = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>(List.of(i));
            comp[i] = c;
            while (!q.isEmpty()) {
                int u = q.poll();
                size++;
                for (int v : nb.get(u)) if (comp[v] < 0) { comp[v] = c; q.add(v); }
            }
            if (size > bestSize) { bestSize = size; bestComp = c; }
            c++;
        }
        int[] remap = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) remap[i] = comp[i] == bestComp ? m++ : -1;
        float[] lat = new float[m], lng = new float[m];
        int[] off = new int[m + 1];
        for (int i = 0; i < n; i++) if (remap[i] >= 0) { lat[remap[i]] = (float) pts.get(i)[0]; lng[remap[i]] = (float) pts.get(i)[1]; off[remap[i] + 1] = nb.get(i).size(); }
        for (int i = 0; i < m; i++) off[i + 1] += off[i];
        int[] adj = new int[off[m]];
        int[] fill = Arrays.copyOf(off, m);
        for (int i = 0; i < n; i++) if (remap[i] >= 0) for (int v : nb.get(i)) adj[fill[remap[i]]++] = remap[v];
        return finish(lat, lng, off, adj);
    }

    /** Edge weights and the nearest-node grid, derived from coordinates + adjacency. */
    static Graph finish(float[] lat, float[] lng, int[] off, int[] adj) {
        int m = lat.length;
        float[] w = new float[adj.length];
        for (int u = 0; u < m; u++) for (int e = off[u]; e < off[u + 1]; e++) w[e] = (float) (Geo.km(lat[u], lng[u], lat[adj[e]], lng[adj[e]]) * 1000);
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int i = 0; i < m; i++) cells.computeIfAbsent(cell(lat[i], lng[i]), k -> new ArrayList<>()).add(i);
        Map<Long, int[]> grid = new HashMap<>();
        cells.forEach((k, v) -> grid.put(k, v.stream().mapToInt(Integer::intValue).toArray()));
        return new Graph(lat, lng, off, adj, w, grid);
    }

    // ------------------------------------------------------------------ file

    static void write(Graph gr, Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(gr.lat.length);
            for (int i = 0; i < gr.lat.length; i++) { out.writeFloat(gr.lat[i]); out.writeFloat(gr.lng[i]); }
            for (int o : gr.off) out.writeInt(o);
            out.writeInt(gr.adj.length);
            for (int a : gr.adj) out.writeInt(a);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    static Graph read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a road graph file: " + file);
            int m = in.readInt();
            float[] lat = new float[m], lng = new float[m];
            for (int i = 0; i < m; i++) { lat[i] = in.readFloat(); lng[i] = in.readFloat(); }
            int[] off = new int[m + 1];
            for (int i = 0; i <= m; i++) off[i] = in.readInt();
            int[] adj = new int[in.readInt()];
            for (int i = 0; i < adj.length; i++) adj[i] = in.readInt();
            return finish(lat, lng, off, adj);
        }
    }
}
