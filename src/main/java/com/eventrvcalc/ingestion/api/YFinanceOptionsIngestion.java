package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.SurfaceRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Ingestion coordinator for real options data via yfinance Python script
 * or local test JSON files.
 */
public class YFinanceOptionsIngestion {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_SYMBOL_PATTERN = Pattern.compile("^[A-Za-z0-9^.=_\\-]+$");

    public static final List<String> SUPPORTED_TICKERS = List.of("SPY", "TLT", "IEF", "HYG");

    public record OptionsManifest(
            String symbol,
            String eventDate,
            String t1Expiry,
            String t2Expiry,
            String t3Expiry,
            double tradingDaysT1ToT2,
            double tradingDaysT2ToT3,
            Path t1Path,
            Path t2Path,
            Path t3Path
    ) {}

    public record FedFundsProbabilityResult(
            String preContract,
            String meetingContract,
            String postContract,
            double prePrice,
            double meetingPrice,
            double ratePrePct,
            double ratePostImpliedPct,
            double deltaRatePct,
            double rawDeltaRatePct,
            double pBonds,
            double pBondsUnadjusted,
            boolean isHikeImplied
    ) {
        public FedFundsProbabilityResult(
                double ratePrePct,
                double ratePostImpliedPct,
                double deltaRatePct,
                double pBonds,
                boolean isHikeImplied) {
            this(null, null, null, 100.0 - ratePrePct, 0.0, ratePrePct, ratePostImpliedPct,
                    deltaRatePct, deltaRatePct, pBonds, pBonds, isHikeImplied);
        }
    }

