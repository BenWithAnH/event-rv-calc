package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.BL;
import com.eventrvcalc.core.surface.SurfaceRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class YFinanceOptionsIngestionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("SurfaceRunner can process test JSON dataset and extract BL variance")
    void testProcessExistingOptionSlice() throws Exception {
        File spyInput = new File("spy_input.json");
        assertThat(spyInput).exists();

        JsonNode root = MAPPER.readTree(spyInput);
        SurfaceRunner.SurfaceResult result = SurfaceRunner.processOptionSlice(root);

        assertThat(result.S0()).isCloseTo(381.10, within(0.50));
        assertThat(result.forwardPrice()).isGreaterThan(result.S0());
        assertThat(result.blTotalProb()).isCloseTo(1.0, within(0.05));
        assertThat(result.logVariance()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Multi-expiry event variance decomposition produces valid V1 and pure sigma_event")
    void testMultiExpiryDecomposition() throws Exception {
        // Construct 3 synthetic slices representing T1 (1d), T2 (2d + event), T3 (7d + event)
        double S0 = 100.0;
        double r = 0.04;
        double q = 0.0;
        double v1DayTrue = 0.00015; // daily variance
        double sigmaEventTrue = 0.035; // 3.5% pure jump
        int nStrikes = 60;

        JsonNode t1Node = createSyntheticSlice(S0, 1.0 / 365.0, r, q, Math.sqrt(v1DayTrue * 365.0), nStrikes);
        // T2 has 2 days of noise variance + event jump variance
        double t2Var = 2.0 * v1DayTrue + sigmaEventTrue * sigmaEventTrue;
        JsonNode t2Node = createSyntheticSlice(S0, 2.0 / 365.0, r, q, Math.sqrt((t2Var / 2.0) * 365.0), nStrikes);
        // T3 has 7 days of noise variance (5 days after T2) + event jump variance
        double t3Var = 7.0 * v1DayTrue + sigmaEventTrue * sigmaEventTrue;
        JsonNode t3Node = createSyntheticSlice(S0, 7.0 / 365.0, r, q, Math.sqrt((t3Var / 7.0) * 365.0), nStrikes);

        SurfaceRunner.EventPureMoveResult res = SurfaceRunner.calculateEventWindow(t1Node, t2Node, t3Node, 5.0, 1.0);

        assertThat(res.v1Day()).isGreaterThan(0.0);
        assertThat(res.sigmaEvent()).isCloseTo(sigmaEventTrue, within(0.01));

        // Connect pure sigmaEvent to Polymarket FOMC jump
        double p = 0.70;
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(res.sigmaEvent(), p);
        assertThat(p * jump.u() + (1.0 - p) * jump.d()).isCloseTo(0.0, within(1e-12));
    }

    @Test
    @DisplayName("Fallback to v1Pre when v1Post is negative or inverted (e.g. HYG / IEF negative variance slope)")
    void testNegativeV1PostFallsBackToV1Pre() throws Exception {
        double S0 = 100.0;
        double r = 0.04;
        double q = 0.0;
        int nStrikes = 60;

        // T1 variance: 0.001
        JsonNode t1Node = createSyntheticSlice(S0, 10.0 / 365.0, r, q, Math.sqrt((0.001 / (10.0 / 365.0))), nStrikes);
        // T2 variance: 0.0025
        JsonNode t2Node = createSyntheticSlice(S0, 25.0 / 365.0, r, q, Math.sqrt((0.0025 / (25.0 / 365.0))), nStrikes);
        // T3 variance lower than T2: 0.0020 (causes negative v1Post)
        JsonNode t3Node = createSyntheticSlice(S0, 40.0 / 365.0, r, q, Math.sqrt((0.0020 / (40.0 / 365.0))), nStrikes);

        SurfaceRunner.EventPureMoveResult res = SurfaceRunner.calculateEventWindow(t1Node, t2Node, t3Node, 15.0, 1.0);

        // Should not be clamped to 0.0; should fall back to v1Pre (strictly > 0)
        assertThat(res.v1Day()).isGreaterThan(1e-6);
        assertThat(res.sigmaEvent()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("End-to-end integration: load downloaded yfinance SPY 3-expiry manifest and calculate pure event move")
    void testLoadYFinanceDownloadedManifest() throws Exception {
        File manifestFile = new File("target/test_options/spy_event_manifest.json");
        if (!manifestFile.exists()) return; // skip if offline / not downloaded yet

        JsonNode root = MAPPER.readTree(manifestFile);
        YFinanceOptionsIngestion.OptionsManifest manifest = new YFinanceOptionsIngestion.OptionsManifest(
                root.path("symbol").asText(),
                root.path("eventDate").asText(),
                root.path("t1Expiry").asText(),
                root.path("t2Expiry").asText(),
                root.path("t3Expiry").asText(),
                root.path("tradingDaysT1ToT2").asDouble(1.0),
                root.path("tradingDaysT2ToT3").asDouble(5.0),
                Path.of(root.path("t1File").asText()),
                Path.of(root.path("t2File").asText()),
                Path.of(root.path("t3File").asText())
        );

        SurfaceRunner.EventPureMoveResult res = YFinanceOptionsIngestion.computePureEventMove(manifest);
        assertThat(res.vT1()).isGreaterThan(0.0);
        assertThat(res.vT2()).isGreaterThan(0.0);
        assertThat(res.vT3()).isGreaterThan(0.0);
        assertThat(res.sigmaEvent()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    @DisplayName("computeFedFundsProbability computes correct post rate and pBonds for rate hike")
    void testFedFundsProbabilityCalculation_Hike() {
        // Pre-FOMC price 95.625 (rate 4.375%), meeting month price 95.455 on Dec 9 (31 days in Dec, d=9)
        LocalDate meetingDate = LocalDate.of(2026, 12, 9);
        double prePrice = 95.625;
        double meetingPrice = 95.455;

        var res = YFinanceOptionsIngestion.computeFedFundsProbability(prePrice, meetingPrice, meetingDate);

        assertThat(res.ratePrePct()).isCloseTo(4.375, within(0.001));
        // 31 days in Dec, d=9: 8 days at 4.375%, 23 days at rPost => (31*4.545 - 8*4.375)/23 = 4.60413%
        assertThat(res.ratePostImpliedPct()).isCloseTo(4.6041, within(0.001));
        assertThat(res.deltaRatePct()).isCloseTo(0.2291, within(0.001));
        assertThat(res.pBonds()).isCloseTo(0.9165, within(0.005));
        assertThat(res.isHikeImplied()).isTrue();
    }

    @Test
    @DisplayName("computeFedFundsProbability computes correct post rate and pBonds for rate cut")
    void testFedFundsProbabilityCalculation_Cut() {
        // Pre-FOMC price 95.00 (rate 5.00%), meeting on Sep 16 (30 days in Sep, d=16, 15 pre days / 15 post days)
        // Implied post rate 4.75% (-25bps cut) => meeting month avg rate = (15*5.00 + 15*4.75)/30 = 4.875% => price 95.125
        LocalDate meetingDate = LocalDate.of(2026, 9, 16);
        double prePrice = 95.00;
        double meetingPrice = 95.125;

        var res = YFinanceOptionsIngestion.computeFedFundsProbability(prePrice, meetingPrice, meetingDate);

        assertThat(res.ratePrePct()).isCloseTo(5.00, within(0.001));
        assertThat(res.ratePostImpliedPct()).isCloseTo(4.75, within(0.001));
        assertThat(res.deltaRatePct()).isCloseTo(-0.25, within(0.001));
        assertThat(res.pBonds()).isCloseTo(1.0, within(0.001));
        assertThat(res.isHikeImplied()).isFalse();
    }

    @Test
    @DisplayName("computeFedFundsProbability falls back to postPrice when meeting is at end of month (daysPost <= 2)")
    void testFedFundsProbabilityCalculation_EndOfMonthFallback() {
        // Jan 30 meeting (31 days in Jan, d=30 => daysPost = 31 - 29 = 2 <= 2)
        LocalDate meetingDate = LocalDate.of(2026, 1, 30);
        double prePrice = 95.50; // rate 4.50%
        double meetingPrice = 95.40;
        double postPrice = 95.30; // Feb futures price => rate 4.70% (+20bps)

        var res = YFinanceOptionsIngestion.computeFedFundsProbability(prePrice, meetingPrice, postPrice, meetingDate);

        assertThat(res.ratePrePct()).isCloseTo(4.50, within(0.001));
        assertThat(res.ratePostImpliedPct()).isCloseTo(4.70, within(0.001));
        assertThat(res.deltaRatePct()).isCloseTo(0.20, within(0.001));
        assertThat(res.pBonds()).isCloseTo(0.80, within(0.001));
        assertThat(res.isHikeImplied()).isTrue();
    }

    @Test
    @DisplayName("SUPPORTED_TICKERS contains SPY, TLT, IEF, HYG")
    void testSupportedTickers() {
        assertThat(YFinanceOptionsIngestion.SUPPORTED_TICKERS)
                .containsExactly("SPY", "TLT", "IEF", "HYG");
    }

    @Test
    @DisplayName("SurfaceRunner and BL compute valid log variance and log skewness on bond ETF option slice")
    void testBondEtfSlicesSkewnessAndVariance() throws Exception {
        double S0 = 100.0;
        double T = 30.0 / 365.0;
        double r = 0.045;
        double q = 0.0;
        double vol = 0.15;
        int nStrikes = 50;

        JsonNode tlfSlice = createSyntheticSlice(S0, T, r, q, vol, nStrikes);
        SurfaceRunner.SurfaceResult result = SurfaceRunner.processOptionSlice(tlfSlice);

        assertThat(result.logVariance()).isGreaterThan(0.0);
        assertThat(result.densitySlice()).isNotEmpty();

        double skewness = BL.calculateLogSkewness(result.densitySlice(), result.forwardPrice());
        // Synthetic symmetric Black-Scholes curve should yield skewness near 0.0
        assertThat(skewness).isCloseTo(0.0, within(0.25));
    }

    private JsonNode createSyntheticSlice(double S0, double T, double r, double q, double vol, int nStrikes) {
        var node = MAPPER.createObjectNode();
        node.put("S0", S0);
        node.put("T", T);
        node.put("r", r);
        node.put("q", q);
        node.put("N", 60);

        var quotesArr = node.putArray("quotes");
        double minK = S0 * 0.85;
        double maxK = S0 * 1.15;
        double step = (maxK - minK) / (nStrikes - 1);

        for (int i = 0; i < nStrikes; i++) {
            double K = minK + i * step;
            double cPx = com.eventrvcalc.core.surface.BS.callPrice(S0, K, T, r, q, vol);
            double pPx = com.eventrvcalc.core.surface.BS.putPrice(S0, K, T, r, q, vol);
            var qNode = quotesArr.addObject();
            qNode.put("strike", K);
            qNode.put("mid", K >= S0 ? cPx : pPx);
            qNode.put("eta", K >= S0 ? 1 : -1);
            qNode.put("callMid", cPx);
            qNode.put("putMid", pPx);
            qNode.put("callWeight", K >= S0 ? 1.0 : 0.0);
            qNode.put("putWeight", K < S0 ? 1.0 : 0.0);
        }
        return node;
    }
}

