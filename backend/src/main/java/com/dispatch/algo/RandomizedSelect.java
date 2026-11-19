package com.dispatch.algo;

import java.util.Random;

/**
 * Randomized Quickselect: k-th smallest element in expected O(n) time, O(1) extra space
 * (worst case O(n^2) with vanishing probability thanks to the random pivot).
 */
public final class RandomizedSelect {

    private RandomizedSelect() {}

    /** Returns the k-th smallest (0-based) value. Works on a copy; the input is not modified. */
    public static int select(int[] data, int k, Random rnd) {
        if (data == null || data.length == 0) throw new IllegalArgumentException("cannot select from an empty array");
        if (k < 0 || k >= data.length) throw new IllegalArgumentException("k=" + k + " out of range [0," + data.length + ")");
        int[] a = data.clone();
        int lo = 0, hi = a.length - 1;
        while (true) {
            if (lo == hi) return a[lo];
            int p = lo + rnd.nextInt(hi - lo + 1);
            // Three-way partition so arrays full of duplicates stay linear.
            int pivot = a[p];
            int lt = lo, i = lo, gt = hi;
            while (i <= gt) {
                if (a[i] < pivot) swap(a, lt++, i++);
                else if (a[i] > pivot) swap(a, i, gt--);
                else i++;
            }
            if (k < lt) hi = lt - 1;
            else if (k > gt) lo = gt + 1;
            else return pivot;
        }
    }

    /** Nearest-rank percentile, p in (0, 100]. */
    public static int percentile(int[] data, double p, Random rnd) {
        if (p <= 0 || p > 100) throw new IllegalArgumentException("percentile must be in (0,100]");
        if (data == null || data.length == 0) throw new IllegalArgumentException("cannot select from an empty array");
        int k = (int) Math.ceil(p / 100.0 * data.length) - 1;
        return select(data, Math.max(k, 0), rnd);
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
