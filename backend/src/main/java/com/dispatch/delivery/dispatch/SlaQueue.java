package com.dispatch.delivery.dispatch;

import com.dispatch.algo.FibonacciHeap;
import com.dispatch.delivery.model.Live.Order;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Dispatch priority queue on the Fibonacci heap.
 *
 * Key (smaller = dispatched first) = (deadline minute - urgency bonus) * 1e6 + arrival sequence:
 *  - SLA urgency: earlier promised deadline first;
 *  - FIFO tie-break: equal effective deadlines go in arrival order (sequence in the low digits);
 *  - waiting-time aging: every 2 minutes waited moves an order 1 minute earlier (decreaseKey);
 *  - escalation: SLA risk or an admin override moves it 10 minutes earlier (decreaseKey);
 *  - cancellation: remove() pulls the node out immediately;
 *  - stale entries: an order whose status changed outside the queue (e.g. manually assigned) is skipped on poll.
 * Keys only ever decrease while an order waits, which is exactly what decreaseKey supports in O(1) amortized.
 */
public class SlaQueue {

    static final long SLOT = 1_000_000L;

    /** SLA = earliest promised deadline first (default); FIFO = strictly in the order received (escalation still jumps). */
    public enum Policy { SLA, FIFO }

    private volatile Policy policy = Policy.SLA;
    private FibonacciHeap<Order> heap = new FibonacciHeap<>();
    private int staleSkipped;

    public void clear() {
        heap = new FibonacciHeap<>();
        staleSkipped = 0;
    }

    public Policy policy() { return policy; }

    /** Switching policy re-keys every queued order: rebuild the heap from the given waiting orders. */
    public void setPolicy(Policy p, Collection<Order> waiting) {
        policy = p;
        clear();
        for (Order o : waiting) { o.node = null; add(o); }
    }

    public long key(Order o) {
        if (policy == Policy.FIFO) {
            long received = o.placedAt.toEpochSecond(ZoneOffset.UTC) / 60;
            return (received - o.escalationMinutes) * SLOT + (o.seq % SLOT); // aging is equal for all, so ignored
        }
        long deadline = o.promisedAt.toEpochSecond(ZoneOffset.UTC) / 60;
        return (deadline - o.agingMinutes - o.escalationMinutes) * SLOT + (o.seq % SLOT);
    }

    public void add(Order o) {
        if (o.node != null && o.node.inHeap()) throw new IllegalStateException("order " + o.id + " already queued");
        o.node = heap.insert(key(o), o);
    }

    public void remove(Order o) {
        if (o.node != null && o.node.inHeap()) heap.remove(o.node);
        o.node = null;
    }

    /** Admin override / SLA risk: move the order `minutes` closer to the front. */
    public void escalate(Order o, int minutes) {
        if (minutes <= 0) throw new IllegalArgumentException("escalation must be positive");
        o.escalationMinutes += minutes;
        if (o.node != null && o.node.inHeap()) heap.decreaseKey(o.node, key(o));
    }

    /** Waiting-time aging for every queued order; O(1) amortized per order thanks to decreaseKey. */
    public void age(Collection<Order> waiting, LocalDateTime now) {
        for (Order o : waiting) {
            if (o.node == null || !o.node.inHeap()) continue;
            int aging = (int) (java.time.Duration.between(o.placedAt, now).toMinutes() / 2);
            if (aging > o.agingMinutes) {
                o.agingMinutes = aging;
                heap.decreaseKey(o.node, key(o));
            }
        }
    }

    /** Pops up to k orders that still need a rider, most urgent first. */
    public List<Order> poll(int k) {
        List<Order> out = new ArrayList<>(k);
        while (out.size() < k && !heap.isEmpty()) {
            FibonacciHeap.Node<Order> n = heap.extractMin();
            Order o = n.value();
            if (o.node != n || !o.waitingForRider() || o.riderId != null) {
                staleSkipped++;
                continue;
            }
            o.node = null;
            out.add(o);
        }
        return out;
    }

    public int size() { return heap.size(); }
    public int staleSkipped() { return staleSkipped; }
}
