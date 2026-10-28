package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.BL;
import com.eventrvcalc.core.surface.SurfaceRunner;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bare-bones Swing UI to select an FOMC decision date, discover the matching
 * top-volume Polymarket event via Gamma API, and compute BL.java jump parameters.
 */
public class FomcSelectorFrame extends JFrame {

    private final JComboBox<LocalDate> dateCombo = new JComboBox<>();
    private final JComboBox<String> tickerCombo = new JComboBox<>(new String[]{"SPY", "TLT", "IEF", "HYG", "ALL (SPY, TLT, IEF, HYG)"});
    private final JComboBox<String> probSourceCombo = new JComboBox<>(new String[]{"Fed Funds ZQ (Institutional)", "Polymarket (Retail)"});
    private final JCheckBox fetchYfCheckbox = new JCheckBox("Live yfinance", true);
    private final JCheckBox eventBullishCheckbox = new JCheckBox("Hike is Bullish", false);
    private final JTextField sigmaField = new JTextField("0.02", 6);
    private final JTextArea outputArea = new JTextArea(25, 85);
    private final JButton openPlotBtn = new JButton("Open Surface Plot (.png)");
    private List<Polymarket.Event> cachedEvents = List.of();
    private volatile File lastGeneratedPlot = new File("surface_exploration.png");
    private volatile File lastEventSliceFile = null;

