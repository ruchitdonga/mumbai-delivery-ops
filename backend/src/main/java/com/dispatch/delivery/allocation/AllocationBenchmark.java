package com.dispatch.delivery.allocation;

import com.dispatch.delivery.dispatch.Batcher.Batch;
import com.dispatch.delivery.geo.TrafficModel;
import com.dispatch.delivery.model.Live.Order;
import com.dispatch.delivery.model.Live.Rider;
import com.dispatch.delivery.model.Live.Store;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Synthetic allocation benchmark (fixed seed): n riders and n single-order jobs scattered over the western suburbs,
 * solved by Edmonds-Karp, Dinic and min-cost max-flow. Reports time and assignment quality for each.
 */
public final class AllocationBenchmark {
    private AllocationBenchmark() {}

    public record Row(int n, String algorithm, int assigned, double totalEtaMin, double totalLateMin, int lateOrders, double millis) {}

    public static List<Row> run(int[] sizes) {
        List<Row> rows = new ArrayList<>();
        TrafficModel traffic = new TrafficModel();
        LocalDateTime now = LocalDateTime.of(2026, 1, 14, 13, 0); // a normal weekday afternoon
        for (int n : sizes) {
            Random rnd = new Random(17 + n);
            List<Store> stores = new ArrayList<>();
            for (int s = 0; s < 6; s++) stores.add(new Store(s + 1, "S" + s, "Store " + s, 1, 19.11 + 0.025 * s, 72.84 + 0.02 * (s % 2), 80, true));
            List<Rider> riders = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Store home = stores.get(rnd.nextInt(stores.size()));
                riders.add(new Rider(i + 1, "R" + i, "R" + i, home.id, 2, home.lat + (rnd.nextDouble() - 0.5) * 0.03, home.lng + (rnd.nextDouble() - 0.5) * 0.03));
            }
            List<Batch> batches = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                Store s = stores.get(rnd.nextInt(stores.size()));
                Order o = new Order(j + 1, 1, 1, s.id, s.lat + (rnd.nextDouble() - 0.5) * 0.05, s.lng + (rnd.nextDouble() - 0.5) * 0.05,
                        300, 2, rnd.nextBoolean(), List.of(), now.minusMinutes(rnd.nextInt(10)), now.plusMinutes(8 + rnd.nextInt(20)), j);
                batches.add(new Batch("J" + j, s, List.of(o), 0));
            }
            RiderAllocator.compare(batches, riders, traffic, now); // warm-up
            for (RiderAllocator.Comparison c : RiderAllocator.compare(batches, riders, traffic, now)) {
                rows.add(new Row(n, c.algorithm(), c.assigned(), c.totalEtaMin(), c.totalLateMin(), c.predictedLateOrders(), c.micros() / 1000.0));
            }
        }
        return rows;
    }
}
