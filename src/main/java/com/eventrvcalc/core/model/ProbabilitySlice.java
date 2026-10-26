package com.eventrvcalc.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot slice of implied probability distribution extracted from a single venue for an event.
 *
 * @param eventId       Canonical event identifier.
 * @param venue         The originating market venue.
 * @param timestamp     Calculation or observation timestamp.
 * @param probabilities Map of canonical outcomeId to implied probability P(outcome) in [0, 1].
 * @param confidence    Confidence/quality metric in [0, 1] based on liquidity, depth, or spread.
 * @param metadata      Diagnostic attributes (e.g. model used, source instrumentId, raw spread).
 */
public record ProbabilitySlice(
    String eventId,
    Venue venue,
    Instant timestamp,
    Map<String, BigDecimal> probabilities,
    BigDecimal confidence,
    Map<String, String> metadata
) {
    public ProbabilitySlice {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(venue, "venue must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
