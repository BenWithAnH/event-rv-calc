package com.eventrvcalc.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical specification for a real-world event (e.g., FOMC meeting, CPI release, election).
 *
 * @param eventId          Canonical event identifier (e.g. "FOMC_2024_12").
 * @param category         High-level category (e.g. "MACRO_RATES", "INFLATION", "EARNINGS").
 * @param description      Human-readable description.
 * @param scheduledTime    Anticipated announcement or resolution timestamp.
 * @param possibleOutcomes Complete set of canonical outcomes for this event.
 * @param metadata         Additional event properties.
 */
public record EventDefinition(
    String eventId,
    String category,
    String description,
    Instant scheduledTime,
    List<EventOutcome> possibleOutcomes,
    Map<String, String> metadata
) {
    public EventDefinition {
        Objects.requireNonNull(eventId, "eventId must not be null");
        possibleOutcomes = possibleOutcomes == null ? List.of() : List.copyOf(possibleOutcomes);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static EventDefinition binary(
        String eventId,
        String category,
        String description,
        Instant scheduledTime,
        EventOutcome positiveOutcome,
        EventOutcome negativeOutcome
    ) {
        return new EventDefinition(
            eventId,
            category,
            description,
            scheduledTime,
            List.of(positiveOutcome, negativeOutcome),
            Map.of("eventType", "BINARY")
        );
    }
}
