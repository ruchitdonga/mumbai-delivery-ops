package com.dispatch.algo;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FibonacciHeapTest {

    @Test
    void emptyHeapExtractThrows() { // edge case
        FibonacciHeap<String> h = new FibonacciHeap<>();
        assertTrue(h.isEmpty());
        assertThrows(NoSuchElementException.class, h::extractMin);
        assertThrows(NoSuchElementException.class, h::peekMin);
    }

    @Test
    void singleElement() { // boundary
        FibonacciHeap<String> h = new FibonacciHeap<>();
        h.insert(5, "a");
        assertEquals("a", h.extractMin().value());
        assertTrue(h.isEmpty());
    }

    @Test
    void extractsInSortedOrderWithDuplicates() {
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        long[] keys = {5, 3, 9, 3, 1, 7, 1, 8};
        for (int i = 0; i < keys.length; i++) h.insert(keys[i], i);
        long[] sorted = keys.clone();
        Arrays.sort(sorted);
        for (long k : sorted) assertEquals(k, h.extractMin().key());
    }

    @Test
    void decreaseKeyOnConsolidatedTreeMovesNodeToFront() {
        FibonacciHeap<Integer> h = new FibonacciHeap<>();
        List<FibonacciHeap.Node<Integer>> nodes = new ArrayList<>();
        for (int i = 0; i < 64; i++) nodes.add(h.insert(100 + i, i));
        h.extractMin(); // consolidation builds trees, so the largest keys are now children
        FibonacciHeap.Node<Integer> deep = nodes.get(63);
        h.decreaseKey(deep, 0);
        assertSame(deep, h.peekMin());
        long prev = Long.MIN_VALUE;
        while (!h.isEmpty()) {
            long k = h.extractMin().key();
            assertTrue(k >= prev);
            prev = k;
        }
    }

    @Test
    void decreaseKeyRejectsLargerKeyAndRemovedNode() { // invalid input
        FibonacciHeap<String> h = new FibonacciHeap<>();
        FibonacciHeap.Node<String> n = h.insert(10, "x");
        assertThrows(IllegalArgumentException.class, () -> h.decreaseKey(n, 11));
        h.extractMin();
        assertThrows(IllegalArgumentException.class, () -> h.decreaseKey(n, 1));
    }

    @Test
    void randomizedMixedOpsAgainstReference() { // stress: 20k random insert / decreaseKey / extractMin
        Random rnd = new Random(1);
        FibonacciHeap<Integer> fib = new FibonacciHeap<>();
        Map<Integer, FibonacciHeap.Node<Integer>> live = new HashMap<>();
        Map<Integer, Long> keyOf = new HashMap<>();
        List<Integer> ids = new ArrayList<>();
        int next = 0;
        for (int step = 0; step < 20_000; step++) {
            int op = rnd.nextInt(10);
            if (op < 5 || live.isEmpty()) {
                long k = rnd.nextInt(1_000_000);
                live.put(next, fib.insert(k, next));
                keyOf.put(next, k);
                ids.add(next++);
            } else if (op < 8) {
                int id = ids.get(rnd.nextInt(ids.size()));
                if (!live.containsKey(id)) continue;
                long nk = keyOf.get(id) - rnd.nextInt(1000);
                fib.decreaseKey(live.get(id), nk);
                keyOf.put(id, nk);
            } else {
                long expected = Collections.min(keyOf.values());
                FibonacciHeap.Node<Integer> got = fib.extractMin();
                assertEquals(expected, got.key());
                live.remove(got.value());
                keyOf.remove(got.value());
            }
            assertEquals(live.size(), fib.size());
        }
    }
}