    /**
     * Executes fetch_options_yfinance.py to download Fed Funds Futures data for the target FOMC date.
     */
    public static FedFundsProbabilityResult fetchFedFundsProbability(LocalDate eventDate, Path outputDir)
            throws IOException, InterruptedException {
        File outDir = outputDir != null ? outputDir.toFile() : new File("target/options");
        if (!outDir.exists() && !outDir.mkdirs() && !outDir.isDirectory()) {
            throw new IOException("Failed to create options output directory: " + outDir.getAbsolutePath());
        }

        // ponytail: Targets py -3.13 specifically where yfinance/scipy C-extensions are installed.
        ProcessBuilder pb = new ProcessBuilder(
                "py", "-3.13", "scripts/fetch_options_yfinance.py",
                "--fetch-zq",
                "--event-date", eventDate.toString(),
                "--output-dir", outDir.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        java.util.concurrent.CompletableFuture<byte[]> streamFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try (var is = proc.getInputStream()) {
                        return is.readAllBytes();
                    } catch (IOException e) {
                        return new byte[0];
                    }
                });
        boolean finished = proc.waitFor(45, TimeUnit.SECONDS);
        if (!finished) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            streamFuture.cancel(true);
            throw new IOException("fetch_options_yfinance.py --fetch-zq timed out after 45s");
        }
        byte[] outputBytes;
        try {
            outputBytes = streamFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            outputBytes = new byte[0];
        }
        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            String err = new String(outputBytes, StandardCharsets.UTF_8);
            throw new IOException("fetch_options_yfinance.py --fetch-zq failed (exit " + exitCode + "): " + err);
        }

        File zqFile = new File(outDir, "zq_fomc_" + eventDate + ".json");
        if (!zqFile.exists()) {
            throw new IOException("Fed Funds file not found: " + zqFile.getAbsolutePath());
        }

        JsonNode root = MAPPER.readTree(zqFile);
        return new FedFundsProbabilityResult(
                root.path("preContract").asText(""),
                root.path("meetingContract").asText(""),
                root.path("postContract").asText(""),
                root.path("prePrice").asDouble(),
                root.path("meetingPrice").asDouble(),
                root.path("ratePrePct").asDouble(),
                root.path("ratePostImpliedPct").asDouble(),
                root.path("deltaRatePct").asDouble(),
                root.path("rawDeltaRatePct").asDouble(),
                root.path("pBonds").asDouble(),
                root.path("pBondsUnadjusted").asDouble(),
                root.path("isHikeImplied").asBoolean()
        );
    }

    /**
     * Computes implied post-meeting target Fed Funds rate and probability of 25bps move
     * from pre-meeting and meeting-month 30-Day Fed Funds futures prices.
     */
    public static FedFundsProbabilityResult computeFedFundsProbability(
            double prePrice, double meetingPrice, Double postPrice, LocalDate meetingDate, boolean effectiveDateWeighted) {
        double rPre = 100.0 - prePrice;
        int d = meetingDate.getDayOfMonth();
        int nDays = meetingDate.lengthOfMonth();
        int daysPre = effectiveDateWeighted ? Math.min(nDays - 1, d) : (d - 1);
        int daysPost = Math.max(1, nDays - daysPre);

        double rPost;
        if (daysPost <= 2 && postPrice != null) {
            rPost = 100.0 - postPrice;
        } else {
            double rAvg = 100.0 - meetingPrice;
            rPost = (nDays * rAvg - daysPre * rPre) / daysPost;
        }

        double delta = rPost - rPre;
        double p = Math.min(1.0, Math.max(0.0, Math.abs(delta) / 0.25));
        boolean isHike = delta >= 0.0;
        return new FedFundsProbabilityResult(rPre, rPost, delta, p, isHike);
    }

    public static FedFundsProbabilityResult computeFedFundsProbability(
            double prePrice, double meetingPrice, Double postPrice, LocalDate meetingDate) {
        return computeFedFundsProbability(prePrice, meetingPrice, postPrice, meetingDate, true);
    }

    public static FedFundsProbabilityResult computeFedFundsProbability(
            double prePrice, double meetingPrice, LocalDate meetingDate, boolean effectiveDateWeighted) {
        return computeFedFundsProbability(prePrice, meetingPrice, null, meetingDate, effectiveDateWeighted);
    }

    public static FedFundsProbabilityResult computeFedFundsProbability(
            double prePrice, double meetingPrice, LocalDate meetingDate) {
        return computeFedFundsProbability(prePrice, meetingPrice, null, meetingDate, true);
    }

    /**
     * Executes fetch_options_yfinance.py to download options around the target event date.
     */
    public static OptionsManifest fetchEventOptions(String symbol, LocalDate eventDate, Path outputDir)
            throws IOException, InterruptedException {
        String sym = (symbol == null || symbol.isBlank()) ? "SPY" : symbol.trim().toUpperCase();
        if (!SAFE_SYMBOL_PATTERN.matcher(sym).matches()) {
            throw new IllegalArgumentException("Invalid ticker symbol format: " + symbol);
        }
        File outDir = outputDir != null ? outputDir.toFile() : new File("target/options");
        if (!outDir.exists() && !outDir.mkdirs() && !outDir.isDirectory()) {
            throw new IOException("Failed to create options output directory: " + outDir.getAbsolutePath());
        }

        // ponytail: Targets py -3.13 specifically where yfinance/scipy C-extensions are installed.
        ProcessBuilder pb = new ProcessBuilder(
                "py", "-3.13", "scripts/fetch_options_yfinance.py",
                "--symbol", sym,
                "--event-date", eventDate.toString(),
                "--output-dir", outDir.getAbsolutePath()
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        java.util.concurrent.CompletableFuture<byte[]> streamFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try (var is = proc.getInputStream()) {
                        return is.readAllBytes();
                    } catch (IOException e) {
                        return new byte[0];
                    }
                });
        boolean finished = proc.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
            streamFuture.cancel(true);
            throw new IOException("fetch_options_yfinance.py timed out after 60s for symbol " + sym);
        }
        byte[] outputBytes;
        try {
            outputBytes = streamFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            outputBytes = new byte[0];
        }
        int exitCode = proc.exitValue();
        if (exitCode != 0) {
            String err = new String(outputBytes, StandardCharsets.UTF_8);
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
                root.path("tradingDaysT1ToT2").asDouble(1.0),
                root.path("tradingDaysT2ToT3").asDouble(5.0),
                Path.of(root.path("t1File").asText()),
                Path.of(root.path("t2File").asText()),
                Path.of(root.path("t3File").asText())
        );
    }

    /**
     * Loads the 3 event option slices and runs the complete SurfaceRunner/BL pure event move decomposition,
     * subtracting (tradingDaysT1ToT2 * V1) noise variance across the exact window gap.
     */
    public static SurfaceRunner.EventPureMoveResult computePureEventMove(OptionsManifest manifest) throws IOException {
        JsonNode t1Root = MAPPER.readTree(manifest.t1Path().toFile());
        JsonNode t2Root = MAPPER.readTree(manifest.t2Path().toFile());
        JsonNode t3Root = MAPPER.readTree(manifest.t3Path().toFile());

        return SurfaceRunner.calculateEventWindow(
                t1Root, t2Root, t3Root,
                manifest.tradingDaysT2ToT3(),
                manifest.tradingDaysT1ToT2()
        );
    }

    public static SurfaceRunner.EventPureMoveResult computePureEventMove(OptionsManifest manifest,
                                                                         double tradingDaysT2ToT3) throws IOException {
        JsonNode t1Root = MAPPER.readTree(manifest.t1Path().toFile());
        JsonNode t2Root = MAPPER.readTree(manifest.t2Path().toFile());
        JsonNode t3Root = MAPPER.readTree(manifest.t3Path().toFile());

        return SurfaceRunner.calculateEventWindow(t1Root, t2Root, t3Root, tradingDaysT2ToT3, manifest.tradingDaysT1ToT2());
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

