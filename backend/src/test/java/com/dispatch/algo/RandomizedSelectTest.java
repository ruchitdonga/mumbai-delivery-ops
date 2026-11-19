package com.dispatch.algo;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RandomizedSelectTest {

    private final Random rnd = new Random(5);

    @Test
    void findsMedianOfSmallArray() {
        assertEquals(5, RandomizedSelect.select(new int[]{9, 1, 5, 3, 7}, 2, rnd));
    }

    @Test
    void singleElementAndAllDuplicates() { // boundary
        assertEquals(42, RandomizedSelect.select(new int[]{42}, 0, rnd));
        int[] same = new int[100_000];
        Arrays.fill(same, 7);
        assertEquals(7, RandomizedSelect.select(same, 50_000, rnd));
    }

    @Test
    void rejectsEmptyAndOutOfRange() { // invalid input
        assertThrows(IllegalArgumentException.class, () -> RandomizedSelect.select(new int[0], 0, rnd));
        assertThrows(IllegalArgumentException.class, () -> RandomizedSelect.select(new int[]{1, 2}, 2, rnd));
        assertThrows(IllegalArgumentException.class, () -> RandomizedSelect.select(new int[]{1, 2}, -1, rnd));
        assertThrows(IllegalArgumentException.class, () -> RandomizedSelect.percentile(new int[]{1}, 0, rnd));
    }

    @Test
    void doesNotModifyInput() {
        int[] a = {3, 1, 2};
        RandomizedSelect.select(a, 0, rnd);
        assertArrayEquals(new int[]{3, 1, 2}, a);
    }

    @Test
    void matchesSortingOnRandomArrays() { // 500 random trials against Arrays.sort
        Random r = new Random(9);
        for (int trial = 0; trial < 500; trial++) {
            int n = 1 + r.nextInt(300);
            int[] a = new int[n];
            for (int i = 0; i < n; i++) a[i] = r.nextInt(50) - 25;
            int k = r.nextInt(n);
            int[] sorted = a.clone();
            Arrays.sort(sorted);
            assertEquals(sorted[k], RandomizedSelect.select(a, k, rnd));
        }
    }

    @Test
    void percentileUsesNearestRank() {
        int[] a = new int[100];
        for (int i = 0; i < 100; i++) a[i] = i + 1; // 1..100
        assertEquals(90, RandomizedSelect.percentile(a, 90, rnd));
        assertEquals(100, RandomizedSelect.percentile(a, 100, rnd));
        assertEquals(50, RandomizedSelect.percentile(a, 50, rnd));
    }
}
