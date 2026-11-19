package com.dispatch.delivery.forecast;

import com.dispatch.delivery.orders.OrderRepository;
import com.dispatch.delivery.simulation.SimulationEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Rolling next-hour revenue forecast.
 *   baseline(h) = mean revenue in that clock hour on earlier days of the same type (weekday / weekend), up to 3 weeks
 *   trend       = revenue in the last 3 hours / baseline for those 3 hours, shrunk 40 % towards 1 and clamped to
 *                 [0.6, 1.6] (one noisy hour of ~50 orders must not swing the forecast)
 *   velocity    = revenue in the last 30 min x 2
 *   forecast    = 0.7 x baseline(next hour) x trend + 0.3 x velocity x baseline(next) / baseline(last hour)
 * Accuracy is back-tested on the last 72 whole hours with WAPE (sum |error| / sum actual): unlike MAPE it is not
 * blown up by quiet night hours with a handful of orders.
 */
@Service
public class RevenueForecastService {

    private final JdbcTemplate jdbc;
    private final SimulationEngine engine;

    public RevenueForecastService(JdbcTemplate jdbc, SimulationEngine engine) {
        this.jdbc = jdbc;
        this.engine = engine;
    }

    public Map<String, Object> forecast() {
        LocalDateTime now = engine.now();
        // Revenue per hour, divided by that hour's scenario demand factor, so peak / monsoon hours don't distort
        // the "normal day" baseline; forecasts are multiplied back by the scenario of the hour being predicted.
        Map<LocalDateTime, Double> hourly = new HashMap<>(), factor = new HashMap<>();
        jdbc.query("SELECT date_trunc('hour', placed_at), SUM(total), MODE() WITHIN GROUP (ORDER BY scenario) FROM orders "
                        + "WHERE status <> 'CANCELLED' AND placed_at >= ? GROUP BY 1",
                rs -> {
                    double f = SCENARIO_DEMAND.getOrDefault(rs.getString(3), 1.0);
                    LocalDateTime h = rs.getTimestamp(1).toLocalDateTime();
                    hourly.put(h, rs.getDouble(2) / f);
                    factor.put(h, f);
                }, OrderRepository.ts(now.minusDays(25)));
        double nowFactor = SCENARIO_DEMAND.get(engine.traffic().scenario().name());

        double last180 = revenue(now.minusMinutes(180), now) / nowFactor, last30 = revenue(now.minusMinutes(30), now) / nowFactor;
        double base180 = 0;
        for (int i = 1; i <= 3; i++) base180 += window(hourly, now.minusMinutes(60L * i), now);
        double baseLast = window(hourly, now.minusMinutes(60), now);
        LocalDateTime nextHour = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        double baseNext = baseline(hourly, nextHour, now);
        double trend = shrink(base180 <= 0 ? 1 : last180 / base180);
        double velocity = last30 * 2;
        double scaled = baseLast <= 0 ? velocity : velocity * baseNext / baseLast;
        double forecast = (0.7 * baseNext * trend + 0.3 * scaled) * nowFactor;

        List<Map<String, Object>> series = new ArrayList<>();
        double absErr = 0, actualSum = 0;
        int n = 0;
        LocalDateTime current = now.truncatedTo(ChronoUnit.HOURS);
        for (LocalDateTime h = current.minusHours(72); h.isBefore(current); h = h.plusHours(1)) {
            double f = factor.getOrDefault(h, 1.0);
            double actual = hourly.getOrDefault(h, 0.0) * f, act3 = 0, base3 = 0;
            for (int i = 1; i <= 3; i++) {
                act3 += hourly.getOrDefault(h.minusHours(i), 0.0);
                base3 += baseline(hourly, h.minusHours(i), h);
            }
            double pred = baseline(hourly, h, h) * shrink(base3 <= 0 ? 1 : act3 / base3) * f;
            absErr += Math.abs(pred - actual);
            actualSum += actual;
            n++;
            if (!h.isBefore(current.minusHours(24))) series.add(Map.of("hour", h.toString(), "actual", Math.round(actual), "predicted", Math.round(pred)));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", now.toString());
        out.put("nextHourStart", nextHour.toString());
        out.put("forecastNextHour", Math.round(forecast));
        out.put("components", Map.of("baselineNextHour", Math.round(baseNext), "trend", Math.round(trend * 100) / 100.0,
                "revenueLast3h", Math.round(last180), "baselineLast3h", Math.round(base180), "revenueLast30Min", Math.round(last30)));
        out.put("backtestWapePct", actualSum <= 0 ? null : Math.round(1000 * absErr / actualSum) / 10.0);
        out.put("backtestHours", n);
        out.put("series", series);
        return out;
    }

    /** Demand multipliers per scenario, matching the simulation's order-rate factors. */
    static final Map<String, Double> SCENARIO_DEMAND = Map.of("NORMAL", 1.0, "PEAK", 1.7, "MONSOON", 1.25);

    static double shrink(double ratio) {
        return Math.max(0.6, Math.min(1.6, 1 + 0.6 * (ratio - 1)));
    }

    private double revenue(LocalDateTime from, LocalDateTime to) {
        Double v = jdbc.queryForObject("SELECT COALESCE(SUM(total),0) FROM orders WHERE status <> 'CANCELLED' AND placed_at >= ? AND placed_at < ?",
                Double.class, OrderRepository.ts(from), OrderRepository.ts(to));
        return v == null ? 0 : v;
    }

    /** Mean revenue of clock hour `h` on up to 21 earlier days of the same day type, strictly before `before`. */
    static double baseline(Map<LocalDateTime, Double> hourly, LocalDateTime h, LocalDateTime before) {
        boolean weekend = h.getDayOfWeek().getValue() >= 6;
        double sum = 0;
        int n = 0;
        for (int d = 1; d <= 21; d++) {
            LocalDateTime past = h.minusDays(d);
            if (!past.isBefore(before.truncatedTo(ChronoUnit.HOURS))) continue;
            if ((past.getDayOfWeek().getValue() >= 6) != weekend) continue;
            Double v = hourly.get(past);
            if (v != null) { sum += v; n++; }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Baseline for the 60 minutes starting at `from`: blend of the two clock hours it overlaps. */
    private static double window(Map<LocalDateTime, Double> hourly, LocalDateTime from, LocalDateTime before) {
        LocalDateTime h0 = from.truncatedTo(ChronoUnit.HOURS);
        double frac = 1 - Duration.between(h0, from).toMinutes() / 60.0;
        return frac * baseline(hourly, h0, before) + (1 - frac) * baseline(hourly, h0.plusHours(1), before);
    }
}
