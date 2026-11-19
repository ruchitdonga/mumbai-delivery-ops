package com.dispatch.service;

import com.dispatch.algo.RandomizedSelect;
import com.dispatch.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Monte Carlo staffing forecast (CO5).
 *
 * For each zone we simulate `days` independent days of calls, Poisson(callsPerDay), then use randomized
 * Quickselect to read off the median and the target percentile. Staffing for the p90 day instead of the
 * average day is the point: the mean-based plan is compared alongside to show how often it falls short.
 */
@Service
public class ForecastService {

    private final DispatchService dispatch;

    public ForecastService(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    public ForecastResult forecast(ForecastRequest r) {
        int days = r == null || r.days() == null ? 1000 : r.days();
        long seed = r == null || r.seed() == null ? 42L : r.seed();
        double pct = r == null || r.percentile() == null ? 90.0 : r.percentile();
        int cpa = r == null || r.callsPerAmbulance() == null ? 8 : r.callsPerAmbulance();
        DispatchService.require(days >= 10 && days <= 200_000, "days must be between 10 and 200000");
        DispatchService.require(pct >= 50 && pct <= 99.9, "percentile must be between 50 and 99.9");
        DispatchService.require(cpa >= 1 && cpa <= 100, "callsPerAmbulance must be between 1 and 100");

        List<Zone> zones = dispatch.zones();
        DispatchService.require(!zones.isEmpty(), "The loaded scenario has no zones to forecast");

        Random rnd = new Random(seed);
        long t0 = System.nanoTime();
        List<ZoneForecast> out = new ArrayList<>();
        int totalRec = 0, totalMean = 0;
        for (Zone z : zones) {
            int[] calls = new int[days];
            long sum = 0;
            for (int d = 0; d < days; d++) {
                calls[d] = poisson(z.callsPerDay(), rnd);
                sum += calls[d];
            }
            double mean = (double) sum / days;
            int p50 = RandomizedSelect.percentile(calls, 50, rnd);
            int pT = RandomizedSelect.percentile(calls, pct, rnd);
            int rec = Math.max(1, (int) Math.ceil((double) pT / cpa));
            int meanBased = Math.max(1, (int) Math.ceil(mean / cpa));
            out.add(new ZoneForecast(z.name(), z.lat(), z.lng(), z.callsPerDay(), round2(mean), p50, pT,
                    rec, round2(coverage(calls, rec * cpa)), meanBased, round2(coverage(calls, meanBased * cpa))));
            totalRec += rec;
            totalMean += meanBased;
        }
        long micros = (System.nanoTime() - t0) / 1000;
        return new ForecastResult(days, seed, pct, cpa, totalRec, totalMean, micros, out);
    }

    /** Knuth's method: multiply uniforms until the product drops below e^-lambda. Fine for lambda <= 500. */
    static int poisson(double lambda, Random rnd) {
        double limit = Math.exp(-lambda), p = 1.0;
        int k = 0;
        do {
            k++;
            p *= rnd.nextDouble();
        } while (p > limit);
        return k - 1;
    }

    /** Fraction of simulated days whose demand fits in the given capacity. */
    private static double coverage(int[] calls, int capacity) {
        int ok = 0;
        for (int c : calls) if (c <= capacity) ok++;
        return (double) ok / calls.length;
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
