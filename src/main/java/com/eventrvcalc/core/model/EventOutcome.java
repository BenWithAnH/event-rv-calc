package com.eventrvcalc.core.model;

import java.util.Map;
import java.util.Objects;

/**
 * A discrete canonical outcome state for a real-world event.
 *
 * @param outcomeId Canonical outcome identifier (e.g. "YES", "NO", "CUT_25", "HOLD", "HIKE_25").
 * @param label     Human-readable display name.
 * @param metadata  Optional outcome-specific attributes (e.g. target rate, basis points).
 */
public record EventOutcome(
    String outcomeId,
    String label,
    Map<String, String> metadata
) {
    public EventOutcome {
        Objects.requireNonNull(outcomeId, "outcomeId must not be null");
        label = label == null ? outcomeId : label;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public EventOutcome(String outcomeId, String label) {
        this(outcomeId, label, Map.of());
    }

    public static EventOutcome of(String outcomeId, String label) {
        return new EventOutcome(outcomeId, label);
    }
}
