package com.eventrvcalc.core.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSerializationSanityTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Test
    @DisplayName("Venue serializes and deserializes cleanly")
    void testVenueSerialization() throws Exception {
        Venue venue = Venue.KALSHI;

        String json = objectMapper.writeValueAsString(venue);
        Venue roundTrip = objectMapper.readValue(json, Venue.class);

        assertThat(roundTrip).isEqualTo(venue);
        assertThat(json).contains("\"id\":\"KALSHI\"");
    }

    @Test
    @DisplayName("InstrumentId serializes and deserializes cleanly")
    void testInstrumentIdSerialization() throws Exception {
        InstrumentId instrumentId = InstrumentId.of(Venue.POLYMARKET, "0x123456789abcdef");

        String json = objectMapper.writeValueAsString(instrumentId);
        InstrumentId roundTrip = objectMapper.readValue(json, InstrumentId.class);

        assertThat(roundTrip).isEqualTo(instrumentId);
        assertThat(roundTrip.venue()).isEqualTo(Venue.POLYMARKET);
        assertThat(roundTrip.symbol()).isEqualTo("0x123456789abcdef");
    }

    @Test
    @DisplayName("EventOutcome serializes and deserializes cleanly")
    void testEventOutcomeSerialization() throws Exception {
        EventOutcome outcome = new EventOutcome(
                "CUT_25",
                "Fed cuts rates by 25 bps",
                Map.of("targetRateMin", "4.25", "targetRateMax", "4.50")
        );

        String json = objectMapper.writeValueAsString(outcome);
        EventOutcome roundTrip = objectMapper.readValue(json, EventOutcome.class);

        assertThat(roundTrip).isEqualTo(outcome);
        assertThat(roundTrip.outcomeId()).isEqualTo("CUT_25");
        assertThat(roundTrip.metadata()).containsEntry("targetRateMin", "4.25");
    }

    @Test
    @DisplayName("EventDefinition serializes and deserializes cleanly")
    void testEventDefinitionSerialization() throws Exception {
        EventOutcome cut = new EventOutcome("CUT", "Cut rates");
        EventOutcome hold = new EventOutcome("HOLD", "Hold rates");
        EventDefinition definition = EventDefinition.binary(
                "FOMC_2024_12",
                "MACRO_RATES",
                "December 2024 FOMC Interest Rate Decision",
                Instant.parse("2024-12-18T19:00:00Z"),
                cut,
                hold
        );

        String json = objectMapper.writeValueAsString(definition);
        EventDefinition roundTrip = objectMapper.readValue(json, EventDefinition.class);

        assertThat(roundTrip).isEqualTo(definition);
        assertThat(roundTrip.possibleOutcomes()).containsExactly(cut, hold);
        assertThat(roundTrip.metadata()).containsEntry("eventType", "BINARY");
    }

    @Test
    @DisplayName("RawMarketTick serializes and deserializes cleanly with decimals and timestamps")
    void testRawMarketTickSerialization() throws Exception {
        InstrumentId instrument = InstrumentId.of(Venue.CME, "ZQZ4");
        Instant exchangeTime = Instant.parse("2024-12-18T18:59:59.123456Z");
        Instant receiptTime = Instant.parse("2024-12-18T19:00:00.001234Z");

        RawMarketTick tick = new RawMarketTick(
                instrument,
                exchangeTime,
                receiptTime,
                new BigDecimal("95.525"),
                new BigDecimal("500"),
                new BigDecimal("95.530"),
                new BigDecimal("350"),
                new BigDecimal("95.525"),
                new BigDecimal("100"),
                Map.of("feedType", "BOOK_L1")
        );

        String json = objectMapper.writeValueAsString(tick);
        RawMarketTick roundTrip = objectMapper.readValue(json, RawMarketTick.class);

        assertThat(roundTrip).isEqualTo(tick);
        assertThat(roundTrip.venue()).isEqualTo(Venue.CME);
        assertThat(roundTrip.bidPrice()).isEqualByComparingTo(new BigDecimal("95.525"));
        assertThat(roundTrip.metadata()).containsEntry("feedType", "BOOK_L1");
    }

    @Test
    @DisplayName("ProbabilitySlice serializes and deserializes cleanly with distribution map")
    void testProbabilitySliceSerialization() throws Exception {
        ProbabilitySlice slice = new ProbabilitySlice(
                "FOMC_2024_12",
                Venue.KALSHI,
                Instant.parse("2024-12-18T19:00:00Z"),
                Map.of(
                        "CUT_25", new BigDecimal("0.8500"),
                        "HOLD", new BigDecimal("0.1500")
                ),
                new BigDecimal("0.9800"),
                Map.of("model", "DIRECT_MID_MARKET")
        );

        String json = objectMapper.writeValueAsString(slice);
        ProbabilitySlice roundTrip = objectMapper.readValue(json, ProbabilitySlice.class);

        assertThat(roundTrip).isEqualTo(slice);
        assertThat(roundTrip.probabilities()).hasSize(2);
        assertThat(roundTrip.probabilities().get("CUT_25")).isEqualByComparingTo(new BigDecimal("0.8500"));
        assertThat(roundTrip.confidence()).isEqualByComparingTo(new BigDecimal("0.9800"));
    }
}
