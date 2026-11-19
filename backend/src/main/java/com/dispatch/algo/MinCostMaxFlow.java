package com.dispatch.algo;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Minimum-cost maximum flow by successive shortest augmenting paths (SPFA / queue-based Bellman-Ford,
 * so negative residual costs are handled). Among all maximum flows it returns one of least total cost.
 *
 * Time O(F * V * E) where F = max flow; fine for dispatch rounds (tens of riders x tens of jobs).
 * Used to assign delivery jobs to riders: max-flow alone only maximises how many jobs are covered,
 * this also minimises total ETA + lateness + workload penalties.
 */
public class MinCostMaxFlow {

    public record Result(int flow, double cost) {}

    private final int n;
    private int[] head, next, to, cap;
    private double[] cost;
    private int edges;

    public MinCostMaxFlow(int nodes) {
        if (nodes < 2) throw new IllegalArgumentException("a flow network needs at least 2 nodes");
        n = nodes;
        head = new int[n];
        Arrays.fill(head, -1);
        next = new int[16];
        to = new int[16];
        cap = new int[16];
        cost = new double[16];
    }

    public int addEdge(int u, int v, int c, double w) {
        if (u < 0 || u >= n || v < 0 || v >= n) throw new IndexOutOfBoundsException("node out of range");
        if (c < 0) throw new IllegalArgumentException("capacity must be non-negative");
        if (Double.isNaN(w) || Double.isInfinite(w)) throw new IllegalArgumentException("cost must be finite");
        if (edges + 2 > to.length) {
            int len = to.length * 2;
            next = Arrays.copyOf(next, len);
            to = Arrays.copyOf(to, len);
            cap = Arrays.copyOf(cap, len);
            cost = Arrays.copyOf(cost, len);
        }
        int id = edges;
        to[edges] = v; cap[edges] = c; cost[edges] = w; next[edges] = head[u]; head[u] = edges++;
        to[edges] = u; cap[edges] = 0; cost[edges] = -w; next[edges] = head[v]; head[v] = edges++;
        return id;
    }

    public int flow(int edgeId) {
        return cap[edgeId ^ 1];
    }

    public Result solve(int s, int t) {
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
        int flow = 0;
        double total = 0;
        double[] dist = new double[n];
        int[] via = new int[n];
        boolean[] inQueue = new boolean[n];
        while (true) {
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(via, -1);
            dist[s] = 0;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            inQueue[s] = true;
            while (!q.isEmpty()) {
                int u = q.poll();
                inQueue[u] = false;
                for (int e = head[u]; e != -1; e = next[e]) {
                    // 1e-9 tolerance stops floating-point noise from re-queueing nodes forever.
                    if (cap[e] > 0 && dist[u] + cost[e] < dist[to[e]] - 1e-9) {
                        dist[to[e]] = dist[u] + cost[e];
                        via[to[e]] = e;
                        if (!inQueue[to[e]]) {
                            inQueue[to[e]] = true;
                            q.add(to[e]);
                        }
                    }
                }
            }
            if (via[t] < 0) break;
            int push = Integer.MAX_VALUE;
            for (int v = t; v != s; v = to[via[v] ^ 1]) push = Math.min(push, cap[via[v]]);
            for (int v = t; v != s; v = to[via[v] ^ 1]) {
                cap[via[v]] -= push;
                cap[via[v] ^ 1] += push;
            }
            flow += push;
            total += push * dist[t];
        }
        return new Result(flow, total);
    }
}
