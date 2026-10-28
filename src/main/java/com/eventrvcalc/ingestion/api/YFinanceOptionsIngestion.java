package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.SurfaceRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Ingestion coordinator for real options data via yfinance Python script
 * or local test JSON files.
 */
public class YFinanceOptionsIngestion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record OptionsManifest(
            String symbol,
            String eventDate,
            String t1Expiry,
            String t2Expiry,
            String t3Expiry,
            Path t1Path,
            Path t2Path,
            Path t3Path
    ) {}

    /**
     * Executes fetch_options_yfinance.py to download SPY options around the target event date.
     */
    public static OptionsManifest fetchEventOptions(String symbol, LocalDate eventDate, Path outputDir)
            throws IOException, InterruptedException {
        String sym = (symbol == null || symbol.isBlank()) ? "SPY" : symbol.toUpperCase();
        File outDir = outputDir != null ? outputDir.toFile() : new File("target/options");
        if (!outDir.exists()) outDir.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(
                "py", "-3.13", "scripts/fetch_options_yfinance.py",
                "--symbol", sym,
                "--event-date", eventDate.toString(),
                "--output-dir", outDir.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            String err = new String(proc.getInputStream().readAllBytes());
            throw new IOException("fetch_options_yfinance.py failed (exit " + exitCode + "): " + err);
        }

        File manifestFile = new File(outDir, sym.toLowerCase() + "_event_manifest.json");
        if (!manifestFile.exists()) {
            throw new IOException("Manifest file not found: " + manifestFile.getAbsolutePath());
        }

        JsonNode root = MAPPER.readTree(manifestFile);
        return new OptionsManifest(
                root.path("symbol").asText(sym),
                root.path("eventDate").asText(eventDate.toString()),
                root.path("t1Expiry").asText(),
                root.path("t2Expiry").asText(),
                root.path("t3Expiry").asText(),
                Path.of(root.path("t1File").asText()),
                Path.of(root.path("t2File").asText()),
                Path.of(root.path("t3File").asText())
        );
    }

    /**
     * Loads the 3 event option slices and runs the complete SurfaceRunner/BL pure event move decomposition.
     */
    public static SurfaceRunner.EventPureMoveResult computePureEventMove(OptionsManifest manifest,
                                                                         double tradingDaysT2ToT3) throws IOException {
        JsonNode t1Root = MAPPER.readTree(manifest.t1Path().toFile());
        JsonNode t2Root = MAPPER.readTree(manifest.t2Path().toFile());
        JsonNode t3Root = MAPPER.readTree(manifest.t3Path().toFile());

        return SurfaceRunner.calculateEventWindow(t1Root, t2Root, t3Root, tradingDaysT2ToT3, 1.0);
    }

    /**
     * Utility method to run the pipeline against pre-existing test JSON data files.
     */
    public static SurfaceRunner.EventPureMoveResult computePureEventMoveFromFiles(Path t1Path, Path t2Path, Path t3Path,
                                                                                 double tradingDaysT2ToT3) throws IOException {
        JsonNode t1Root = MAPPER.readTree(t1Path.toFile());
        JsonNode t2Root = MAPPER.readTree(t2Path.toFile());
        JsonNode t3Root = MAPPER.readTree(t3Path.toFile());

        return SurfaceRunner.calculateEventWindow(t1Root, t2Root, t3Root, tradingDaysT2ToT3, 1.0);
    }
}

