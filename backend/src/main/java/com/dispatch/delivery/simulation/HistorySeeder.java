package com.dispatch.delivery.simulation;

import com.dispatch.delivery.model.Live.Customer;
import com.dispatch.delivery.model.Live.Rider;
import com.dispatch.delivery.model.Live.Scenario;
import com.dispatch.delivery.model.Live.Store;
import com.dispatch.delivery.orders.OrderRepository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Generates past days of SIMULATED orders (same demand curve, basket and serviceability rules as the live engine)
 * so forecasting, AOV, weekday/weekend and locality analytics have history on first start.
 * History is written in bulk per day; it does not replay riders minute by minute (delivery times are sampled
 * from the traffic model instead), which keeps first start to seconds.
 */
final class HistorySeeder {

    private final SimulationEngine engine;
    private final OrderRepository orders;
    private final Random rnd;

    HistorySeeder(SimulationEngine engine, OrderRepository orders, Random rnd) {
        this.engine = engine;
        this.orders = orders;
        this.rnd = rnd;
    }

    int seed(LocalDateTime from, LocalDateTime to) {
        Map<Integer, List<Rider>> ridersByStore = new HashMap<>();
        for (Rider r : engine.riders()) ridersByStore.computeIfAbsent(r.homeStoreId, k -> new ArrayList<>()).add(r);
        int total = 0;
        for (LocalDateTime dayStart = from; dayStart.isBefore(to); dayStart = dayStart.plusDays(1)) {
            List<Object[]> os = new ArrayList<>(), items = new ArrayList<>(), events = new ArrayList<>(), assigns = new ArrayList<>(), demand = new ArrayList<>();
            for (int minute = 0; minute < 1440; minute++) {
                LocalDateTime t = dayStart.plusMinutes(minute);
                double rate = engine.rate(t, Scenario.NORMAL);
                for (int i = SimulationEngine.poisson(rate, rnd); i > 0; i--) {
                    Customer c = engine.randomCustomer();
                    Store s = engine.serviceableStore(c.lat(), c.lng());
                    if (s == null) {
                        demand.add(new Object[]{"UNSERVICEABLE", c.lat(), c.lng(), c.localityId(), null, null, ts(t)});
                        continue;
                    }
                    List<int[]> basket = engine.basket(rnd);
                    if (rnd.nextDouble() < 0.06) { // historical stock-outs: drop one item
                        int[] gone = basket.remove(0);
                        demand.add(new Object[]{"OUT_OF_STOCK", c.lat(), c.lng(), c.localityId(), gone[0], null, ts(t)});
                        if (basket.isEmpty()) continue;
                    }
                    long id = orders.newOrderId();
                    int count = basket.stream().mapToInt(it -> it[1]).sum();
                    double sum = basket.stream().mapToDouble(it -> it[1] * it[2] / 100.0).sum();
                    LocalDateTime promised = t.plusMinutes(count <= 4 ? 20 : 30);
                    boolean cancelled = rnd.nextDouble() < 0.04;
                    LocalDateTime ready = null, picked = null, delivered = null, cancelledAt = null;
                    if (cancelled) {
                        cancelledAt = t.plusMinutes(1 + rnd.nextInt(10));
                        events.add(new Object[]{id, "CANCELLED", ts(cancelledAt), "CUSTOMER_CANCELLED"});
                    } else {
                        ready = t.plusSeconds((long) ((3 + rnd.nextDouble() * 4) * 60));
                        picked = ready.plusSeconds((long) (rnd.nextDouble() * 5 * 60));
                        double travel = engine.traffic().travelMin(s.lat, s.lng, c.lat(), c.lng(), t) * (0.8 + rnd.nextDouble() * 0.6);
                        delivered = picked.plusSeconds((long) ((travel + engine.traffic().dropMin(c.highRise())) * 60));
                        events.add(new Object[]{id, "DELIVERED", ts(delivered), delivered.isAfter(promised) ? "late" : "on time"});
                        List<Rider> rs = ridersByStore.get(s.id);
                        if (rs != null && !rs.isEmpty()) {
                            Rider r = rs.get(rnd.nextInt(rs.size()));
                            assigns.add(new Object[]{id, r.id, "H" + id, ts(ready.minusMinutes(1)), Math.round(travel * 10) / 10.0, "HISTORY", false});
                        }
                    }
                    events.add(new Object[]{id, "PLACED", ts(t), "store " + s.id});
                    os.add(new Object[]{id, c.id(), s.id, c.localityId(), cancelled ? "CANCELLED" : "DELIVERED", Math.round(sum * 100) / 100.0, count,
                            c.lat(), c.lng(), ts(t), ts(promised), ts(ready), ts(picked), ts(delivered), ts(cancelledAt),
                            cancelled ? "CUSTOMER_CANCELLED" : null, "NORMAL"});
                    for (int[] it : basket) items.add(new Object[]{id, it[0], it[1], it[2] / 100.0});
                }
                for (int i = SimulationEngine.poisson(rate * 1.5, rnd); i > 0; i--) {
                    Customer c = engine.randomCustomer();
                    demand.add(new Object[]{"SEARCH", c.lat(), c.lng(), c.localityId(), null, null, ts(t)});
                }
                for (int i = SimulationEngine.poisson(rate * 0.25, rnd); i > 0; i--) {
                    Customer c = engine.randomCustomer();
                    demand.add(new Object[]{"ABANDONED_CART", c.lat(), c.lng(), c.localityId(), null, null, ts(t)});
                }
            }
            orders.insertHistory(os, items, events, assigns);
            orders.insertDemand(demand);
            total += os.size();
        }
        return total;
    }

    private static Timestamp ts(LocalDateTime t) { return OrderRepository.ts(t); }
}
