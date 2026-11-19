package com.dispatch.model;

import java.util.List;
import java.util.Set;

/** All request/response and domain records in one place. */
public final class Models {
    private Models() {}

    public enum Speciality { GENERAL, CARDIAC, TRAUMA, BURNS, NEURO }

    public enum Strategy { MAXFLOW, GREEDY }

    // ---- domain / scenario input ----

    public record Hospital(String id, String name, Double lat, Double lng, Integer freeBeds, Set<Speciality> specialities) {}

    public record Ambulance(String id, Double lat, Double lng) {}

    /** A city zone with its historical average emergency calls per day (used by the Monte Carlo forecast). */
    public record Zone(String name, Double lat, Double lng, Double callsPerDay) {}

    public record NewEmergency(Double lat, Double lng, Integer severity, Speciality type) {}

    public record Scenario(List<Hospital> hospitals, List<Ambulance> ambulances, List<Zone> zones, List<NewEmergency> emergencies) {}

    // ---- requests ----

    public record EscalateRequest(Integer severity) {}

    public record DispatchRequest(Double ambulanceRadiusKm, Double hospitalRadiusKm, Strategy strategy, Boolean apply) {}

    public record SimulateRequest(Integer count, Long seed) {}

    public record ForecastRequest(Integer days, Long seed, Double percentile, Integer callsPerAmbulance) {}

    // ---- responses ----

    public record EmergencyView(String id, double lat, double lng, int severity, Speciality type, int queuePosition) {}

    public record AmbulanceView(String id, double lat, double lng, boolean busy) {}

    /** A patient lying in a hospital bed; the bed frees up when secondsLeft reaches 0. */
    public record AdmissionView(String emergencyId, int severity, String hospitalId, String hospitalName, long secondsLeft) {}

    /** Running totals since the city was loaded. avgResponseMin = mean ambulance-to-patient time of dispatched calls. */
    public record Stats(int callsReceived, int patientsHelped, double avgResponseMin) {}

    /** missions = ambulances currently driving to a patient and on to a hospital. */
    public record StateView(List<Hospital> hospitals, List<AmbulanceView> ambulances, List<EmergencyView> queue,
                            List<Zone> zones, List<Assignment> missions, List<AdmissionView> admissions, Stats stats) {}

    public record Assignment(String emergencyId, int severity, Speciality type, double lat, double lng,
                             String ambulanceId, double ambulanceLat, double ambulanceLng,
                             String hospitalId, String hospitalName, double hospitalLat, double hospitalLng,
                             double pickupKm, double transportKm, double etaToPatientMin, double totalMin) {}

    public record Unserved(String emergencyId, int severity, Speciality type, String reason) {}

    public record DispatchResult(Strategy strategy, boolean applied, int served, int unservedCount,
                                 double avgEtaToPatientMin, long runtimeMicros, int augmentingPaths,
                                 int localSearchMoves, boolean roadDistances, List<String> bottlenecks, List<Assignment> assignments, List<Unserved> unserved) {}

    public record ZoneForecast(String zone, double lat, double lng, double callsPerDay,
                               double simulatedMean, int p50, int pTarget,
                               int recommendedAmbulances, double coverage,
                               int meanBasedAmbulances, double meanBasedCoverage) {}

    public record ForecastResult(int days, long seed, double percentile, int callsPerAmbulance,
                                 int totalRecommended, int totalMeanBased, long runtimeMicros, List<ZoneForecast> zones) {}

    public record BenchmarkRow(String experiment, int n, String algorithm, double algorithmMs,
                               String baseline, double baselineMs, String note) {}
}