    public FomcSelectorFrame() {
        super("Event RV Calc - FOMC, Bond Market & Options RV");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 12));
        topPanel.setBorder(BorderFactory.createEmptyBorder(8, 10, 16, 10));
        topPanel.add(new JLabel("FOMC Date:"));
        topPanel.add(dateCombo);
        topPanel.add(new JLabel("Ticker:"));
        topPanel.add(tickerCombo);
        topPanel.add(new JLabel("P-Source:"));
        topPanel.add(probSourceCombo);
        topPanel.add(fetchYfCheckbox);
        topPanel.add(eventBullishCheckbox);
        topPanel.add(new JLabel("Manual sigma_event:"));
        topPanel.add(sigmaField);

        JButton fetchBtn = new JButton("Fetch Market & Run Math Engine");
        topPanel.add(fetchBtn);
        topPanel.add(openPlotBtn);
        add(topPanel, BorderLayout.NORTH);

        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(outputArea), BorderLayout.CENTER);

        fetchBtn.addActionListener(e -> evaluateSelectedDate());
        openPlotBtn.addActionListener(e -> openImageExternally());

        pack();
        setLocationRelativeTo(null);
        loadInitialData();
    }

    private void openImageExternally() {
        if (!lastGeneratedPlot.exists() && lastEventSliceFile != null) {
            outputArea.append("Generating surface plot from event slice: " + lastEventSliceFile.getName() + "...\n");
            generateAndOpenPlot(lastEventSliceFile);
            return;
        }
        if (lastGeneratedPlot.exists()) {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(lastGeneratedPlot);
                } else {
                    new ProcessBuilder("cmd", "/c", "start", "", lastGeneratedPlot.getAbsolutePath()).start();
                }
            } catch (Exception ex) {
                outputArea.append("Could not open plot image: " + ex.getMessage() + "\n");
            }
        } else {
            outputArea.append("No plot file found. Run 'Fetch Market & Run Math Engine' first.\n");
        }
    }

    private void generateAndOpenPlot(File sliceJson) {
        generatePlotTask(sliceJson, true);
    }

    private void generateAndOpenPlotInBackground(File sliceJson) {
        generatePlotTask(sliceJson, false);
    }

    private void generatePlotTask(File sliceJson, boolean openWhenReady) {
        SwingWorker<File, Void> plotWorker = new SwingWorker<>() {
            private Exception error;

            @Override
            protected File doInBackground() {
                try {
                    File outPng = new File("event_surface.png");
                    ProcessBuilder pb = new ProcessBuilder(
                            "py", "-3.13", "scripts/visualize_surface.py",
                            "--input-file", sliceJson.getAbsolutePath(),
                            "--output-png", outPng.getAbsolutePath()
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output;
                    try (var is = p.getInputStream()) {
                        output = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                    boolean completed = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (!completed) {
                        p.destroyForcibly();
                        throw new IOException("Plot generation timed out after 30s");
                    }
                    int code = p.exitValue();
                    if (code != 0) {
                        throw new IOException("Plot generation failed: " + output);
                    }
                    // Parse output in case fallback filename was used (e.g. event_surface_latest.png)
                    for (String line : output.split("\n")) {
                        if (line.contains("Saved visualization to")) {
                            String path = line.substring(line.indexOf("Saved visualization to") + "Saved visualization to".length()).trim();
                            return new File(path);
                        }
                    }
                    return outPng;
                } catch (Exception ex) {
                    error = ex;
                    return null;
                }
            }

            @Override
            protected void done() {
                if (error != null) {
                    outputArea.append("Plot generation error: " + error.getMessage() + "\n");
                } else {
                    try {
                        File resultPng = get();
                        if (resultPng != null && resultPng.exists()) {
                            lastGeneratedPlot = resultPng;
                            outputArea.append("Generated fresh event surface plot: " + lastGeneratedPlot.getAbsolutePath() + "\n");
                            if (openWhenReady) {
                                openImageExternally();
                            }
                        }
                    } catch (Exception ex) {
                        outputArea.append("Plot error: " + ex.getMessage() + "\n");
                    }
                }
            }
        };
        plotWorker.execute();
    }

    private void loadInitialData() {
        outputArea.setText("Fetching FOMC decision dates from federalreserve.gov and active FOMC markets from Polymarket...\n");
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            private List<LocalDate> dates = List.of();
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    dates = Polymarket.fetchFomcDecisionDates();
                    cachedEvents = Polymarket.fetchActiveFomcEvents();
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    outputArea.append("Error loading initial data: " + error.getMessage() + "\n");
                    return;
                }
                LocalDate cutoff = LocalDate.now().minusDays(7);
                for (LocalDate d : dates) {
                    if (!d.isBefore(cutoff)) {
                        dateCombo.addItem(d);
                    }
                }
                outputArea.append(String.format("Loaded %d upcoming FOMC decision dates and %d active Polymarket FOMC events.%n",
                        dateCombo.getItemCount(), cachedEvents.size()));
                outputArea.append("Select an FOMC date above and click 'Fetch Market & Run Math Engine' to begin calculations.\n");
                if (dateCombo.getItemCount() > 0) {
                    dateCombo.setSelectedIndex(0);
                }
            }
        };
        worker.execute();
    }

    private void evaluateSelectedDate() {
        LocalDate selectedDate = (LocalDate) dateCombo.getSelectedItem();
        if (selectedDate == null) return;

        double sigmaManual;
        try {
            sigmaManual = Double.parseDouble(sigmaField.getText().trim());
        } catch (NumberFormatException ex) {
            outputArea.setText("Invalid sigma_event number: " + sigmaField.getText());
            return;
        }

        String chosenTicker = (String) tickerCombo.getSelectedItem();
        String chosenProbSource = (String) probSourceCombo.getSelectedItem();
        boolean useBondsProb = chosenProbSource != null && chosenProbSource.startsWith("Fed Funds");

        outputArea.setText("Gathering market data for FOMC Date: " + selectedDate + "...\n");

        SwingWorker<Void, String> mainWorker = new SwingWorker<>() {
            private final StringBuilder sb = new StringBuilder();
            private Polymarket.Event matchedEvent;
            private YFinanceOptionsIngestion.FedFundsProbabilityResult fedFundsRes;

            @Override
            protected Void doInBackground() {
                sb.append("Selected FOMC Decision Date: ").append(selectedDate).append("\n");

                // 1. Discover Polymarket event
                Optional<Polymarket.Event> matchOpt = Polymarket.findFomcEventForDate(selectedDate, cachedEvents);
                double pPolymarket = Double.NaN;
                if (matchOpt.isPresent()) {
                    matchedEvent = matchOpt.get();
                    sb.append("Matched Polymarket Event:    ").append(matchedEvent.title()).append("\n");
                    sb.append(String.format("Polymarket Total Volume:     $%,.2f%n", matchedEvent.volume()));
                    pPolymarket = matchedEvent.yesProbability();
                    sb.append(String.format("Polymarket Hike Prob (p):    %.4f (%.2f%%)%n%n", pPolymarket, pPolymarket * 100.0));
                } else {
                    sb.append("Polymarket: No direct market match found for ").append(selectedDate).append("\n\n");
                }

                // 2. Discover Layer B: CME 30-Day Fed Funds Futures (ZQ)
                sb.append("=== Layer B: Institutional Ground Truth (CME 30-Day Fed Funds Futures ZQ) ===\n");
                double pBonds = Double.NaN;
                try {
                    fedFundsRes = YFinanceOptionsIngestion.fetchFedFundsProbability(selectedDate, null);
                    pBonds = fedFundsRes.pBonds();
                    sb.append(String.format("  Pre-FOMC Contract  (%s) : Price = %.4f -> Implied Rate = %.4f%%%n",
                            fedFundsRes.preContract(), fedFundsRes.prePrice(), fedFundsRes.ratePrePct()));
                    sb.append(String.format("  Meeting Contract   (%s) : Price = %.4f%n",
                            fedFundsRes.meetingContract(), fedFundsRes.meetingPrice()));
                    sb.append(String.format("  Day-Weighted Post-FOMC Target Rate : %.4f%%%n", fedFundsRes.ratePostImpliedPct()));
                    sb.append(String.format("  Implied Rate Change (Delta)       : %+.2f bps (%s)%n",
                            fedFundsRes.deltaRatePct() * 100.0, fedFundsRes.isHikeImplied() ? "HIKE implied" : "CUT implied"));
                    sb.append(String.format("  CME Day-Weighted Prob (p_Bonds)    : %.4f (%.2f%%)%n",
                            fedFundsRes.pBonds(), fedFundsRes.pBonds() * 100.0));
                    sb.append(String.format("  Unadjusted Month-Avg Prob         : %.4f (%.2f%%)%n",
                            fedFundsRes.pBondsUnadjusted(), fedFundsRes.pBondsUnadjusted() * 100.0));
                } catch (Exception ex) {
                    sb.append("  Fed Funds Futures Ingestion Note: ").append(ex.getMessage()).append("\n");
                }

                // 3. Probability Comparison Matrix
                sb.append("\n=== Probability Source Comparison ===\n");
                if (!Double.isNaN(pPolymarket)) {
                    sb.append(String.format("  Polymarket (Retail)       : %.2f%%%n", pPolymarket * 100.0));
                }
                if (!Double.isNaN(pBonds)) {
                    sb.append(String.format("  Fed Funds ZQ (Institutional): %.2f%%%n", pBonds * 100.0));
                }
                if (!Double.isNaN(pPolymarket) && !Double.isNaN(pBonds)) {
                    double spread = (pBonds - pPolymarket) * 100.0;
                    sb.append(String.format("  Institutional Divergence   : %+.2f pts (%s)%n",
                            spread, spread > 0 ? "Bonds price HIGHER hike probability than Retail" : "Retail pricing MORE aggressive than Bonds"));
                }

                double activeP = useBondsProb && !Double.isNaN(pBonds) ? pBonds : (!Double.isNaN(pPolymarket) ? pPolymarket : 0.50);
                String activeSourceName = useBondsProb && !Double.isNaN(pBonds) ? "Fed Funds ZQ (Institutional)" : "Polymarket (Retail)";
                sb.append(String.format("  -> ACTIVE PROBABILITY USED : %.2f%% (%s)%n%n", activeP * 100.0, activeSourceName));

                // 4. Layer A: Options Processing
                if (fetchYfCheckbox.isSelected()) {
                    sb.append("=== Layer A: Multi-Asset Options RV & BL Density Decomposition ===\n");
                    List<String> tickersToRun = "ALL (SPY, TLT, IEF, HYG)".equalsIgnoreCase(chosenTicker)
                            ? YFinanceOptionsIngestion.SUPPORTED_TICKERS
                            : List.of(chosenTicker != null ? chosenTicker : "SPY");

                    for (String ticker : tickersToRun) {
                        sb.append(String.format("\n--- Processing %s ---%n", ticker));
                        try {
                            var manifest = YFinanceOptionsIngestion.fetchEventOptions(ticker, selectedDate, null);
                            var optRes = YFinanceOptionsIngestion.computePureEventMove(manifest);
                            lastEventSliceFile = manifest.t2Path().toFile();

                            double t1Years = optRes.t1Result().T();
                            double t2Years = optRes.t2Result().T();
                            double t3Years = optRes.t3Result().T();

                            double t1AnnVol = Math.sqrt(optRes.vT1() / Math.max(1e-6, t1Years));
                            double t2AnnVol = Math.sqrt(optRes.vT2() / Math.max(1e-6, t2Years));
                            double t3AnnVol = Math.sqrt(optRes.vT3() / Math.max(1e-6, t3Years));
                            double skewness = BL.calculateLogSkewness(optRes.t2Result().densitySlice(), optRes.t2Result().forwardPrice());

                            sb.append(String.format("  Spot S0: $%.2f | Expiries: T1=%s, T2=%s, T3=%s%n",
                                    optRes.t2Result().S0(), manifest.t1Expiry(), manifest.t2Expiry(), manifest.t3Expiry()));
                            sb.append(String.format("  Annualized Vols: T1=%.2f%% | T2=%.2f%% | T3=%.2f%%%n",
                                    t1AnnVol * 100.0, t2AnnVol * 100.0, t3AnnVol * 100.0));
                            sb.append(String.format("  Noise V1: %.8f/day | Cleaned Pure Event Move (sigma): %.4f (%.2f%%)%n",
                                    optRes.v1Day(), optRes.sigmaEvent(), optRes.sigmaEvent() * 100.0));
                            sb.append(String.format("  Breeden-Litzenberger Risk-Neutral Skewness: %+.4f (%s)%n",
                                    skewness, getSkewInterpretation(ticker, skewness)));

                            appendJumpCalculations(sb, ticker, activeP, optRes.sigmaEvent());
                            final double discoveredSigma = optRes.sigmaEvent();
                            SwingUtilities.invokeLater(() -> sigmaField.setText(String.format("%.4f", discoveredSigma)));
                        } catch (Exception ex) {
                            sb.append(String.format("  Could not fetch/process options for %s: %s%n", ticker, ex.getMessage()));
                            appendJumpCalculations(sb, ticker, activeP, sigmaManual);
                        }
                    }

                    if (lastEventSliceFile != null && lastEventSliceFile.exists()) {
                        generateAndOpenPlotInBackground(lastEventSliceFile);
                    }
                } else {
                    appendJumpCalculations(sb, chosenTicker, activeP, sigmaManual);
                }

                return null;
            }

            @Override
            protected void done() {
                outputArea.setText(sb.toString());
            }
        };

        mainWorker.execute();
    }

    private static String getSkewInterpretation(String ticker, double skew) {
        if ("TLT".equalsIgnoreCase(ticker)) {
            return skew >= -0.1 ? "Symmetric / Call-Demand Smile (Flight to Safety)" : "Mild Put Tilt";
        } else if ("HYG".equalsIgnoreCase(ticker)) {
            return skew < -0.2 ? "Severe Left-Tail Credit Spread / Default Risk Blowout" : "Normal Credit Skew";
        } else if ("IEF".equalsIgnoreCase(ticker)) {
            return "Benchmark 10Y Duration Smile";
        } else {
            return skew < -0.3 ? "Typical Equity Smirk (Downside Crash Protection)" : "Equity Vol Smile";
        }
    }

    private void appendJumpCalculations(StringBuilder sb, String ticker, double p, double sigmaEvent) {
        boolean isBullish = eventBullishCheckbox.isSelected();
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(sigmaEvent, p, isBullish);

        sb.append(String.format("  Zero-Drift Jump Solution for %s (Martingale Drift=0.0):%n", ticker));
        sb.append(String.format("    Move if Fed HIKES (%5.2f%% prob) : %+.4f%%%n", p * 100.0, jump.moveIfEvent() * 100.0));
        sb.append(String.format("    Move if SURPRISE   (%5.2f%% prob) : %+.4f%%%n", (1.0 - p) * 100.0, jump.moveIfNotEvent() * 100.0));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FomcSelectorFrame().setVisible(true));
    }
}

