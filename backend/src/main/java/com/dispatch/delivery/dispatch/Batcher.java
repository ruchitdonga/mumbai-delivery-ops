package com.dispatch.delivery.dispatch;

import com.dispatch.algo.Geo;
import com.dispatch.delivery.geo.TrafficModel;
import com.dispatch.delivery.model.Live.Order;
import com.dispatch.delivery.model.Live.Store;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Smart batching: one rider carries several orders from the same dark store when the drops are close,
 * the extra detour is small, and every order in the batch still meets its SLA.
 *
 * Greedy in priority order: the most urgent unbatched order seeds a batch, then its nearest compatible
 * neighbours are tried one by one. Drop sequence = nearest-neighbour from the store.
 * O(n^2 * b) for n candidate orders and batch size b (n is the dispatch window, tens of orders).
 */
public final class Batcher {
    private Batcher() {}

    public static final double MAX_NEIGHBOUR_KM = 1.5;   // drops further apart are never batched
    public static final double MAX_DETOUR = 0.4;         // batched route <= 1.4 x the longest single trip (+0.5 km)

    public record Batch(String key, Store store, List<Order> drops, double routeKm) {}

    public static List<Batch> build(List<Order> urgent, Map<Integer, Store> stores, TrafficModel traffic,
                                    LocalDateTime now, int maxSize) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        List<Batch> out = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        for (Order seed : urgent) {
            if (!used.add(seed.id)) continue;
            Store store = stores.get(seed.storeId);
            List<Order> group = new ArrayList<>(List.of(seed));
            List<Order> near = urgent.stream()
                    .filter(o -> !used.contains(o.id) && o.storeId == seed.storeId
                            && Geo.km(o.lat, o.lng, seed.lat, seed.lng) <= MAX_NEIGHBOUR_KM)
                    .sorted(Comparator.comparingDouble(o -> Geo.km(o.lat, o.lng, seed.lat, seed.lng)))
                    .toList();
            for (Order c : near) {
                if (group.size() >= maxSize) break;
                List<Order> trial = new ArrayList<>(group);
                trial.add(c);
                List<Order> seq = sequence(store, trial);
                if (withinDetour(store, seq) && meetsSla(store, seq, traffic, now)) {
                    group = trial;
                    used.add(c.id);
                }
            }
            List<Order> seq = sequence(store, group);
            out.add(new Batch("B" + seed.id, store, seq, routeKm(store, seq)));
        }
        return out;
    }

    /** Nearest-neighbour drop order starting at the store. */
    public static List<Order> sequence(Store store, List<Order> orders) {
        List<Order> left = new ArrayList<>(orders), seq = new ArrayList<>();
        double lat = store.lat, lng = store.lng;
        while (!left.isEmpty()) {
            final double fLat = lat, fLng = lng;
            Order next = Collections.min(left, Comparator.comparingDouble(o -> Geo.km(fLat, fLng, o.lat, o.lng)));
            left.remove(next);
            seq.add(next);
            lat = next.lat;
            lng = next.lng;
        }
        return seq;
    }

    public static double routeKm(Store store, List<Order> seq) {
        double km = 0, lat = store.lat, lng = store.lng;
        for (Order o : seq) {
            km += Geo.km(lat, lng, o.lat, o.lng);
            lat = o.lat;
            lng = o.lng;
        }
        return km;
    }

    static boolean withinDetour(Store store, List<Order> seq) {
        double longestSingle = seq.stream().mapToDouble(o -> Geo.km(store.lat, store.lng, o.lat, o.lng)).max().orElse(0);
        return routeKm(store, seq) <= longestSingle * (1 + MAX_DETOUR) + 0.5;
    }

    /** Leaving the store when the slowest order is packed, does every drop arrive before its promise? */
    static boolean meetsSla(Store store, List<Order> seq, TrafficModel traffic, LocalDateTime now) {
        LocalDateTime t = now;
        for (Order o : seq) if (o.prepDoneAt != null && o.prepDoneAt.isAfter(t)) t = o.prepDoneAt;
        double lat = store.lat, lng = store.lng;
        for (Order o : seq) {
            t = t.plusSeconds((long) ((traffic.travelMin(lat, lng, o.lat, o.lng, now) + traffic.dropMin(o.highRise)) * 60));
            if (t.isAfter(o.promisedAt)) return false;
            lat = o.lat;
            lng = o.lng;
        }
        return true;
    }
}
