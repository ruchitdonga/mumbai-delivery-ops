package com.dispatch.delivery;

import com.dispatch.delivery.dispatch.SlaQueue;
import com.dispatch.delivery.model.Live.Order;
import com.dispatch.delivery.model.Live.OrderStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlaQueueTest {

    static final LocalDateTime T0 = LocalDateTime.of(2026, 1, 14, 12, 0);

    static Order order(long id, int placedOffset, int promiseMin, long seq) {
        Order o = new Order(id, 1, 1, 1, 19.1, 72.85, 100, 1, false, List.of(), T0.plusMinutes(placedOffset),
                T0.plusMinutes(placedOffset + promiseMin), seq);
        o.status = OrderStatus.PREPARING;
        return o;
    }

    @Test
    void earlierDeadlineFirstThenFifoOnTies() {
        SlaQueue q = new SlaQueue();
        Order late = order(1, 0, 30, 1), tieA = order(2, 0, 20, 2), tieB = order(3, 0, 20, 3);
        q.add(late);
        q.add(tieB);
        q.add(tieA);
        assertEquals(List.of(2L, 3L, 1L), q.poll(3).stream().map(o -> o.id).toList());
    }

    @Test
    void fifoPolicyServesInOrderReceivedAndRekeysOnSwitch() {
        SlaQueue q = new SlaQueue();
        Order first = order(1, 0, 30, 1), second = order(2, 2, 15, 2); // second is due sooner but arrived later
        q.add(first);
        q.add(second);
        q.setPolicy(SlaQueue.Policy.FIFO, List.of(first, second));
        assertEquals(List.of(1L, 2L), q.poll(2).stream().map(o -> o.id).toList());
        q.setPolicy(SlaQueue.Policy.SLA, List.of(first, second));
        assertEquals(List.of(2L, 1L), q.poll(2).stream().map(o -> o.id).toList());
    }

    @Test
    void escalationUsesDecreaseKeyToJumpAhead() {
        SlaQueue q = new SlaQueue();
        Order a = order(1, 0, 20, 1), b = order(2, 0, 30, 2);
        q.add(a);
        q.add(b);
        q.escalate(b, 15); // effective deadline 15 < 20
        assertEquals(2L, q.poll(1).get(0).id);
        assertThrows(IllegalArgumentException.class, () -> q.escalate(a, 0));
    }

    @Test
    void agingLetsALongWaitingOrderOvertakeANewerTighterOne() {
        SlaQueue q = new SlaQueue();
        Order old = order(1, 0, 30, 1);    // deadline 12:30
        Order fresh = order(2, 25, 4, 2);  // placed 12:25, deadline 12:29
        q.add(old);
        q.add(fresh);
        q.age(List.of(old, fresh), T0.plusMinutes(26)); // old waited 26 min -> 13 min of aging -> effective 12:17
        assertEquals(1L, q.poll(1).get(0).id);
    }

    @Test
    void cancellationRemovesImmediately() { // edge: remove from the middle of a consolidated heap
        SlaQueue q = new SlaQueue();
        Order[] os = new Order[20];
        for (int i = 0; i < 20; i++) q.add(os[i] = order(i, 0, 20 + i, i));
        q.poll(1); // forces consolidation (os[0] out)
        q.remove(os[7]);
        q.remove(os[7]); // second removal is a no-op, not an error
        assertEquals(18, q.size());
        assertFalse(q.poll(20).stream().anyMatch(o -> o.id == 7));
    }

    @Test
    void staleEntriesAreSkippedOnPoll() {
        SlaQueue q = new SlaQueue();
        Order a = order(1, 0, 10, 1), b = order(2, 0, 20, 2);
        q.add(a);
        q.add(b);
        a.riderId = 99; // assigned manually outside the queue
        List<Order> got = q.poll(2);
        assertEquals(List.of(2L), got.stream().map(o -> o.id).toList());
        assertEquals(1, q.staleSkipped());
        assertThrows(IllegalStateException.class, () -> { Order c = order(3, 0, 5, 3); q.add(c); q.add(c); });
    }
}
