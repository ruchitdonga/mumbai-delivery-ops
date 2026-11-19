package com.dispatch.service;

import com.dispatch.algo.FibonacciHeap;
import com.dispatch.algo.IndexedBinaryHeap;
import com.dispatch.algo.MaxFlow;
import com.dispatch.algo.RandomizedSelect;
import com.dispatch.model.Models.BenchmarkRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Reproducible benchmarks (fixed seeds) on three increasing input sizes for each core algorithm,
 * each against a baseline. Times are the median of 3 runs after one warm-up run.
 */
@Service
public class BenchmarkService {

    public List<BenchmarkRow> runAll() {
        List<BenchmarkRow> rows = new ArrayList<>();
        for (int n : new int[]{10_000, 100_000, 500_000}) rows.add(heapBench(n));
        for (int n : new int[]{10_000, 100_000, 500_000}) rows.add(escalationStormBench(n));
        for (int n : new int[]{100, 400, 1_000}) rows.add(matchingBench(n));
        for (int n : new int[]{100_000, 1_000_000, 5_000_000}) rows.add(selectBench(n));
        return rows;
    }

    /** Workload shaped like a busy control room: n inserts, 3n escalations (decreaseKey), n extractMins. */
    public BenchmarkRow heapBench(int n) {
        Random rnd = new Random(7);
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = 1_000_000_000L + rnd.nextInt(1_000_000_000);
        int[] decIds = new int[3 * n];
        long[] decBy = new long[3 * n];
        for (int i = 0; i < decIds.length; i++) { decIds[i] = rnd.nextInt(n); decBy[i] = 1 + rnd.nextInt(1000); }

        double fib = time(() -> {
            FibonacciHeap<Void> h = new FibonacciHeap<>();
            List<FibonacciHeap.Node<Void>> nodes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) nodes.add(h.insert(keys[i], null));
            for (int i = 0; i < decIds.length; i++) {
                FibonacciHeap.Node<Void> node = nodes.get(decIds[i]);
                h.decreaseKey(node, node.key() - decBy[i]);
            }
            while (!h.isEmpty()) h.extractMin();
        });
        double bin = time(() -> {
            IndexedBinaryHeap h = new IndexedBinaryHeap(n);
            for (int i = 0; i < n; i++) h.insert(i, keys[i]);
            for (int i = 0; i < decIds.length; i++) h.decreaseKey(decIds[i], h.keyOf(decIds[i]) - decBy[i]);
            while (h.size() > 0) h.extractMin();
        });
        return new BenchmarkRow("Priority queue: n inserts + 3n escalations + n dispatches", n,
                "Fibonacci heap", fib, "Indexed binary heap", bin,
                "Theory: O(1) amortized decreaseKey vs O(log n). Practice: pointer-heavy nodes cost cache misses, so the array heap usually wins at these sizes");
    }

    /**
     * Escalation storm: n waiting calls, then 10n escalations that each jump a call far up the queue
     * (large key drops), with only n/100 dispatches in between. decreaseKey dominates, which is the
     * regime where the Fibonacci heap's O(1) amortized decreaseKey is supposed to pay off.
     */
    public BenchmarkRow escalationStormBench(int n) {
        Random rnd = new Random(17);
        long[] keys = new long[n];
        for (int i = 0; i < n; i++) keys[i] = 4_000_000_000_000L + rnd.nextInt(1_000_000_000);
        int ops = 10 * n;
        int[] ids = new int[ops];
        long[] drop = new long[ops];
        for (int i = 0; i < ops; i++) { ids[i] = rnd.nextInt(n); drop[i] = 1_000_000L + rnd.nextInt(1_000_000_000); }
        int extracts = Math.max(1, n / 100);

        double fib = time(() -> {
            FibonacciHeap<Void> h = new FibonacciHeap<>();
            List<FibonacciHeap.Node<Void>> nodes = new ArrayList<>(n);
            for (int i = 0; i < n; i++) nodes.add(h.insert(keys[i], null));
            for (int i = 0; i < ops; i++) {
                FibonacciHeap.Node<Void> node = nodes.get(ids[i]);
                if (node.inHeap()) h.decreaseKey(node, node.key() - drop[i]);
                if (i % (ops / extracts) == 0) h.extractMin();
            }
        });
        double bin = time(() -> {
            IndexedBinaryHeap h = new IndexedBinaryHeap(n);
            boolean[] gone = new boolean[n];
            for (int i = 0; i < n; i++) h.insert(i, keys[i]);
            for (int i = 0; i < ops; i++) {
                if (!gone[ids[i]]) h.decreaseKey(ids[i], h.keyOf(ids[i]) - drop[i]);
                if (i % (ops / extracts) == 0) gone[h.extractMin()] = true;
            }
        });
        return new BenchmarkRow("Escalation storm: n calls, 10n large escalations, n/100 dispatches", n,
                "Fibonacci heap", fib, "Indexed binary heap", bin,
                "decreaseKey-dominated: gap narrows vs the mixed workload, as the amortized analysis predicts");
    }

    /** Random bipartite instance: n patients, n ambulances, n/10 hospitals with 10 beds each. Edges with prob ~4/n. */
    BenchmarkRow matchingBench(int n) {
        Random rnd = new Random(11);
        int hospitals = Math.max(1, n / 10);
        double pEdge = Math.min(1.0, 4.0 / n);
        boolean[][] amb = new boolean[n][n];
        boolean[][] hos = new boolean[n][hospitals];
        for (int p = 0; p < n; p++) {
            for (int a = 0; a < n; a++) amb[p][a] = rnd.nextDouble() < pEdge;
            for (int h = 0; h < hospitals; h++) hos[p][h] = rnd.nextDouble() < Math.min(1.0, 3.0 / hospitals);
        }
        int[] served = new int[2];
        double mfMs = time(() -> {
            int S = 0, aB = 1, pIn = aB + n, pOut = pIn + n, hB = pOut + n, T = hB + hospitals;
            MaxFlow mf = new MaxFlow(T + 1);
            for (int a = 0; a < n; a++) mf.addEdge(S, aB + a, 1);
            for (int p = 0; p < n; p++) {
                for (int a = 0; a < n; a++) if (amb[p][a]) mf.addEdge(aB + a, pIn + p, 1);
                mf.addEdge(pIn + p, pOut + p, 1);
                for (int h = 0; h < hospitals; h++) if (hos[p][h]) mf.addEdge(pOut + p, hB + h, 1);
            }
            for (int h = 0; h < hospitals; h++) mf.addEdge(hB + h, T, 10);
            served[0] = mf.maxFlow(S, T);
        });
        double grMs = time(() -> {
            boolean[] usedA = new boolean[n];
            int[] bedsLeft = new int[hospitals];
            Arrays.fill(bedsLeft, 10);
            int s = 0;
            for (int p = 0; p < n; p++) {
                int a = -1, h = -1;
                for (int i = 0; i < n && a < 0; i++) if (amb[p][i] && !usedA[i]) a = i;
                for (int i = 0; i < hospitals && h < 0; i++) if (hos[p][i] && bedsLeft[i] > 0) h = i;
                if (a >= 0 && h >= 0) { usedA[a] = true; bedsLeft[h]--; s++; }
            }
            served[1] = s;
        });
        return new BenchmarkRow("Dispatch matching: n calls, n ambulances, n/10 hospitals", n,
                "Edmonds-Karp max-flow", mfMs, "Greedy first-fit", grMs,
                "served " + served[0] + " vs " + served[1] + " (max-flow is optimal in count)");
    }

    /** 90th percentile of n simulated values. */
    BenchmarkRow selectBench(int n) {
        Random rnd = new Random(13);
        int[] data = new int[n];
        for (int i = 0; i < n; i++) data[i] = rnd.nextInt(200);
        int[] res = new int[2];
        double qs = time(() -> res[0] = RandomizedSelect.percentile(data, 90, new Random(1)));
        double sort = time(() -> {
            int[] c = data.clone();
            Arrays.sort(c);
            res[1] = c[(int) Math.ceil(0.9 * n) - 1];
        });
        return new BenchmarkRow("p90 of n simulated values", n, "Randomized Quickselect", qs,
                "Full sort (Arrays.sort)", sort,
                "expected O(n) vs O(n log n); both return " + res[0] + (res[0] == res[1] ? "" : " (MISMATCH " + res[1] + ")"));
    }

    private static double time(Runnable r) {
        r.run(); // warm-up for the JIT
        double[] t = new double[3];
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            r.run();
            t[i] = (System.nanoTime() - s) / 1e6;
        }
        Arrays.sort(t);
        return Math.round(t[1] * 100) / 100.0;
    }
}
