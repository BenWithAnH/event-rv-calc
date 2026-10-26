package com.eventrvcalc.core.model;

import java.util.Objects;

/**
 * Strongly-typed identifier pairing a trading venue with an exchange-specific ticker/symbol.
 *
 * @param venue  The trading venue.
 * @param symbol The venue ticker or contract symbol (e.g., "FED-25DEC-T4.50", "ZQZ4").
 */
public record InstrumentId(Venue venue, String symbol) {

    public InstrumentId {
        Objects.requireNonNull(venue, "venue must not be null");
        Objects.requireNonNull(symbol, "symbol must not be null");
        if (symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be blank");
        }
    }

    public static InstrumentId of(Venue venue, String symbol) {
        return new InstrumentId(venue, symbol);
    }
}
