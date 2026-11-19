package com.dispatch.delivery.allocation;

import com.dispatch.algo.Dinic;
import com.dispatch.algo.MaxFlow;
import com.dispatch.algo.MinCostMaxFlow;
import com.dispatch.delivery.dispatch.Batcher.Batch;
import com.dispatch.delivery.geo.TrafficModel;
import com.dispatch.delivery.model.Live.Order;
import com.dispatch.delivery.model.Live.Rider;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Assigns delivery batches to riders on a bipartite flow network
 *   S -> rider (cap 1) -> batch (cap 1, cost) -> T (cap 1).
 *
 * MIN_COST (used live): min-cost max-flow. Maximises how many batches get a rider, then minimises
 *   total cost = sum of predicted delivery minutes + 4 x minutes late past each SLA + 0.6 x rider's deliveries today
 *   (the last term spreads work so the same nearby riders are not overloaded).
 * EDMONDS_KARP / DINIC: plain max-flow on the same network. They find the same NUMBER of assignments but ignore
 *   ETA, SLA and workload, so their assignments are arbitrary among the maximum ones. compare() shows the gap.
 */
public final class RiderAllocator {
    private RiderAllocator() {}

    public enum Algo { MIN_COST, EDMONDS_KARP, DINIC }

    public static final double MAX_PICKUP_MIN = 30;

    public record Plan(Batch batch, Rider rider, double cost, Map<Long, Double> etaMin, double lateMin) {}

    public record Comparison(String algorithm, int assigned, double totalEtaMin, double totalLateMin,
                             int predictedLateOrders, long micros) {}

    private record Option(Map<Long, Double> eta, double late, double cost) {}

    /** Predicted minutes-from-now to each drop if rider r takes batch b, plus total lateness. */
    public static Map<Long, Double> evaluate(Rider r, Batch b, TrafficModel traffic, LocalDateTime now, double[] lateOut) {
        double t = traffic.travelMin(r.lat, r.lng, b.store().lat, b.store().lng, now);
        for (Order o : b.drops()) {
            if (o.prepDoneAt != null) t = Math.max(t, Math.max(0, Duration.between(now, o.prepDoneAt).toSeconds() / 60.0));
        }
        double lat = b.store().lat, lng = b.store().lng, late = 0;
        Map<Long, Double> eta = new LinkedHashMap<>();
        for (Order o : b.drops()) {
            t += traffic.travelMin(lat, lng, o.lat, o.lng, now) + traffic.dropMin(o.highRise);
            eta.put(o.id, t);
            late += Math.max(0, t - Duration.between(now, o.promisedAt).toSeconds() / 60.0);
            lat = o.lat;
            lng = o.lng;
        }
        lateOut[0] = late;
        return eta;
    }

    public static List<Plan> allocate(List<Batch> batches, List<Rider> riders, TrafficModel traffic,
                                      LocalDateTime now, Algo algo) {
        int R = riders.size(), B = batches.size();
        if (R == 0 || B == 0) return List.of();
        Option[][] opt = new Option[R][B];
        for (int i = 0; i < R; i++) {
            Rider r = riders.get(i);
            for (int j = 0; j < B; j++) {
                Batch b = batches.get(j);
                if (b.drops().size() > r.capacity) continue;
                if (traffic.travelMin(r.lat, r.lng, b.store().lat, b.store().lng, now) > MAX_PICKUP_MIN) continue;
                double[] late = new double[1];
                Map<Long, Double> eta = evaluate(r, b, traffic, now, late);
                double cost = eta.values().stream().mapToDouble(Double::doubleValue).sum() + 4 * late[0] + 0.6 * r.deliveriesToday;
                opt[i][j] = new Option(eta, late[0], cost);
            }
        }
        int S = 0, T = R + B + 1;
        List<int[]> picked = new ArrayList<>(); // [i, j]
        switch (algo) {
            case MIN_COST -> {
                MinCostMaxFlow mf = new MinCostMaxFlow(T + 1);
                int[][] id = edges(R, B, opt, (u, v, c, w) -> mf.addEdge(u, v, c, w));
                mf.solve(S, T);
                collect(id, R, B, e -> mf.flow(e) == 1, picked);
            }
            case DINIC -> {
                Dinic mf = new Dinic(T + 1);
                int[][] id = edges(R, B, opt, (u, v, c, w) -> mf.addEdge(u, v, c));
                mf.maxFlow(S, T);
                collect(id, R, B, e -> mf.flow(e) == 1, picked);
            }
            case EDMONDS_KARP -> {
                MaxFlow mf = new MaxFlow(T + 1);
                List<MaxFlow.Edge> list = new ArrayList<>();
                int[][] id = edges(R, B, opt, (u, v, c, w) -> { list.add(mf.addEdge(u, v, c)); return list.size() - 1; });
                mf.maxFlow(S, T);
                collect(id, R, B, e -> list.get(e).flow() == 1, picked);
            }
        }
        List<Plan> plans = new ArrayList<>();
        for (int[] p : picked) {
            Option o = opt[p[0]][p[1]];
            plans.add(new Plan(batches.get(p[1]), riders.get(p[0]), o.cost(), o.eta(), o.late()));
        }
        return plans;
    }

    /** Runs all three algorithms on the same snapshot so their assignment quality and speed can be compared. */
    public static List<Comparison> compare(List<Batch> batches, List<Rider> riders, TrafficModel traffic, LocalDateTime now) {
        List<Comparison> out = new ArrayList<>();
        for (Algo a : Algo.values()) {
            long t0 = System.nanoTime();
            List<Plan> plans = allocate(batches, riders, traffic, now, a);
            long micros = (System.nanoTime() - t0) / 1000;
            double eta = 0, late = 0;
            int lateOrders = 0;
            for (Plan p : plans) {
                eta += p.etaMin().values().stream().mapToDouble(Double::doubleValue).sum();
                late += p.lateMin();
                for (Order o : p.batch().drops()) {
                    if (p.etaMin().get(o.id) > Duration.between(now, o.promisedAt).toSeconds() / 60.0) lateOrders++;
                }
            }
            int assigned = plans.stream().mapToInt(p -> p.batch().drops().size()).sum();
            out.add(new Comparison(a.name(), assigned, Math.round(eta * 10) / 10.0, Math.round(late * 10) / 10.0, lateOrders, micros));
        }
        return out;
    }

    @FunctionalInterface
    private interface EdgeAdder { int add(int u, int v, int cap, double cost); }

    private static int[][] edges(int R, int B, Option[][] opt, EdgeAdder add) {
        int S = 0, T = R + B + 1;
        int[][] id = new int[R][B];
        for (int i = 0; i < R; i++) add.add(S, 1 + i, 1, 0);
        for (int i = 0; i < R; i++) {
            for (int j = 0; j < B; j++) {
                id[i][j] = opt[i][j] == null ? -1 : add.add(1 + i, 1 + R + j, 1, opt[i][j].cost());
            }
        }
        for (int j = 0; j < B; j++) add.add(1 + R + j, T, 1, 0);
        return id;
    }

    private static void collect(int[][] id, int R, int B, java.util.function.IntPredicate used, List<int[]> out) {
        for (int i = 0; i < R; i++) for (int j = 0; j < B; j++) if (id[i][j] >= 0 && used.test(id[i][j])) out.add(new int[]{i, j});
    }
}
