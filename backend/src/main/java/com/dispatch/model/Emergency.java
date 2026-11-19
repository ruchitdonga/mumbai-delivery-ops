package com.dispatch.model;

import com.dispatch.model.Models.Speciality;

/** A waiting emergency call. Severity is mutable because a call can be escalated while it waits. */
public final class Emergency {
    public final String id;
    public final double lat, lng;
    public final Speciality type;
    /** Arrival order; breaks ties so equal-severity calls are served first-come-first-served. */
    public final long seq;
    public int severity;

    public Emergency(String id, double lat, double lng, int severity, Speciality type, long seq) {
        this.id = id;
        this.lat = lat;
        this.lng = lng;
        this.severity = severity;
        this.type = type;
        this.seq = seq;
    }

    /** Heap key: smaller = more urgent. Severity 5 sorts before 4, then arrival order. */
    public long priorityKey() {
        return (5L - severity) * 1_000_000_000_000L + seq;
    }
}
