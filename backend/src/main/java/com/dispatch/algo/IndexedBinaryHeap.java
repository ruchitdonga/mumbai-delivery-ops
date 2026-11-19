package com.dispatch.algo;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * Baseline for the benchmark: array binary min-heap with a position index so decreaseKey is O(log n).
 * Elements are ints in [0, capacity). insert/decreaseKey/extractMin are all O(log n).
 */
public class IndexedBinaryHeap {
    private final int[] heap;      // heap[i] = element id
    private final int[] pos;       // pos[id] = index in heap, -1 if absent
    private final long[] key;      // key[id]
    private int size;

    public IndexedBinaryHeap(int capacity) {
        heap = new int[capacity];
        pos = new int[capacity];
        key = new long[capacity];
        Arrays.fill(pos, -1);
    }

    public int size() { return size; }

    public void insert(int id, long k) {
        if (pos[id] != -1) throw new IllegalArgumentException("id already present: " + id);
        key[id] = k;
        heap[size] = id;
        pos[id] = size;
        siftUp(size++);
    }

    public void decreaseKey(int id, long k) {
        if (pos[id] == -1) throw new IllegalArgumentException("id not present: " + id);
        if (k > key[id]) throw new IllegalArgumentException("new key is greater than current key");
        key[id] = k;
        siftUp(pos[id]);
    }

    public int extractMin() {
        if (size == 0) throw new NoSuchElementException("heap is empty");
        int top = heap[0];
        swap(0, --size);
        pos[top] = -1;
        siftDown(0);
        return top;
    }

    public long keyOf(int id) { return key[id]; }

    private void siftUp(int i) {
        while (i > 0) {
            int p = (i - 1) / 2;
            if (key[heap[i]] >= key[heap[p]]) break;
            swap(i, p);
            i = p;
        }
    }

    private void siftDown(int i) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < size && key[heap[l]] < key[heap[m]]) m = l;
            if (r < size && key[heap[r]] < key[heap[m]]) m = r;
            if (m == i) return;
            swap(i, m);
            i = m;
        }
    }

    private void swap(int i, int j) {
        int t = heap[i];
        heap[i] = heap[j];
        heap[j] = t;
        pos[heap[i]] = i;
        pos[heap[j]] = j;
    }
}
