package com.eventrvcalc.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical raw tick ingested from external feeds prior to filtering or normalisation.
 *
 * @param instrumentId      The instrument ticker and venue.
 * @param exchangeTimestamp Timestamp reported by the exchange matching the event.
 * @param receiptTimestamp  Local arrival timestamp for latency and staleness analysis.
 * @param bidPrice          Top-of-book bid price (nullable).
 * @param bidSize           Top-of-book bid size/depth (nullable).
 * @param askPrice          Top-of-book ask price (nullable).
 * @param askSize           Top-of-book ask size/depth (nullable).
 * @param lastPrice         Last trade transaction price (nullable).
 * @param lastSize          Last trade transaction size (nullable).
 * @param metadata          Venue-specific auxiliary properties (e.g. conditionId, strike, expiry).
 */
public record RawMarketTick(
    InstrumentId instrumentId,
    Instant exchangeTimestamp,
    Instant receiptTimestamp,
    BigDecimal bidPrice,
    BigDecimal bidSize,
    BigDecimal askPrice,
    BigDecimal askSize,
    BigDecimal lastPrice,
    BigDecimal lastSize,
    Map<String, String> metadata
) {
    public RawMarketTick {
        Objects.requireNonNull(instrumentId, "instrumentId must not be null");
        Objects.requireNonNull(exchangeTimestamp, "exchangeTimestamp must not be null");
        Objects.requireNonNull(receiptTimestamp, "receiptTimestamp must not be null");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public Venue venue() {
        return instrumentId.venue();
    }
}
