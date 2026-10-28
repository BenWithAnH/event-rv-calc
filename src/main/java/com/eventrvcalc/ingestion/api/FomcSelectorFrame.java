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
    private final JComboBox<String> tickerCombo = new JComboBox<>(new String[]{"ALL (SPY, TLT, IEF, HYG)", "SPY", "TLT", "IEF", "HYG"});
    private final JComboBox<String> probSourceCombo = new JComboBox<>(new String[]{
            "Hybrid Consensus (ZQ + PM) (Default)",
            "Fed Funds ZQ (Institutional)",
            "Polymarket (Retail Crowd)",
            "Manual / Custom p"
    });
    private final JTextField customPField = new JTextField("0.50", 4);
    private final JCheckBox fetchYfCheckbox = new JCheckBox("Live yfinance", true);
    private final JCheckBox eventBullishCheckbox = new JCheckBox("Hike is Bullish", false);
    private final JTextField sigmaField = new JTextField("0.02", 6);
    private final JTextArea outputArea = new JTextArea(25, 85);
    private final JButton openPlotBtn = new JButton("Open Surface Plot (.png)");
    private final JPanel summaryBanner = new JPanel(new GridLayout(2, 1, 4, 4));
    private final JLabel summaryLine1 = new JLabel("Triangulated Probability: -- | Consensus: Awaiting Run", SwingConstants.LEFT);
    private final JLabel summaryLine2 = new JLabel("Cross-Asset Verdict: Awaiting Calculation (TLT 20Y Yield Shock vs SPY Equity Move)", SwingConstants.LEFT);
    private volatile List<Polymarket.Event> cachedEvents = List.of();
    private volatile File lastGeneratedPlot = new File("surface_exploration.png");
    private volatile File lastEventSliceFile = null;

    public FomcSelectorFrame() {
        super("Event RV Calc - FOMC, Bond Market & Options RV");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel northContainer = new JPanel();
        northContainer.setLayout(new BoxLayout(northContainer, BoxLayout.Y_AXIS));

        // Row 1: Selectors & Inputs
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        row1.add(new JLabel("FOMC Date:"));
        row1.add(dateCombo);
        row1.add(new JLabel("Ticker:"));
        row1.add(tickerCombo);
        row1.add(new JLabel("P-Source:"));
        row1.add(probSourceCombo);
        row1.add(new JLabel("Custom p:"));
        row1.add(customPField);
        customPField.setEnabled(false);
        row1.add(fetchYfCheckbox);
        row1.add(eventBullishCheckbox);
        row1.add(new JLabel("Manual sigma_event:"));
        row1.add(sigmaField);

        probSourceCombo.addActionListener(e -> {
            String sel = (String) probSourceCombo.getSelectedItem();
            boolean isCustom = sel != null && sel.startsWith("Manual");
            customPField.setEnabled(isCustom);
        });

        // Row 2: Action Buttons
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        JButton fetchBtn = new JButton("Fetch Market & Run Math Engine");
        fetchBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        openPlotBtn.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        row2.add(fetchBtn);
        row2.add(openPlotBtn);

        northContainer.add(row1);
        northContainer.add(row2);

        // Row 3: Triangulated Summary Banner
        summaryBanner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 10, 8, 10),
                BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(60, 110, 180), 2, true),
                        BorderFactory.createEmptyBorder(8, 12, 8, 12)
                )
        ));
        summaryBanner.setBackground(new Color(242, 246, 252));
        summaryBanner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        summaryBanner.setToolTipText("Click to view full verdict and summary details");
        summaryLine1.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        summaryLine1.setForeground(new Color(20, 45, 90));
        summaryLine2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        summaryLine2.setForeground(new Color(30, 60, 100));

        java.awt.event.MouseAdapter bannerClickListener = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                showFullVerdictDialog();
            }
        };
        summaryBanner.addMouseListener(bannerClickListener);
        summaryLine1.addMouseListener(bannerClickListener);
        summaryLine2.addMouseListener(bannerClickListener);

        summaryBanner.add(summaryLine1);
        summaryBanner.add(summaryLine2);
        northContainer.add(summaryBanner);

        add(northContainer, BorderLayout.NORTH);

        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(outputArea), BorderLayout.CENTER);

        fetchBtn.addActionListener(e -> evaluateSelectedDate());
        openPlotBtn.addActionListener(e -> openImageExternally());

        pack();
        setLocationRelativeTo(null);
        loadInitialData();
    }

    private void showFullVerdictDialog() {
        String line1 = summaryLine1.getText();
        String line2 = summaryLine2.getText();

        JTextArea textArea = new JTextArea(line1 + "\n\n" + line2);
        textArea.setWrapStyleWord(true);
        textArea.setLineWrap(true);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        textArea.setCaretPosition(0);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(550, 220));

        JOptionPane.showMessageDialog(
                this,
                scrollPane,
                "Cross-Asset Verdict & Summary",
                JOptionPane.INFORMATION_MESSAGE
        );
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
            if (Double.isNaN(sigmaManual) || Double.isInfinite(sigmaManual) || sigmaManual < 0.0) {
                outputArea.setText("Invalid sigma_event: must be a non-negative finite number.");
                return;
            }
        } catch (NumberFormatException ex) {
            outputArea.setText("Invalid sigma_event number: " + sigmaField.getText());
            return;
        }

        String chosenTicker = (String) tickerCombo.getSelectedItem();
        String chosenProbSource = (String) probSourceCombo.getSelectedItem();
        double parsedCustomP = 0.50;
        if (chosenProbSource != null && chosenProbSource.startsWith("Manual")) {
            try {
                parsedCustomP = Double.parseDouble(customPField.getText().trim());
                if (parsedCustomP > 1.0 && parsedCustomP <= 100.0) {
                    parsedCustomP /= 100.0;
                }
                if (parsedCustomP < 0.0 || parsedCustomP > 1.0) {
                    outputArea.setText("Invalid Custom p: must be between 0.0 and 1.0 (or 0% to 100%).");
                    return;
                }
            } catch (NumberFormatException ex) {
                outputArea.setText("Invalid Custom p number: " + customPField.getText());
                return;
            }
        }
        final double customPValue = parsedCustomP;
        final boolean fetchYf = fetchYfCheckbox.isSelected();
        final boolean isBullish = eventBullishCheckbox.isSelected();

        outputArea.setText(""); // Clear console/terminal area on rerun
        summaryLine1.setText("Triangulated Probability: Calculating... | Consensus: In Progress");
        summaryLine2.setText("Cross-Asset Verdict: Gathering multi-asset data...");
        outputArea.append("Gathering market data for FOMC Date: " + selectedDate + "...\n");

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
                BL.PolymarketTailBrackets tails = null;
                if (matchOpt.isPresent()) {
                    matchedEvent = matchOpt.get();
                    sb.append("Matched Polymarket Event:    ").append(matchedEvent.title()).append("\n");
                    sb.append(String.format("Polymarket Total Volume:     $%,.2f%n", matchedEvent.volume()));
                    pPolymarket = matchedEvent.yesProbability();
                    tails = matchedEvent.extractTailBrackets();
                    sb.append(String.format("Polymarket Hike Prob (p):    %.4f (%.2f%%)%n", pPolymarket, pPolymarket * 100.0));
                    sb.append(String.format("  -> Discovered Brackets: 50+ bps cut: %.2f%% | 25 bps cut: %.2f%% | 25 bps hike: %.2f%% | 50+ bps hike: %.2f%%%n%n",
                            tails.pCut50() * 100.0, tails.pCut25() * 100.0, tails.pHike25() * 100.0, tails.pHike50() * 100.0));
                } else {
                    sb.append("Polymarket: No direct market match found for ").append(selectedDate).append("\n\n");
                }

                // 2. Discover Layer B: CME 30-Day Fed Funds Futures (ZQ)
                sb.append("=== Layer B: Institutional Ground Truth (CME 30-Day Fed Funds Futures ZQ) ===\n");
                double pBonds = Double.NaN;
                double pBondsTailAdjusted = Double.NaN;
                BL.TailAdjustedCmeResult tailCmeResult = null;
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
                    sb.append(String.format("  CME Unadjusted Binary Prob        : %.4f (%.2f%%)%n",
                            fedFundsRes.pBonds(), fedFundsRes.pBonds() * 100.0));

                    // Tail-adjusted CME Calculation via Polymarket tail brackets
                    tailCmeResult = BL.solveTailAdjustedCme(fedFundsRes.deltaRatePct(), tails);
                    pBondsTailAdjusted = tailCmeResult.pBondsTailAdjusted();
                    sb.append(String.format("  Tail Bracket Drag / Contribution  : %+.4f bps%n", tailCmeResult.tailCorrectionBps()));
                    sb.append(String.format("  CME 25 bps Hike Prob (p_hike25_ZQ): %.4f (%.2f%%)%n",
                            tailCmeResult.pHike25Zq(), tailCmeResult.pHike25Zq() * 100.0));
                    sb.append(String.format("  CME Tail-Adjusted Hike Prob (ZQ)  : %.4f (%.2f%%)%n",
                            pBondsTailAdjusted, pBondsTailAdjusted * 100.0));
                } catch (Exception ex) {
                    sb.append("  Fed Funds Futures Ingestion Note: ").append(ex.getMessage()).append("\n");
                }

                // 3. Probability Comparison & Selection Matrix
                sb.append("\n=== Probability Source Comparison ===\n");
                if (!Double.isNaN(pPolymarket)) {
                    sb.append(String.format("  1. Polymarket (Retail Crowd)       : %.2f%%%n", pPolymarket * 100.0));
                }
                if (!Double.isNaN(pBonds)) {
                    sb.append(String.format("  2. Fed Funds ZQ (Binary)           : %.2f%%%n", pBonds * 100.0));
                    if (!Double.isNaN(pBondsTailAdjusted)) {
                        sb.append(String.format("  3. Fed Funds ZQ (Tail-Adjusted)    : %.2f%%%n", pBondsTailAdjusted * 100.0));
                    }
                }

                // Effective Rates Prob used in blend
                double effectiveRatesP = !Double.isNaN(pBondsTailAdjusted) ? pBondsTailAdjusted : pBonds;
                double hybridP;
                if (!Double.isNaN(effectiveRatesP) && !Double.isNaN(pPolymarket)) {
                    hybridP = 0.65 * effectiveRatesP + 0.35 * pPolymarket;
                    double rawSpread = (pBonds - pPolymarket) * 100.0;
                    double tailAdjSpread = (effectiveRatesP - pPolymarket) * 100.0;
                    sb.append(String.format("  Binary Divergence (CME - PM)       : %+.2f pts%n", rawSpread));
                    sb.append(String.format("  Tail-Adjusted Divergence (CME - PM): %+.2f pts (Gap shrank by %.2f pts)%n",
                            tailAdjSpread, Math.abs(rawSpread) - Math.abs(tailAdjSpread)));
                    sb.append(String.format("  Hybrid Consensus Blend (ZQ + PM)   : %.2f%%%n", hybridP * 100.0));
                } else if (!Double.isNaN(effectiveRatesP)) {
                    hybridP = effectiveRatesP;
                } else if (!Double.isNaN(pPolymarket)) {
                    hybridP = pPolymarket;
                } else {
                    hybridP = 0.50;
                }

                // Determine Active P based on user selection in dropdown:
                double activeP;
                String activeSourceName;
                if (chosenProbSource != null && chosenProbSource.startsWith("Polymarket")) {
                    activeP = !Double.isNaN(pPolymarket) ? pPolymarket : 0.50;
                    activeSourceName = "Polymarket (Retail Crowd)";
                } else if (chosenProbSource != null && chosenProbSource.startsWith("Fed Funds")) {
                    activeP = !Double.isNaN(effectiveRatesP) ? effectiveRatesP : 0.50;
                    activeSourceName = "Fed Funds ZQ (Institutional " + (!Double.isNaN(pBondsTailAdjusted) ? "Tail-Adjusted)" : "Binary)");
                } else if (chosenProbSource != null && chosenProbSource.startsWith("Manual")) {
                    activeP = customPValue;
                    activeSourceName = String.format("Manual / Custom p (%.1f%%)", customPValue * 100.0);
                } else {
                    // Default: Hybrid Consensus (ZQ + PM)
                    activeP = hybridP;
                    activeSourceName = "Hybrid Consensus (ZQ + PM) (Default)";
                }

                sb.append(String.format("  -> ACTIVE PROBABILITY APPLIED TO ALL SURFACES : %.2f%% (%s)%n%n",
                        activeP * 100.0, activeSourceName));

                // 4. Layer A: Options Processing
                Map<String, Double> discoveredSigmas = new java.util.concurrent.ConcurrentHashMap<>();
                if (fetchYf) {
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

                            double t1AnnVol = Math.sqrt(Math.max(0.0, optRes.vT1()) / Math.max(1e-6, t1Years));
                            double t2AnnVol = Math.sqrt(Math.max(0.0, optRes.vT2()) / Math.max(1e-6, t2Years));
                            double t3AnnVol = Math.sqrt(Math.max(0.0, optRes.vT3()) / Math.max(1e-6, t3Years));
                            double skewness = BL.calculateLogSkewness(optRes.t2Result().densitySlice(), optRes.t2Result().forwardPrice());

                            sb.append(String.format("  Spot S0: $%.2f | Expiries: T1=%s, T2=%s, T3=%s%n",
                                    optRes.t2Result().S0(), manifest.t1Expiry(), manifest.t2Expiry(), manifest.t3Expiry()));
                            sb.append(String.format("  Annualized Vols: T1=%.2f%% | T2=%.2f%% | T3=%.2f%%%n",
                                    t1AnnVol * 100.0, t2AnnVol * 100.0, t3AnnVol * 100.0));
                            sb.append(String.format("  Noise V1: %.8f/day | Cleaned Pure Event Move (sigma): %.4f (%.2f%%)%n",
                                    optRes.v1Day(), optRes.sigmaEvent(), optRes.sigmaEvent() * 100.0));
                            sb.append(String.format("  Breeden-Litzenberger Risk-Neutral Skewness: %+.4f (%s)%n",
                                    skewness, getSkewInterpretation(ticker, skewness)));

                            discoveredSigmas.put(ticker.toUpperCase(), optRes.sigmaEvent());
                            appendJumpCalculations(sb, ticker, activeP, optRes.sigmaEvent(), isBullish);
                            final double discoveredSigma = optRes.sigmaEvent();
                            SwingUtilities.invokeLater(() -> sigmaField.setText(String.format("%.4f", discoveredSigma)));
                        } catch (Exception ex) {
                            sb.append(String.format("  Could not fetch/process options for %s: %s%n", ticker, ex.getMessage()));
                            sb.append(String.format("  Zero-Drift Jump Solution for %s: N/A (Options SVI fit unavailable)%n", ticker));
                        }
                    }

                    if (lastEventSliceFile != null && lastEventSliceFile.exists()) {
                        // Generate the plot file silently in background without popping open an external viewer window
                        generatePlotTask(lastEventSliceFile, false);
                    }
                } else {
                    discoveredSigmas.put(chosenTicker != null ? chosenTicker.toUpperCase() : "SPY", sigmaManual);
                    appendJumpCalculations(sb, chosenTicker, activeP, sigmaManual, isBullish);
                }

                // 5. Synthesize 3-Venue Triangulated Number & Mispricing Analysis
                double sigmaTlt = discoveredSigmas.getOrDefault("TLT", sigmaManual);
                double sigmaSpy = discoveredSigmas.getOrDefault("SPY", sigmaManual);

                double ratesPForSynthesis = !Double.isNaN(pBondsTailAdjusted) ? pBondsTailAdjusted : pBonds;
                BL.CrossAssetTriangulation triangulation = BL.triangulateFomcVenues(
                        pPolymarket, ratesPForSynthesis, sigmaTlt, sigmaSpy, isBullish);

                sb.append("\n========================================================================================\n");
                sb.append("=== 3-VENUE FOMC EVENT SYNTHESIS & TRIANGULATION (PREDICTION + RATES + B-L SURFACES) ===\n");
                sb.append("========================================================================================\n");
                sb.append(String.format("Venue 1 — Retail Prediction Market (Polymarket)      : %s%n",
                        Double.isNaN(pPolymarket) ? "N/A" : String.format("%.2f%%", pPolymarket * 100.0)));
                sb.append(String.format("Venue 2 — Institutional Rates Market (Fed Funds ZQ) : %s%n",
                        Double.isNaN(pBonds) ? "N/A" : String.format("%.2f%% %s (Delta: %+.2f bps)",
                                (!Double.isNaN(pBondsTailAdjusted) ? pBondsTailAdjusted : pBonds) * 100.0,
                                (!Double.isNaN(pBondsTailAdjusted) ? "[Tail-Adjusted]" : "[Binary]"),
                                (fedFundsRes != null ? fedFundsRes.deltaRatePct() * 100.0 : 0.0))));
                sb.append(String.format("Venue 3 — B-L Implied Volatility Surfaces (TLT & SPY) :%n"));
                sb.append(String.format("   * TLT Pure Event Move: %.2f%% -> Implied 20Y Yield Shock: +/-%.2f bps (D_mod ~16.5y)%n",
                        sigmaTlt * 100.0, triangulation.tltImpliedYieldShockBps()));
                sb.append(String.format("     Move if Hike/Event: %+.2f bps yield | Move if Pause/Surprise: %+.2f bps yield%n",
                        triangulation.tltYieldMoveIfEventBps(), triangulation.tltYieldMoveIfNotEventBps()));
                sb.append(String.format("   * SPY Pure Event Move: %.2f%%%n", sigmaSpy * 100.0));
                sb.append(String.format("     Move if Hike/Event: %+.2f%% equity | Move if Pause/Surprise: %+.2f%% equity%n",
                        triangulation.spyMoveIfEventPct() * 100.0, triangulation.spyMoveIfNotEventPct() * 100.0));
                sb.append("----------------------------------------------------------------------------------------\n");
                sb.append(String.format("-> ACTIVE PROBABILITY APPLIED                 : %.2f%% (%s)%n", activeP * 100.0, activeSourceName));
                sb.append(String.format("-> TRIANGULATED HYBRID CONSENSUS PROB (p_Tri) : %.2f%%%n", triangulation.pTriangulated() * 100.0));
                sb.append(String.format("-> CROSS-ASSET EVENT VOL RATIO (TLT / SPY)    : %.2f  (Baseline: ~1.25x)%n", triangulation.tltToSpyVolRatio()));
                sb.append(String.format("-> TRIANGULATED FAIR SPY EVENT MOVE           : %.2f%% (Current Surface: %.2f%%, Edge: %+.2f%%)%n",
                        triangulation.triangulatedSpyFairSigma() * 100.0, sigmaSpy * 100.0, triangulation.spyVolEdgePct() * 100.0));
                sb.append(String.format("-> REGIME VERDICT & MISPRICING DIAGNOSIS      : [%s]%n", triangulation.regimeVerdict()));
                sb.append(String.format("   %s%n", triangulation.detailedExplanation()));
                sb.append("========================================================================================\n\n");

                // Update UI Banner on Swing Thread
                final BL.CrossAssetTriangulation finalTri = triangulation;
                final double finalPPolymarket = pPolymarket;
                final double finalPBonds = !Double.isNaN(pBondsTailAdjusted) ? pBondsTailAdjusted : pBonds;
                final double finalActiveP = activeP;
                final String finalActiveName = activeSourceName;
                SwingUtilities.invokeLater(() -> {
                    summaryLine1.setText(String.format(
                            "ACTIVE PROB: %.1f%% (%s) | Hybrid Consensus: %.1f%% (PM: %s | CME ZQ: %s) | 20Y Yield Shock: +/-%.1f bps | SPY Move: +/-%.2f%%",
                            finalActiveP * 100.0,
                            finalActiveName,
                            finalTri.pTriangulated() * 100.0,
                            Double.isNaN(finalPPolymarket) ? "N/A" : String.format("%.1f%%", finalPPolymarket * 100.0),
                            Double.isNaN(finalPBonds) ? "N/A" : String.format("%.1f%%", finalPBonds * 100.0),
                            finalTri.tltImpliedYieldShockBps(),
                            finalTri.sigmaSpy() * 100.0
                    ));
                    summaryLine2.setText(String.format(
                            "VERDICT: %s | Fair SPY Move: %.2f%% (Edge: %+.2f%%) — %s",
                            finalTri.regimeVerdict(),
                            finalTri.triangulatedSpyFairSigma() * 100.0,
                            finalTri.spyVolEdgePct() * 100.0,
                            finalTri.detailedExplanation()
                    ));
                });

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

    private void appendJumpCalculations(StringBuilder sb, String ticker, double p, double sigmaEvent, boolean isBullish) {
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(sigmaEvent, p, isBullish);

        sb.append(String.format("  Zero-Drift Jump Solution for %s (Martingale Drift=0.0):%n", ticker));
        sb.append(String.format("    Move if Fed HIKES (%5.2f%% prob) : %+.4f%%%n", p * 100.0, jump.moveIfEvent() * 100.0));
        sb.append(String.format("    Move if SURPRISE   (%5.2f%% prob) : %+.4f%%%n", (1.0 - p) * 100.0, jump.moveIfNotEvent() * 100.0));

        // Treasury Duration conversion to basis point yield shock
        if ("TLT".equalsIgnoreCase(ticker)) {
            double yieldShockHikeBps = -(jump.moveIfEvent() / BL.TLT_MODIFIED_DURATION) * 10000.0;
            double yieldShockSurpriseBps = -(jump.moveIfNotEvent() / BL.TLT_MODIFIED_DURATION) * 10000.0;
            double unsignedYieldShockBps = (sigmaEvent / BL.TLT_MODIFIED_DURATION) * 10000.0;
            sb.append(String.format("    [TLT D_mod ~16.5y Yield Shock]  : +/-%.2f bps unsigned 20Y Yield Move%n", unsignedYieldShockBps));
            sb.append(String.format("      -> Implied 20Y Yield Move if Hike     : %+.2f bps%n", yieldShockHikeBps));
            sb.append(String.format("      -> Implied 20Y Yield Move if Surprise : %+.2f bps%n", yieldShockSurpriseBps));
        } else if ("IEF".equalsIgnoreCase(ticker)) {
            double yieldShockHikeBps = -(jump.moveIfEvent() / BL.IEF_MODIFIED_DURATION) * 10000.0;
            double yieldShockSurpriseBps = -(jump.moveIfNotEvent() / BL.IEF_MODIFIED_DURATION) * 10000.0;
            double unsignedYieldShockBps = (sigmaEvent / BL.IEF_MODIFIED_DURATION) * 10000.0;
            sb.append(String.format("    [IEF D_mod ~7.5y Yield Shock]   : +/-%.2f bps unsigned 10Y Yield Move%n", unsignedYieldShockBps));
            sb.append(String.format("      -> Implied 10Y Yield Move if Hike     : %+.2f bps%n", yieldShockHikeBps));
            sb.append(String.format("      -> Implied 10Y Yield Move if Surprise : %+.2f bps%n", yieldShockSurpriseBps));
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FomcSelectorFrame().setVisible(true));
    }
}

