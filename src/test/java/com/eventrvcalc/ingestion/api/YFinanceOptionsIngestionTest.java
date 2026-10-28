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

