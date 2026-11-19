package com.dispatch.service;

import com.dispatch.model.Models.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ForecastServiceTest {

    private final DispatchService dispatch = new DispatchService(JsonMapper.builder().build());
    private final ForecastService svc = new ForecastService(dispatch);

    @Test
    void poissonSampleMeanAndVarianceMatchLambda() { // sound probabilistic behaviour
        Random rnd = new Random(1);
        int n = 200_000;
        double lambda = 20, sum = 0, sq = 0;
        for (int i = 0; i < n; i++) {
            int x = ForecastService.poisson(lambda, rnd);
            sum += x;
            sq += (double) x * x;
        }
        double mean = sum / n, var = sq / n - mean * mean;
        assertEquals(lambda, mean, 0.1);
        assertEquals(lambda, var, 0.5); // Poisson: variance == mean
    }

    @Test
    void sameSeedIsReproducible() {
        ForecastResult a = svc.forecast(new ForecastRequest(2000, 7L, 90.0, 8));
        ForecastResult b = svc.forecast(new ForecastRequest(2000, 7L, 90.0, 8));
        assertEquals(a.zones(), b.zones());
    }

    @Test
    void percentilePlanCoversAtLeastNinetyPercentOfDays() {
        ForecastResult r = svc.forecast(new ForecastRequest(20_000, 3L, 90.0, 1));
        for (ZoneForecast z : r.zones()) {
            assertTrue(z.coverage() >= 0.9, z.zone() + " coverage " + z.coverage());
            assertTrue(z.meanBasedCoverage() < z.coverage(), z.zone() + ": mean-based plan should cover fewer days");
            assertTrue(z.p50() <= z.pTarget());
        }
    }

    @Test
    void invalidParametersRejected() {
        assertThrows(IllegalArgumentException.class, () -> svc.forecast(new ForecastRequest(5, 1L, 90.0, 8)));
        assertThrows(IllegalArgumentException.class, () -> svc.forecast(new ForecastRequest(100, 1L, 100.0, 8)));
        assertThrows(IllegalArgumentException.class, () -> svc.forecast(new ForecastRequest(100, 1L, 90.0, 0)));
    }

    @Test
    void scenarioWithoutZonesCannotBeForecast() { // edge case
        dispatch.load(new Scenario(
                List.of(new Hospital("H", "H", 19.05, 72.85, 1, Set.of(Speciality.GENERAL))),
                List.of(new Ambulance("A", 19.05, 72.85)), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> svc.forecast(null));
    }
}
