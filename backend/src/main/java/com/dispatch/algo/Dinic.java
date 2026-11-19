package com.dispatch.algo;

import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Dinic's maximum flow: BFS builds a level graph, DFS sends blocking flows along it.
 * O(V^2 E) in general and O(E sqrt V) on unit-capacity bipartite graphs, versus Edmonds-Karp's O(V E^2).
 * Edge storage is array-based (forward edge 2i, reverse 2i+1).
 */
public class Dinic {
    private final int n;
    private int[] head, next, to, cap;
    private int edges;
    private int[] level, iter;

    public Dinic(int nodes) {
        if (nodes < 2) throw new IllegalArgumentException("a flow network needs at least 2 nodes");
        n = nodes;
        head = new int[n];
        Arrays.fill(head, -1);
        next = new int[16];
        to = new int[16];
        cap = new int[16];
    }

    /** Adds u -> v with the given capacity; returns the edge id (use with flow()). */
    public int addEdge(int u, int v, int c) {
        if (u < 0 || u >= n || v < 0 || v >= n) throw new IndexOutOfBoundsException("node out of range");
        if (c < 0) throw new IllegalArgumentException("capacity must be non-negative");
        if (edges + 2 > to.length) {
            next = Arrays.copyOf(next, to.length * 2);
            cap = Arrays.copyOf(cap, to.length * 2);
            to = Arrays.copyOf(to, to.length * 2);
        }
        int id = edges;
        to[edges] = v; cap[edges] = c; next[edges] = head[u]; head[u] = edges++;
        to[edges] = u; cap[edges] = 0; next[edges] = head[v]; head[v] = edges++;
        return id;
    }

    /** Flow currently on an edge returned by addEdge (= capacity used, read from the reverse edge). */
    public int flow(int edgeId) {
        return cap[edgeId ^ 1];
    }

    public int maxFlow(int s, int t) {
        if (s == t) throw new IllegalArgumentException("source and sink must differ");
        level = new int[n];
        iter = new int[n];
        int total = 0;
        while (bfs(s, t)) {
            System.arraycopy(head, 0, iter, 0, n);
            int f;
            while ((f = dfs(s, t, Integer.MAX_VALUE)) > 0) total += f;
        }
        return total;
    }

    private boolean bfs(int s, int t) {
        Arrays.fill(level, -1);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        level[s] = 0;
        q.add(s);
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int e = head[u]; e != -1; e = next[e]) {
                if (cap[e] > 0 && level[to[e]] < 0) {
                    level[to[e]] = level[u] + 1;
                    q.add(to[e]);
                }
            }
        }
        return level[t] >= 0;
    }

    // Recursion depth is bounded by the level graph's depth (4 layers in the dispatch network).
    private int dfs(int u, int t, int f) {
        if (u == t) return f;
        for (; iter[u] != -1; iter[u] = next[iter[u]]) {
            int e = iter[u], v = to[e];
            if (cap[e] > 0 && level[v] == level[u] + 1) {
                int d = dfs(v, t, Math.min(f, cap[e]));
                if (d > 0) {
                    cap[e] -= d;
                    cap[e ^ 1] += d;
                    return d;
                }
            }
        }
        return 0;
    }
}
