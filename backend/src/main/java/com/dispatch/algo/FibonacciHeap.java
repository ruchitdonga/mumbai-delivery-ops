package com.dispatch.algo;

import java.util.NoSuchElementException;

/**
 * Min Fibonacci heap (Fredman & Tarjan), written from scratch.
 *
 * Amortized costs: insert O(1), min O(1), decreaseKey O(1), extractMin O(log n).
 * Space: O(n).
 *
 * The dispatcher uses decreaseKey whenever a waiting patient's condition is escalated,
 * which is exactly the operation where a Fibonacci heap beats a binary heap.
 */
public class FibonacciHeap<T> {

    /** Handle returned by insert; keep it to call decreaseKey later. */
    public static final class Node<T> {
        private long key;
        private final T value;
        private Node<T> parent, child, left, right;
        private int degree;
        private boolean marked;
        private boolean inHeap = true;

        private Node(long key, T value) {
            this.key = key;
            this.value = value;
            this.left = this;
            this.right = this;
        }

        public long key() { return key; }
        public T value() { return value; }
        public boolean inHeap() { return inHeap; }
    }

    private Node<T> min;
    private int size;

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }

    public Node<T> insert(long key, T value) {
        Node<T> n = new Node<>(key, value);
        min = mergeIntoList(min, n);
        if (n.key < min.key) min = n;
        size++;
        return n;
    }

    public Node<T> peekMin() {
        if (min == null) throw new NoSuchElementException("heap is empty");
        return min;
    }

    public Node<T> extractMin() {
        if (min == null) throw new NoSuchElementException("heap is empty");
        Node<T> z = min;

        // Move all children of z to the root list: clear their parent pointers, then splice the
        // whole circular child list next to z in O(1).
        if (z.child != null) {
            Node<T> c = z.child;
            do { c.parent = null; c.marked = false; c = c.right; } while (c != z.child);
            Node<T> zRight = z.right, cLast = c.left;
            z.right = c;
            c.left = z;
            cLast.right = zRight;
            zRight.left = cLast;
            z.child = null;
        }

        if (z.right == z) {
            min = null;
        } else {
            min = z.right;
            removeFromList(z);
            consolidate();
        }
        size--;
        z.inHeap = false;
        z.left = z.right = z;
        z.degree = 0;
        return z;
    }

    /** Lowers a node's key. Throws if newKey is larger (that would break heap order). */
    public void decreaseKey(Node<T> x, long newKey) {
        if (!x.inHeap) throw new IllegalArgumentException("node is no longer in the heap");
        if (newKey > x.key) throw new IllegalArgumentException("new key " + newKey + " is greater than current key " + x.key);
        x.key = newKey;
        Node<T> y = x.parent;
        if (y != null && x.key < y.key) {
            cut(x, y);
            cascadingCut(y);
        }
        if (x.key < min.key) min = x;
    }

    /**
     * Removes any node (order cancelled while waiting). Cuts it to the root list, makes it the min, then
     * extracts it: O(log n) amortized, and never touches its key, so ties at Long.MIN_VALUE cannot misfire.
     */
    public void remove(Node<T> x) {
        if (!x.inHeap) throw new IllegalArgumentException("node is no longer in the heap");
        Node<T> y = x.parent;
        if (y != null) {
            cut(x, y);
            cascadingCut(y);
        }
        min = x;
        extractMin();
    }

    // ---- internals ----

    // Max degree is O(log_phi n); 64 slots is plenty for any int-sized heap. Reused to avoid allocation.
    @SuppressWarnings("unchecked")
    private final Node<T>[] byDegree = new Node[64];

    private void consolidate() {
        int roots = 0;
        Node<T> w = min;
        do { roots++; w = w.right; } while (w != min);

        while (roots-- > 0) {
            Node<T> x = w;
            w = w.right; // capture before x may be linked under another root
            int d = x.degree;
            while (byDegree[d] != null) {
                Node<T> y = byDegree[d];
                if (y.key < x.key) { Node<T> t = x; x = y; y = t; }
                link(y, x);
                byDegree[d] = null;
                d++;
            }
            byDegree[d] = x;
        }

        min = null;
        for (int i = 0; i < byDegree.length; i++) {
            Node<T> n = byDegree[i];
            if (n == null) continue;
            byDegree[i] = null;
            n.left = n.right = n;
            min = mergeIntoList(min, n);
            if (n.key < min.key) min = n;
        }
    }

    /** Makes y a child of x. */
    private void link(Node<T> y, Node<T> x) {
        removeFromList(y);
        y.left = y.right = y;
        y.parent = x;
        x.child = mergeIntoList(x.child, y);
        x.degree++;
        y.marked = false;
    }

    private void cut(Node<T> x, Node<T> y) {
        if (x.right == x) {
            y.child = null;
        } else {
            if (y.child == x) y.child = x.right;
            removeFromList(x);
        }
        y.degree--;
        x.left = x.right = x;
        x.parent = null;
        x.marked = false;
        mergeIntoList(min, x);
    }

    private void cascadingCut(Node<T> y) {
        Node<T> z = y.parent;
        if (z != null) {
            if (!y.marked) {
                y.marked = true;
            } else {
                cut(y, z);
                cascadingCut(z);
            }
        }
    }

    /** Splices single node n into the circular list containing head; returns the list head. */
    private Node<T> mergeIntoList(Node<T> head, Node<T> n) {
        if (head == null) {
            n.left = n.right = n;
            return n;
        }
        n.right = head.right;
        n.left = head;
        head.right.left = n;
        head.right = n;
        return head;
    }

    private void removeFromList(Node<T> n) {
        n.left.right = n.right;
        n.right.left = n.left;
    }
}
