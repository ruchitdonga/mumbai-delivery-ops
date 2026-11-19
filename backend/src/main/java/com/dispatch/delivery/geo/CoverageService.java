package com.dispatch.delivery.geo;

import com.dispatch.algo.Geo;
import com.dispatch.delivery.model.Live.Store;
import com.dispatch.service.RoadRouter;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic delivery radius. For each active dark store, walk outward along 24 bearings in 250 m steps, adding
 * travel time at the local (traffic- and disruption-adjusted) speed, until the travel budget runs out or a road
 * closure blocks the way. The end points form the store's serviceable polygon.
 *
 * Road network: per-bearing road/straight-line ratios come from one OSRM table request per store (3 km ring),
 * so directions that cross the railway or a creek get a longer effective distance. If OSRM is unreachable,
 * the default 1.35 detour is used and the store is retried on the next recompute.
 */
@Service
public class CoverageService {

    public static final int BEARINGS = 24;
    public static final double STEP_KM = 0.25, MAX_KM = 7.0;
    /** Travel minutes a delivery may take after packing, inside the 20-minute promise. */
    public static final double TRAVEL_BUDGET_MIN = 12.0;

    private final TrafficModel traffic;
    private final RoadRouter router;
    private final Map<Integer, double[]> detours = new ConcurrentHashMap<>();

    public CoverageService(TrafficModel traffic, RoadRouter router) {
        this.traffic = traffic;
        this.router = router;
    }

    public void recompute(Collection<Store> stores, LocalDateTime now) {
        for (Store s : stores) s.coverage = s.active ? polygon(s, now) : new double[0][];
    }

    double[][] polygon(Store s, LocalDateTime now) {
        double[] detour = detours.get(s.id);
        if (detour == null) detour = measureDetours(s);
        double hour = traffic.hourFactor(now);
        double[][] ring = new double[BEARINGS][];
        for (int b = 0; b < BEARINGS; b++) {
            double minutes = 0;
            double[] last = {s.lat, s.lng};
            for (double km = STEP_KM; km <= MAX_KM + 1e-9; km += STEP_KM) {
                double[] p = destination(s.lat, s.lng, b * 360.0 / BEARINGS, km);
                if (traffic.blocked(p[0], p[1])) break;
                double kmh = 24.0 * hour * traffic.localFactor(p[0], p[1]);
                minutes += STEP_KM * detour[b] / kmh * 60;
                if (minutes > TRAVEL_BUDGET_MIN) break;
                last = p;
            }
            ring[b] = last;
        }
        return ring;
    }

    /** Road km / straight km towards each bearing at 3 km, from OSRM; 1.35 everywhere if unreachable. */
    private double[] measureDetours(Store s) {
        double[] out = new double[BEARINGS];
        Arrays.fill(out, TrafficModel.DEFAULT_DETOUR);
        List<double[]> ring = new ArrayList<>();
        for (int b = 0; b < BEARINGS; b++) ring.add(destination(s.lat, s.lng, b * 360.0 / BEARINGS, 3.0));
        double[] from = {s.lat, s.lng};
        if (!router.prefetch(List.of(from), ring)) return out; // not cached: measured again next recompute
        for (int b = 0; b < BEARINGS; b++) {
            Double road = router.cached(from, ring.get(b));
            if (road != null && Double.isFinite(road)) out[b] = Math.max(1.05, Math.min(2.5, road / 3.0));
        }
        detours.put(s.id, out);
        return out;
    }

    public boolean osrmDetoursKnown(int storeId) {
        return detours.containsKey(storeId);
    }

    /** Ray-casting point-in-polygon on [lat, lng] rings. */
    public static boolean contains(double[][] poly, double lat, double lng) {
        if (poly.length < 3) return false;
        boolean in = false;
        for (int i = 0, j = poly.length - 1; i < poly.length; j = i++) {
            double yi = poly[i][0], xi = poly[i][1], yj = poly[j][0], xj = poly[j][1];
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    /** Point `km` away from (lat, lng) along a compass bearing (spherical earth). */
    public static double[] destination(double lat, double lng, double bearingDeg, double km) {
        double d = km / 6371.0, br = Math.toRadians(bearingDeg), la = Math.toRadians(lat), lo = Math.toRadians(lng);
        double la2 = Math.asin(Math.sin(la) * Math.cos(d) + Math.cos(la) * Math.sin(d) * Math.cos(br));
        double lo2 = lo + Math.atan2(Math.sin(br) * Math.sin(d) * Math.cos(la), Math.cos(d) - Math.sin(la) * Math.sin(la2));
        return new double[]{Math.toDegrees(la2), Math.toDegrees(lo2)};
    }

    /** Km from a store to the edge of its polygon along the bearing nearest the given point (for display). */
    public static double reachKm(Store s, double bearingDeg) {
        if (s.coverage.length == 0) return 0;
        int b = (int) Math.round(((bearingDeg % 360) + 360) % 360 / (360.0 / BEARINGS)) % BEARINGS;
        return Geo.km(s.lat, s.lng, s.coverage[b][0], s.coverage[b][1]);
    }
}
