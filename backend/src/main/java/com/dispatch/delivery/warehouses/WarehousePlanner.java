package com.dispatch.delivery.warehouses;

import com.dispatch.delivery.geo.TrafficModel;
import com.dispatch.delivery.model.Live.Locality;
import com.dispatch.delivery.orders.OrderRepository;
import com.dispatch.delivery.simulation.SimulationEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Dark-store count and placement as a capacitated, weighted SET COVER problem (NP-hard).
 *
 * Universe = demand cells (last 7 days of orders + unserviceable requests, snapped to a ~450 m PostGIS grid,
 * weighted by peak orders/hour). A candidate site "covers" a cell when the ride takes <= the travel budget under
 * normal midday traffic. Greedy: repeatedly open the site that covers the most still-uncovered peak demand
 * (capped by one store's hourly capacity, nearest cells first) until the coverage target is met.
 * Greedy set cover is an H(n) ~ ln n + 1 approximation of the minimum number of stores; it is NOT guaranteed optimal.
 * Travel times are computed once into an n x n matrix: O(n^2) setup, O(k n^2) for k chosen stores.
 */
@Service
public class WarehousePlanner {

    public record Cell(double lat, double lng, double peakPerHour) {}
    public record Site(int rank, double lat, double lng, String locality, double coveredPeakPerHour, double cumulativeCoveragePct) {}

    static final double PEAK_SHARE = 1.8 / 22.0; // busiest hour's share of a day's orders (from the demand curve)

    private final JdbcTemplate jdbc;
    private final SimulationEngine engine;
    // Its own model: planning assumes a normal weekday midday with no disruptions, whatever the live scenario is,
    // and straight-line x 1.35 keeps the n x n travel matrix from flooding the OSRM queue.
    private final TrafficModel traffic = new TrafficModel();

    public WarehousePlanner(JdbcTemplate jdbc, SimulationEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    public Map<String, Object> plan(double budgetMin, double targetPct, int capacityPerHour) {
        if (budgetMin < 5 || budgetMin > 30) throw new IllegalArgumentException("travel budget must be 5..30 minutes");
        if (targetPct < 50 || targetPct > 100) throw new IllegalArgumentException("coverage target must be 50..100 %");
        if (capacityPerHour < 20 || capacityPerHour > 500) throw new IllegalArgumentException("store capacity must be 20..500 orders/hour");
        LocalDateTime now = engine.now();
        List<Cell> cells = new ArrayList<>();
        jdbc.query("SELECT ST_Y(c), ST_X(c), SUM(n) FROM ("
                        + " SELECT ST_SnapToGrid(geo::geometry, 0.004) AS c, COUNT(*) AS n FROM orders WHERE placed_at >= ? GROUP BY c"
                        + " UNION ALL SELECT ST_SnapToGrid(geo::geometry, 0.004), COUNT(*) FROM demand_event WHERE type = 'UNSERVICEABLE' AND at >= ? GROUP BY 1"
                        + ") x GROUP BY c",
                rs -> { cells.add(new Cell(rs.getDouble(1), rs.getDouble(2), rs.getDouble(3) / 7.0 * PEAK_SHARE)); },
                OrderRepository.ts(now.minusDays(7)), OrderRepository.ts(now.minusDays(7)));
        LocalDateTime midday = now.toLocalDate().atTime(13, 0);
        List<double[]> current = engine.stores().values().stream().filter(s -> s.active).map(s -> new double[]{s.lat, s.lng}).toList();

        List<Site> sites = greedy(cells, budgetMin, targetPct, capacityPerHour, (a, b) -> traffic.travelMin(a[0], a[1], b[0], b[1], midday));
        double total = cells.stream().mapToDouble(Cell::peakPerHour).sum();
        double currentCovered = coverExisting(cells, current, budgetMin, capacityPerHour, midday);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recommendedStores", sites.size());
        out.put("sites", sites);
        out.put("demandCells", cells.size());
        out.put("peakOrdersPerHour", Math.round(total * 10) / 10.0);
        out.put("currentStores", current.size());
        out.put("currentCoveragePct", total == 0 ? 0 : Math.round(1000 * currentCovered / total) / 10.0);
        out.put("method", "Greedy weighted set cover with store capacity; ln(n)+1 approximation, not exact");
        return out;
    }

    public interface Travel { double minutes(double[] from, double[] to); }

    /** Greedy capacitated set cover; candidate sites = the demand cells themselves. */
    public List<Site> greedy(List<Cell> cells, double budgetMin, double targetPct, int capacity, Travel travel) {
        int n = cells.size();
        double total = cells.stream().mapToDouble(Cell::peakPerHour).sum();
        // reach[i] = cells within budget of site i, nearest first
        int[][] reach = new int[n][];
        for (int i = 0; i < n; i++) {
            double[] from = {cells.get(i).lat(), cells.get(i).lng()};
            List<double[]> r = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                double m = travel.minutes(from, new double[]{cells.get(j).lat(), cells.get(j).lng()});
                if (m <= budgetMin) r.add(new double[]{j, m});
            }
            r.sort(Comparator.comparingDouble(x -> x[1]));
            reach[i] = r.stream().mapToInt(x -> (int) x[0]).toArray();
        }
        boolean[] done = new boolean[n];
        List<Site> out = new ArrayList<>();
        double covered = 0;
        while (total > 0 && covered / total * 100 < targetPct - 1e-9 && out.size() < 15) {
            int best = -1;
            double bestGain = 0;
            for (int i = 0; i < n; i++) {
                double g = take(reach[i], cells, done, capacity, false);
                if (g > bestGain + 1e-9) { bestGain = g; best = i; }
            }
            if (best < 0) break;
            covered += take(reach[best], cells, done, capacity, true);
            Cell c = cells.get(best);
            out.add(new Site(out.size() + 1, c.lat(), c.lng(), localityName(c.lat(), c.lng()),
                    Math.round(bestGain * 10) / 10.0, Math.round(1000 * covered / total) / 10.0));
        }
        return out;
    }

    /** Uncovered peak demand a site can absorb: nearest cells first, until its hourly capacity is full. */
    private static double take(int[] reach, List<Cell> cells, boolean[] done, int capacity, boolean commit) {
        double left = capacity, got = 0;
        for (int j : reach) {
            if (done[j]) continue;
            double d = cells.get(j).peakPerHour();
            if (d > left) continue;
            left -= d;
            got += d;
            if (commit) done[j] = true;
        }
        return got;
    }

    private double coverExisting(List<Cell> cells, List<double[]> sites, double budgetMin, int capacity, LocalDateTime when) {
        boolean[] done = new boolean[cells.size()];
        double sum = 0;
        for (double[] s : sites) {
            List<double[]> r = new ArrayList<>();
            for (int j = 0; j < cells.size(); j++) {
                double m = traffic.travelMin(s[0], s[1], cells.get(j).lat(), cells.get(j).lng(), when);
                if (m <= budgetMin) r.add(new double[]{j, m});
            }
            r.sort(Comparator.comparingDouble(x -> x[1]));
            sum += take(r.stream().mapToInt(x -> (int) x[0]).toArray(), cells, done, capacity, true);
        }
        return sum;
    }

    private String localityName(double lat, double lng) {
        if (engine == null) return "";
        for (Locality l : engine.localities().values()) {
            if (lat >= l.minLat && lat <= l.maxLat && lng >= l.minLng && lng <= l.maxLng) return l.name;
        }
        return "outside mapped localities";
    }
}
