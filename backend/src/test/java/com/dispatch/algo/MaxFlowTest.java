package com.dispatch.algo;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MaxFlowTest {

    @Test
    void classicClrsNetworkHasFlow23() {
        // CLRS Figure 26.1: s=0, v1..v4 = 1..4, t=5
        MaxFlow mf = new MaxFlow(6);
        mf.addEdge(0, 1, 16);
        mf.addEdge(0, 2, 13);
        mf.addEdge(1, 3, 12);
        mf.addEdge(2, 1, 4);
        mf.addEdge(2, 4, 14);
        mf.addEdge(3, 2, 9);
        mf.addEdge(3, 5, 20);
        mf.addEdge(4, 3, 7);
        mf.addEdge(4, 5, 4);
        assertEquals(23, mf.maxFlow(0, 5));
    }

    @Test
    void disconnectedSinkGivesZero() { // edge: infeasible network
        MaxFlow mf = new MaxFlow(4);
        mf.addEdge(0, 1, 5);
        mf.addEdge(2, 3, 5);
        assertEquals(0, mf.maxFlow(0, 3));
    }

    @Test
    void invalidInputsRejected() { // invalid input
        assertThrows(IllegalArgumentException.class, () -> new MaxFlow(1));
        MaxFlow mf = new MaxFlow(3);
        assertThrows(IllegalArgumentException.class, () -> mf.addEdge(0, 1, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> mf.addEdge(0, 7, 1));
        assertThrows(IllegalArgumentException.class, () -> mf.maxFlow(1, 1));
    }

    @Test
    void greedyTrapIsSolvedByAugmentingThroughReverseEdge() {
        // Patient P0 can use A0 or A1, patient P1 can only use A0.
        // Giving A0 to P0 first serves 1; max-flow reroutes through the reverse edge and serves 2.
        int s = 0, a0 = 1, a1 = 2, p0 = 3, p1 = 4, t = 5;
        MaxFlow mf = new MaxFlow(6);
        mf.addEdge(s, a0, 1);
        mf.addEdge(s, a1, 1);
        MaxFlow.Edge first = mf.addEdge(a0, p0, 1); // listed first so the first BFS path uses it
        mf.addEdge(a1, p0, 1);
        mf.addEdge(a0, p1, 1);
        mf.addEdge(p0, t, 1);
        mf.addEdge(p1, t, 1);
        assertEquals(2, mf.maxFlow(s, t));
        assertEquals(0, first.flow(), "A0 must have been re-routed to P1");
    }

    @Test
    void minCutCapacityEqualsMaxFlow() { // max-flow min-cut theorem on 50 random graphs
        Random rnd = new Random(3);
        for (int trial = 0; trial < 50; trial++) {
            int n = 8;
            MaxFlow mf = new MaxFlow(n);
            int[][] cap = new int[n][n];
            for (int i = 0; i < 20; i++) {
                int u = rnd.nextInt(n), v = rnd.nextInt(n), c = rnd.nextInt(10);
                if (u == v) continue;
                mf.addEdge(u, v, c);
                cap[u][v] += c;
            }
            int flow = mf.maxFlow(0, n - 1);
            boolean[] side = mf.minCutSourceSide(0);
            assertFalse(side[n - 1]);
            int cut = 0;
            for (int u = 0; u < n; u++) for (int v = 0; v < n; v++) if (side[u] && !side[v]) cut += cap[u][v];
            assertEquals(cut, flow);
        }
    }

    @Test
    void largeUnitNetworkRunsFast() { // stress
        int n = 2000;
        MaxFlow mf = new MaxFlow(2 * n + 2);
        int s = 2 * n, t = 2 * n + 1;
        for (int i = 0; i < n; i++) {
            mf.addEdge(s, i, 1);
            mf.addEdge(i, n + i, 1);
            mf.addEdge(i, n + (i + 1) % n, 1);
            mf.addEdge(n + i, t, 1);
        }
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> assertEquals(n, mf.maxFlow(s, t)));
    }
}
