package com.dispatch.algo;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Dinic and min-cost max-flow, checked against Edmonds-Karp and brute force. Plus FibonacciHeap.remove. */
class FlowAlgorithmsTest {

    @Test
    void dinicSolvesClrsExample() {
        Dinic d = new Dinic(6);
        int[][] e = {{0, 1, 16}, {0, 2, 13}, {1, 3, 12}, {2, 1, 4}, {2, 4, 14}, {3, 2, 9}, {3, 5, 20}, {4, 3, 7}, {4, 5, 4}};
        for (int[] x : e) d.addEdge(x[0], x[1], x[2]);
        assertEquals(23, d.maxFlow(0, 5));
    }

    @Test
    void dinicMatchesEdmondsKarpOnRandomGraphs() {
        Random r = new Random(4);
        for (int trial = 0; trial < 100; trial++) {
            int n = 2 + r.nextInt(10);
            Dinic d = new Dinic(n);
            MaxFlow ek = new MaxFlow(n);
            for (int i = 0; i < n * 3; i++) {
                int u = r.nextInt(n), v = r.nextInt(n), c = r.nextInt(12);
                if (u == v) continue;
                d.addEdge(u, v, c);
                ek.addEdge(u, v, c);
            }
            assertEquals(ek.maxFlow(0, n - 1), d.maxFlow(0, n - 1));
        }
    }

    @Test
    void dinicEdgeCases() { // boundary + invalid
        Dinic d = new Dinic(4);
        d.addEdge(0, 1, 5);
        assertEquals(0, d.maxFlow(0, 3)); // sink unreachable
        assertThrows(IllegalArgumentException.class, () -> d.maxFlow(1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Dinic(1));
        assertThrows(IllegalArgumentException.class, () -> d.addEdge(0, 1, -2));
    }

    @Test
    void minCostPicksTheCheapestPerfectAssignment() {
        // 2 riders x 2 jobs: greedy-cheapest edge (r0-j0, 1) forces r1-j1 (100) = 101; optimum is r0-j1 + r1-j0 = 4.
        double[][] cost = {{1, 2}, {2, 100}};
        assertEquals(4.0, solveAssignment(cost).cost(), 1e-9);
    }

    @Test
    void minCostNeverTradesFlowForCost() {
        // One job reachable only through an expensive edge is still taken: max flow first, then min cost.
        MinCostMaxFlow f = new MinCostMaxFlow(4);
        f.addEdge(0, 1, 1, 0);
        f.addEdge(0, 2, 1, 0);
        f.addEdge(1, 3, 1, 1);
        f.addEdge(2, 3, 1, 1000);
        MinCostMaxFlow.Result r = f.solve(0, 3);
        assertEquals(2, r.flow());
        assertEquals(1001, r.cost(), 1e-9);
    }

    @Test
    void minCostMatchesBruteForceOnRandomAssignments() {
        Random r = new Random(9);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + r.nextInt(5);
            double[][] cost = new double[n][n];
            for (double[] row : cost) for (int j = 0; j < n; j++) row[j] = r.nextInt(50);
            assertEquals(bruteForce(cost, 0, new boolean[n]), solveAssignment(cost).cost(), 1e-9);
        }
    }

    @Test
    void minCostRejectsBadInput() {
        MinCostMaxFlow f = new MinCostMaxFlow(3);
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 1, 1, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> f.addEdge(0, 1, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> f.addEdge(0, 5, 1, 1));
        assertEquals(new MinCostMaxFlow.Result(0, 0), f.solve(0, 2)); // empty network
    }

    @Test
    void fibonacciRemoveWorksAnywhereInTheHeap() {
        Random r = new Random(2);
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        List<FibonacciHeap.Node<Integer>> nodes = new ArrayList<>();
        TreeMap<Long, Integer> ref = new TreeMap<>();
        for (int i = 0; i < 300; i++) {
            long k = i * 7L % 1000 * 1000 + i; // distinct keys
            nodes.add(h.insert(k, i));
            ref.put(k, i);
        }
        h.extractMin(); // build trees
        ref.pollFirstEntry();
        Collections.shuffle(nodes, r);
        for (FibonacciHeap.Node<Integer> n : nodes.subList(0, 150)) {
            if (!n.inHeap()) continue;
            h.remove(n);
            ref.remove(n.key());
            assertFalse(n.inHeap());
        }
        assertEquals(ref.size(), h.size());
        for (long k : ref.keySet()) assertEquals(k, h.extractMin().key());
        assertThrows(IllegalArgumentException.class, () -> h.remove(nodes.get(0)));
    }

    private static MinCostMaxFlow.Result solveAssignment(double[][] cost) {
        int n = cost.length, S = 0, T = 2 * n + 1;
        MinCostMaxFlow f = new MinCostMaxFlow(T + 1);
        for (int i = 0; i < n; i++) {
            f.addEdge(S, 1 + i, 1, 0);
            f.addEdge(1 + n + i, T, 1, 0);
            for (int j = 0; j < n; j++) f.addEdge(1 + i, 1 + n + j, 1, cost[i][j]);
        }
        MinCostMaxFlow.Result r = f.solve(S, T);
        assertEquals(n, r.flow());
        return r;
    }

    private static double bruteForce(double[][] c, int row, boolean[] used) {
        if (row == c.length) return 0;
        double best = Double.MAX_VALUE;
        for (int j = 0; j < c.length; j++) {
            if (used[j]) continue;
            used[j] = true;
            best = Math.min(best, c[row][j] + bruteForce(c, row + 1, used));
            used[j] = false;
        }
        return best;
    }
}
