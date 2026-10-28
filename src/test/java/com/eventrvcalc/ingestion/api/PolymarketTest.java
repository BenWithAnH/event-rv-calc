package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.BL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PolymarketTest {

    @Test
    @DisplayName("Extracts event slug from full Polymarket URLs and raw slugs")
    void testExtractSlug() {
        String url = "https://polymarket.com/event/fed-decision-in-december-20260729232808632?tid=123/";
        assertThat(Polymarket.extractSlug(url)).isEqualTo("fed-decision-in-december-20260729232808632");
        assertThat(Polymarket.extractSlug("fed-decision-in-december-20260729232808632"))
                .isEqualTo("fed-decision-in-december-20260729232808632");
    }

    @Test
    @DisplayName("Parses Gamma API event payload and feeds outcome probabilities into BL jump equations")
    void testParseEventAndBridgeToBL() throws Exception {
        String sampleGammaJson = """
                [
                  {
                    "id": "770450",
                    "slug": "fed-decision-in-december-20260729232808632",
                    "title": "Fed Decision in December?",
                    "markets": [
                      {
                        "id": "3215005",
                        "question": "Will the Fed decrease interest rates by 50+ bps after the December 2026 meeting?",
                        "slug": "will-the-fed-decrease-interest-rates-by-50-bps-after-the-december-2026-meeting-20260729232808633",
                        "groupItemTitle": "50+ bps decrease",
                        "outcomes": "[\\"Yes\\", \\"No\\"]",
                        "outcomePrices": "[\\"0.004\\", \\"0.996\\"]",
                        "volumeNum": 900147.92,
                        "closed": false
                      },
                      {
                        "id": "3215006",
                        "question": "Will the Fed decrease interest rates by 25 bps after the December 2026 meeting?",
                        "slug": "will-the-fed-decrease-interest-rates-by-25-bps-after-the-december-2026-meeting-20260729232808634",
                        "groupItemTitle": "25 bps decrease",
                        "outcomes": "[\\"Yes\\", \\"No\\"]",
                        "outcomePrices": "[\\"0.0055\\", \\"0.9945\\"]",
                        "volumeNum": 1205522.59,
                        "closed": false
                      },
                      {
                        "id": "3215007",
                        "question": "Will there be no change in Fed interest rates after the December 2026 meeting?",
                        "slug": "will-there-be-no-change-in-fed-interest-rates-after-the-december-2026-meeting-20260729232808635",
                        "groupItemTitle": "No change",
                        "outcomes": "[\\"Yes\\", \\"No\\"]",
                        "outcomePrices": "[\\"0.155\\", \\"0.845\\"]",
                        "volumeNum": 1221997.27,
                        "closed": false
                      },
                      {
                        "id": "3215008",
                        "question": "Will the Fed increase interest rates by 25 bps after the December 2026 meeting?",
                        "slug": "will-the-fed-increase-interest-rates-by-25-bps-after-the-december-2026-meeting-20260729232808636",
                        "groupItemTitle": "25 bps increase",
                        "outcomes": "[\\"Yes\\", \\"No\\"]",
                        "outcomePrices": "[\\"0.815\\", \\"0.185\\"]",
                        "volumeNum": 1458710.24,
                        "closed": false
                      },
                      {
                        "id": "3215009",
                        "question": "Will the Fed increase interest rates by 50+ bps after the December 2026 meeting?",
                        "slug": "will-the-fed-increase-interest-rates-by-50-bps-after-the-december-2026-meeting-20260729232808637",
                        "groupItemTitle": "50+ bps increase",
                        "outcomes": "[\\"Yes\\", \\"No\\"]",
                        "outcomePrices": "[\\"0.025\\", \\"0.975\\"]",
                        "volumeNum": 752386.93,
                        "closed": false
                      }
                    ]
                  }
                ]
                """;

        Polymarket.Event event = Polymarket.parseEventJson(sampleGammaJson);
        assertThat(event.id()).isEqualTo("770450");
        assertThat(event.markets()).hasSize(5);

        // Summed hike probability: 0.815 (25 bps increase) + 0.025 (50+ bps increase) = 0.840
        double pHike = event.yesProbability();
        assertThat(pHike).isCloseTo(0.840, within(1e-6));

        double sigmaEvent = 0.025; // 2.5% implied pure event move
        BL.ZeroDriftJump jump = Polymarket.solveZeroDriftJump(event, sigmaEvent);
        // Event (hike) is bearish: moveIfEvent = -sigma * sqrt((1-p)/p), moveIfNotEvent = +sigma * sqrt(p/(1-p))
        assertThat(jump.moveIfEvent()).isCloseTo(-sigmaEvent * Math.sqrt((1.0 - pHike) / pHike), within(1e-9));
        assertThat(jump.moveIfNotEvent()).isCloseTo(sigmaEvent * Math.sqrt(pHike / (1.0 - pHike)), within(1e-9));
        assertThat(pHike * jump.moveIfEvent() + (1.0 - pHike) * jump.moveIfNotEvent()).isCloseTo(0.0, within(1e-12));

        // Verify Gaussian mixture fitting with aggregated Polymarket hike probability
        List<BL.LogReturnPoint> syntheticJumpDensity = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double x = -0.10 + i * (0.20 / 199);
            double d = pHike * gauss(x, 0.012, 0.008) + (1.0 - pHike) * gauss(x, -0.052, 0.010);
            syntheticJumpDensity.add(new BL.LogReturnPoint(x, d));
        }

        BL.GaussianMixture gm = Polymarket.fitGaussianMixture(syntheticJumpDensity, event);
        assertThat(gm.p()).isCloseTo(pHike, within(1e-6));
        assertThat(gm.muUp()).isCloseTo(0.012, within(0.002));
        assertThat(gm.muDown()).isCloseTo(-0.052, within(0.002));
    }

    @Test
    @DisplayName("Live Gamma API fetch for fed-decision-in-december-20260729232808632")
    void testLiveFedDecisionEvent() throws Exception {
        String url = "https://polymarket.com/event/fed-decision-in-december-20260729232808632";
        Polymarket.Event event = Polymarket.fetchEvent(url);

        assertThat(event.slug()).isEqualTo("fed-decision-in-december-20260729232808632");
        assertThat(event.markets()).isNotEmpty();

        double pHike = event.yesProbability();
        assertThat(pHike).isBetween(0.0, 1.0);
        if (pHike > 0.0 && pHike < 1.0) {
            BL.ZeroDriftJump jump = Polymarket.solveZeroDriftJump(event, 0.02);
            assertThat(pHike * jump.moveIfEvent() + (1.0 - pHike) * jump.moveIfNotEvent()).isCloseTo(0.0, within(1e-12));
        }
    }

    @Test
    @DisplayName("Scrapes Federal Reserve FOMC dates and matches top-volume active Polymarket FOMC market")
    void testFomcDatesAndMarketDiscovery() throws Exception {
        List< java.time.LocalDate > dates = Polymarket.fetchFomcDecisionDates();
        assertThat(dates).isNotEmpty();
        assertThat(dates).contains(java.time.LocalDate.of(2026, 10, 28), java.time.LocalDate.of(2026, 12, 9));

        List<Polymarket.Event> activeFomc = Polymarket.fetchActiveFomcEvents();
        assertThat(activeFomc).isNotEmpty();

        // Verify sorted by volume descending
        for (int i = 0; i < activeFomc.size() - 1; i++) {
            assertThat(activeFomc.get(i).volume()).isGreaterThanOrEqualTo(activeFomc.get(i + 1).volume());
        }

        // Match December 9, 2026 FOMC decision to Polymarket's December event
        java.util.Optional<Polymarket.Event> decMatch = Polymarket.findFomcEventForDate(
                java.time.LocalDate.of(2026, 12, 9), activeFomc);
        assertThat(decMatch).isPresent();
        assertThat(decMatch.get().slug()).contains("fed-decision-in-december");

        double pHike = decMatch.get().yesProbability();
        assertThat(pHike).isBetween(0.0, 1.0);
    }

    private static double gauss(double x, double mu, double sigma) {
        double z = (x - mu) / sigma;
        return Math.exp(-0.5 * z * z) / (sigma * Math.sqrt(2.0 * Math.PI));
    }
}

