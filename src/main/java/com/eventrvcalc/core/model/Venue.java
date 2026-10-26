package com.eventrvcalc.core.model;

import java.util.Objects;

/**
 * Identifies an execution or prediction market venue.
 *
 * @param id Unique canonical identifier for the venue (e.g., "KALSHI", "POLYMARKET", "CME").
 */
public record Venue(String id) {

    public static final Venue KALSHI = new Venue("KALSHI");
    public static final Venue POLYMARKET = new Venue("POLYMARKET");
    public static final Venue CME = new Venue("CME");
    public static final Venue DERIBIT = new Venue("DERIBIT");

    public Venue {
        Objects.requireNonNull(id, "Venue id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Venue id must not be blank");
        }
    }

    public static Venue of(String id) {
        return new Venue(id);
    }
}
