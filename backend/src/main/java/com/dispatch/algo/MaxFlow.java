package com.dispatch.algo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Edmonds-Karp maximum flow (Ford-Fulkerson with BFS shortest augmenting paths).
 *
 * Time O(V * E^2), space O(V + E). Integer capacities, so the result is integral,
 * which is what lets us read assignments straight off the flow (integrality theorem).
 */
public class MaxFlow {

    public static final class Edge {
        public final int from, to;
        final int cap;
        int flow;
        Edge reverse;

        Edge(int from, int to, int cap) {
            this.from = from;
            this.to = to;
            this.cap = cap;
        }

        public int flow() { return flow; }
        public int capacity() { return cap; }
        int residual() { return cap - flow; }
    }

    private final int n;
    private final List<List<Edge>> adj;
    private int augmentingPaths;

    public MaxFlow(int nodes) {
        if (nodes < 2) throw new IllegalArgumentException("a flow network needs at least 2 nodes");
        this.n = nodes;
        this.adj = new ArrayList<>(nodes);
        for (int i = 0; i < nodes; i++) adj.add(new ArrayList<>());
    }

    /** Adds a directed edge and returns it so callers can read its flow afterwards. */
    public Edge addEdge(int from, int to, int cap) {
        check(from);
        check(to);
        if (cap < 0) throw new IllegalArgumentException("capacity must be non-negative");
        Edge e = new Edge(from, to, cap);
        Edge r = new Edge(to, from, 0);
        e.reverse = r;
        r.reverse = e;
        adj.get(from).add(e);
        adj.get(to).add(r);
        return e;
    }

    public int maxFlow(int s, int t) {
        check(s);
        check(t);
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
        int total = 0;
        Edge[] via = new Edge[n];
        while (true) {
            Arrays.fill(via, null);
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s);
            boolean[] seen = new boolean[n];
            seen[s] = true;
            while (!q.isEmpty() && !seen[t]) {
                int u = q.poll();
                for (Edge e : adj.get(u)) {
                    if (!seen[e.to] && e.residual() > 0) {
                        seen[e.to] = true;
                        via[e.to] = e;
                        q.add(e.to);
                    }
                }
            }
            if (!seen[t]) break;

            int bottleneck = Integer.MAX_VALUE;
            for (int v = t; v != s; v = via[v].from) bottleneck = Math.min(bottleneck, via[v].residual());
            for (int v = t; v != s; v = via[v].from) {
                via[v].flow += bottleneck;
                via[v].reverse.flow -= bottleneck;
            }
            total += bottleneck;
            augmentingPaths++;
        }
        return total;
    }

    /** Nodes reachable from s in the residual graph after maxFlow: the source side of a minimum cut. */
    public boolean[] minCutSourceSide(int s) {
        boolean[] seen = new boolean[n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(s);
        seen[s] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            for (Edge e : adj.get(u)) {
                if (!seen[e.to] && e.residual() > 0) {
                    seen[e.to] = true;
                    q.add(e.to);
                }
            }
        }
        return seen;
    }

    public int augmentingPaths() { return augmentingPaths; }

    private void check(int v) {
        if (v < 0 || v >= n) throw new IndexOutOfBoundsException("node " + v + " not in [0," + n + ")");
    }
}
