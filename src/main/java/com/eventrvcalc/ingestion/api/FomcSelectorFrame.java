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
    private final JButton fetchBtn = new JButton("Fetch Market & Run Math Engine");
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
                    new ProcessBuilder("cmd", "/c", "start", "", "\"" + lastGeneratedPlot.getAbsolutePath() + "\"").start();
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
                    var streamFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                        try (var is = p.getInputStream()) {
                            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        } catch (IOException e) {
                            return "";
                        }
                    });
                    boolean completed = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (!completed) {
                        p.descendants().forEach(ProcessHandle::destroyForcibly);
                        p.destroyForcibly();
                        streamFuture.cancel(true);
                        throw new IOException("Plot generation timed out after 30s");
                    }
                    String output;
                    try {
                        output = streamFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        output = "";
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
        fetchBtn.setEnabled(false);

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
                    sb.append(String.format("  -> Discovered Brackets: 50+ bps cut: %.2f%% | 25 bps cut: %.2f%% | No change: %.2f%% | 25 bps hike: %.2f%% | 50+ bps hike: %.2f%%%n%n",
                            tails.pCut50() * 100.0, tails.pCut25() * 100.0, tails.pNoChange() * 100.0, tails.pHike25() * 100.0, tails.pHike50() * 100.0));
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

                    // Year-end turn premium adjustment for December FOMC contracts:
                    // ZQ settles on the 31-day monthly average of EFFR. An assumed year-end turn spike
                    // of +18.7 bps on Dec 31 (assumption based on recent historical year-end balance-sheet turn prints;
                    // drives ~1.3 pts of headline probability) only impacts the last day of December.
                    // Spread over 22 post-FOMC days (meeting Dec 9, effective Dec 10):
                    // Delta_r_turn_post = (1 day / 22 post-days) * 18.7 bps = 0.85 bps (monthly average impact = 18.7/31 = 0.60 bps).
                    boolean isDecemberMeeting = selectedDate != null && selectedDate.getMonthValue() == 12;
                    double derivedTurnBps = isDecemberMeeting ? 0.85 : 0.0;
                    tailCmeResult = BL.solveTailAdjustedCme(fedFundsRes.deltaRatePct(), tails, derivedTurnBps);
                    pBondsTailAdjusted = tailCmeResult.pBondsTurnAndTailAdjusted();
                    if (tails != null) {
                        double polyExpBps = tails.expectedRateChangeBps();
                        double cmeExpBps = fedFundsRes.deltaRatePct() * 100.0;
                        sb.append(String.format("  Polymarket Implied Expected Move  : %+.2f bps (vs CME ZQ %+.2f bps, Spread: %+.2f bps)%n",
                                polyExpBps, cmeExpBps, polyExpBps - cmeExpBps));
                        if (isDecemberMeeting) {
                            BL.TailAdjustedCmeResult res2Bp = BL.solveTailAdjustedCme(fedFundsRes.deltaRatePct(), tails, 2.0);
                            sb.append(String.format("  [Year-End Turn Premium Analysis]  :%n"));
                            sb.append(String.format("     * Turn Assumption: Assumed Dec 31 single-day EFFR spike of +18.7 bps (assumption based on recent historical year-end prints; drives ~1.3 pts of headline probability) spread over 22 post-meeting days = +%.2f bps on post-rate (+%.2f bps on 31d month avg)%n",
                                    derivedTurnBps, derivedTurnBps * (22.0 / 31.0)));
                            sb.append(String.format("     * Baseline Turn Assumption (0.85 bps post-rate / 18.7 bps Dec 31 spike) -> Rate Delta: %+.2f bps | Binary: %.2f%% | Any-Hike: %.2f%%%n",
                                    tailCmeResult.deltaRateTurnAdjustedBps(), (tailCmeResult.deltaRateTurnAdjustedBps() / 25.0) * 100.0, tailCmeResult.pBondsTurnAndTailAdjusted() * 100.0));
                            sb.append(String.format("     * Conservative (2.00 bps) -> Rate Delta: %+.2f bps | Binary: %.2f%% | Any-Hike: %.2f%% (Requires ~44 bps single-day post-meeting spike [2.0x22], or ~62 bps on 31d month avg; either is implausible)%n",
                                    res2Bp.deltaRateTurnAdjustedBps(), (res2Bp.deltaRateTurnAdjustedBps() / 25.0) * 100.0, res2Bp.pBondsTurnAndTailAdjusted() * 100.0));
                        }
                    }
                    sb.append(String.format("  [Diagnostic] Tail-Implied ZQ 25bp : %.4f (%.2f%%)%n",
                            tailCmeResult.pHike25Zq(), tailCmeResult.pHike25Zq() * 100.0));
                    sb.append(String.format("  [Diagnostic] Active Turn+Tail ZQ  : %.4f (%.2f%%) [Propagated downstream on any-hike basis]%n",
                            pBondsTailAdjusted, pBondsTailAdjusted * 100.0));
                } catch (Exception ex) {
                    sb.append("  Fed Funds Futures Ingestion Note: ").append(ex.getMessage()).append("\n");
                }

                // 3. Probability Comparison & Selection Matrix
                // Use consistently propagated turn-and-tail adjusted institutional rate probability (any-hike basis):
                double effectiveRatesP = !Double.isNaN(pBondsTailAdjusted) ? pBondsTailAdjusted : pBonds;

                sb.append("\n=== Probability Source Comparison ===\n");
                if (!Double.isNaN(pPolymarket)) {
                    sb.append(String.format("  1. Polymarket (Retail Crowd Any-Hike)     : %.2f%%%n", pPolymarket * 100.0));
                }
                if (!Double.isNaN(effectiveRatesP)) {
                    sb.append(String.format("  2. Fed Funds ZQ (Inst. Turn+Tail Any-Hike): %.2f%% (Unadjusted Binary: %.2f%%)%n",
                            effectiveRatesP * 100.0, pBonds * 100.0));
                }

                double hybridP;
                if (!Double.isNaN(effectiveRatesP) && !Double.isNaN(pPolymarket)) {
                    // Blend 65% CME Fed Funds (institutional SOFR/EFFR balance-sheet hedging liquidity)
                    // + 35% Polymarket (crowd positioning and tail-bracket discovery) on identical any-hike basis:
                    hybridP = 0.65 * effectiveRatesP + 0.35 * pPolymarket;
                    double divergencePts = (effectiveRatesP - pPolymarket) * 100.0;
                    sb.append(String.format("  Institutional vs Retail Divergence        : %+.2f pts (CME ZQ Turn+Tail - Polymarket)%n", divergencePts));
                    sb.append(String.format("  Hybrid Consensus Blend (ZQ + PM)          : %.2f%% [65%% CME ZQ Institutional + 35%% Polymarket Retail Crowd (Judgmental Weighting)]%n",
                            hybridP * 100.0));
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
                    activeSourceName = "Polymarket (Retail Crowd Any-Hike)";
                } else if (chosenProbSource != null && chosenProbSource.startsWith("Fed Funds")) {
                    activeP = !Double.isNaN(effectiveRatesP) ? effectiveRatesP : 0.50;
                    activeSourceName = "Fed Funds ZQ (Inst. Turn+Tail Adjusted)";
                } else if (chosenProbSource != null && chosenProbSource.startsWith("Manual")) {
                    activeP = customPValue;
                    activeSourceName = String.format("Manual / Custom p (%.1f%%)", customPValue * 100.0);
                } else {
                    // Default: Hybrid Consensus (ZQ + PM)
                    activeP = hybridP;
                    activeSourceName = "Hybrid Consensus (ZQ + PM) [65/35 Judgmental] (Default)";
                }

                sb.append(String.format("  -> ACTIVE PROBABILITY APPLIED TO ALL SURFACES : %.2f%% (%s)%n%n",
                        activeP * 100.0, activeSourceName));

                // 4. Layer A: Options Processing
                Map<String, Double> discoveredSigmas = new java.util.concurrent.ConcurrentHashMap<>();
                Map<String, Double> discoveredV1 = new java.util.concurrent.ConcurrentHashMap<>();
                Map<String, Double> discoveredVT2 = new java.util.concurrent.ConcurrentHashMap<>();
                Map<String, Double> discoveredSkews = new java.util.concurrent.ConcurrentHashMap<>();
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

                            double effectiveSigma = optRes.sigmaEvent();
                            boolean durationRegularized = false;
                            if ("IEF".equalsIgnoreCase(ticker) && discoveredSigmas.containsKey("TLT")) {
                                double tltSig = discoveredSigmas.get("TLT");
                                double durationRatio = BL.IEF_MODIFIED_DURATION / BL.TLT_MODIFIED_DURATION;
                                if (tltSig > 1e-4) {
                                    if (effectiveSigma <= 1e-4 || effectiveSigma > 1.25 * tltSig * durationRatio) {
                                        effectiveSigma = tltSig * durationRatio;
                                        durationRegularized = true;
                                    }
                                }
                            } else if ("TLT".equalsIgnoreCase(ticker) && effectiveSigma <= 1e-4 && discoveredSigmas.containsKey("IEF")) {
                                double iefSig = discoveredSigmas.get("IEF");
                                if (iefSig > 1e-4) {
                                    effectiveSigma = iefSig * (BL.TLT_MODIFIED_DURATION / BL.IEF_MODIFIED_DURATION);
                                    durationRegularized = true;
                                }
                            } else if ("HYG".equalsIgnoreCase(ticker)) {
                                double tltSig = discoveredSigmas.getOrDefault("TLT", Double.NaN);
                                double spySig = discoveredSigmas.getOrDefault("SPY", Double.NaN);
                                double boundedSigma = BL.applyCrossAssetSanityBounds("HYG", effectiveSigma, spySig, tltSig);
                                if (boundedSigma < effectiveSigma) {
                                    effectiveSigma = boundedSigma;
                                    durationRegularized = true;
                                }
                            }

                            sb.append(String.format("  Spot S0: $%.2f | Expiries: T1=%s, T2=%s, T3=%s%n",
                                    optRes.t2Result().S0(), manifest.t1Expiry(), manifest.t2Expiry(), manifest.t3Expiry()));
                            sb.append(String.format("  Annualized Vols: T1=%.2f%% | T2=%.2f%% | T3=%.2f%%%n",
                                    t1AnnVol * 100.0, t2AnnVol * 100.0, t3AnnVol * 100.0));
                            sb.append(String.format("  Noise V1: %.8f/day | Cleaned Pure Event Move (sigma): %.4f (%.2f%%)%s%n",
                                    optRes.v1Day(), effectiveSigma, effectiveSigma * 100.0,
                                    durationRegularized ? ("HYG".equalsIgnoreCase(ticker)
                                            ? " [Term-Structure / Beta-Regularized from SPY/TLT]"
                                            : String.format(" [Synthetic from %s]", "TLT".equalsIgnoreCase(ticker) ? "IEF" : "TLT")) : ""));
                            sb.append(String.format("  Breeden-Litzenberger Risk-Neutral Skewness: %+.4f (%s)%n",
                                    skewness, getSkewInterpretation(ticker, skewness)));

                            discoveredSigmas.put(ticker.toUpperCase(), effectiveSigma);
                            discoveredV1.put(ticker.toUpperCase(), optRes.v1Day());
                            discoveredVT2.put(ticker.toUpperCase(), optRes.vT2());
                            discoveredSkews.put(ticker.toUpperCase(), skewness);
                            appendJumpCalculations(sb, ticker, activeP, effectiveSigma, skewness, isBullish, durationRegularized);
                            final double discoveredSigma = effectiveSigma;
                            SwingUtilities.invokeLater(() -> sigmaField.setText(String.format("%.4f", discoveredSigma)));
                        } catch (Exception ex) {
                            sb.append(String.format("  Could not fetch/process options for %s: %s%n", ticker, ex.getMessage()));
                            sb.append(String.format("  Zero-Drift Jump Solution for %s: N/A (Options SVI fit unavailable)%n", ticker));
                        }
                    }

                    if (lastEventSliceFile != null && lastEventSliceFile.exists()) {
                        final File sliceFile = lastEventSliceFile;
                        // Generate the plot file silently in background without popping open an external viewer window
                        SwingUtilities.invokeLater(() -> generatePlotTask(sliceFile, false));
                    }
                } else {
                    discoveredSigmas.put(chosenTicker != null ? chosenTicker.toUpperCase() : "SPY", sigmaManual);
                    appendJumpCalculations(sb, chosenTicker, activeP, sigmaManual, Double.NaN, isBullish, false);
                }

                // 5. Synthesize 3-Venue Triangulated Number & Mispricing Analysis
                double sigmaTlt = discoveredSigmas.getOrDefault("TLT", sigmaManual);
                double sigmaSpy = discoveredSigmas.getOrDefault("SPY", sigmaManual);
                double sigmaIef = discoveredSigmas.getOrDefault("IEF", Double.NaN);
                boolean isIefSynthetic = false;

                // Cross-regularize TLT from IEF if TLT options had zero/illiquid move:
                if (sigmaTlt <= 1e-4 && !Double.isNaN(sigmaIef) && sigmaIef > 1e-4) {
                    sigmaTlt = sigmaIef * (BL.TLT_MODIFIED_DURATION / BL.IEF_MODIFIED_DURATION);
                    discoveredSigmas.put("TLT", sigmaTlt);
                } else if ((Double.isNaN(sigmaIef) || sigmaIef <= 1e-4) && sigmaTlt > 1e-4) {
                    sigmaIef = sigmaTlt * (BL.IEF_MODIFIED_DURATION / BL.TLT_MODIFIED_DURATION);
                    discoveredSigmas.put("IEF", sigmaIef);
                    isIefSynthetic = true;
                }

                double v1Tlt = discoveredV1.getOrDefault("TLT", Double.NaN);
                double v1Spy = discoveredV1.getOrDefault("SPY", Double.NaN);
                double skewTlt = discoveredSkews.getOrDefault("TLT", Double.NaN);
                double skewSpy = discoveredSkews.getOrDefault("SPY", Double.NaN);

                double ratesPForSynthesis = effectiveRatesP;
                BL.CrossAssetTriangulation triangulation = BL.triangulateFomcVenues(
                        activeP, pPolymarket, ratesPForSynthesis, sigmaTlt, sigmaSpy, v1Tlt, v1Spy, skewTlt, skewSpy, isBullish);

                sb.append("\n========================================================================================\n");
                sb.append("=== 3-VENUE FOMC EVENT SYNTHESIS & TRIANGULATION (PREDICTION + RATES + B-L SURFACES) ===\n");
                sb.append("========================================================================================\n");
                sb.append(String.format("Venue 1 — Retail Prediction Market (Polymarket)      : %s%n",
                        Double.isNaN(pPolymarket) ? "N/A" : String.format("%.2f%% (Any-Hike)", pPolymarket * 100.0)));
                sb.append(String.format("Venue 2 — Institutional Rates Market (Fed Funds ZQ) : %s%n",
                        Double.isNaN(effectiveRatesP) ? "N/A" : String.format("%.2f%% [Turn+Tail Any-Hike] (Unadjusted: %.2f%%, Delta: %+.2f bps)",
                                effectiveRatesP * 100.0,
                                pBonds * 100.0,
                                (fedFundsRes != null ? fedFundsRes.deltaRatePct() * 100.0 : 0.0))));
                sb.append(String.format("Venue 3 — B-L Implied Volatility Surfaces (TLT & SPY) :%n"));
                if (!Double.isNaN(triangulation.pOptions())) {
                    sb.append(String.format("   * Option Skew Inversion Diagnostic (p_Options) : %.2f%% [NOT COMPARABLE to Any-Hike Probabilities; Raw Skew Diagnostic %+.4f]%n",
                            triangulation.pOptions() * 100.0, skewTlt));
                    sb.append(String.format("     [Caveat: Under-identified. Not comparable to rates or Polymarket any-hike probabilities. 2-week TLT skew reflects structural put demand and background diffusion, not a pure binary event probability. Many (p, share) pairs fit this skew; options do not vote in consensus.]%n"));
                } else {
                    sb.append(String.format("   * Option Skew Inversion Diagnostic (p_Options) : N/A (No TLT skewness)%n"));
                }
                if (!Double.isNaN(skewTlt) && sigmaTlt > 1e-4) {
                    BL.ThreeStateFomcJump tltTreeCheck = BL.solveThreeStateJump(sigmaTlt, activeP, skewTlt, isBullish);
                    boolean skewConsistent = Math.abs(tltTreeCheck.realizedSkewness() - skewTlt) < 0.05;
                    sb.append(String.format("   * Options Consistency Check at Active p=%.2f%% : %s (Tree realized skew %+.3f vs market %+.3f, dot-plot var share %.0f%%)%n",
                            activeP * 100.0,
                            skewConsistent ? "DOES NOT CONTRADICT" : "CONTRADICTS / DIVERGENT",
                            tltTreeCheck.realizedSkewness(), skewTlt, tltTreeCheck.dotPlotVarianceShare() * 100.0));
                    sb.append(String.format("     [Caveat: Under-identified. TLT option surface accommodates active p=%.2f%% under %.0f%% dot-plot variance share; options do not contradict active p, but do not independently confirm or support it (weak evidence; non-voting).]%n",
                            activeP * 100.0, tltTreeCheck.dotPlotVarianceShare() * 100.0));
                }
                sb.append(String.format("   * TLT Pure Event Move: %.2f%% -> Implied 20Y Yield Shock: +/-%.2f bps (D_mod ~16.5y)%n",
                        sigmaTlt * 100.0, triangulation.tltImpliedYieldShockBps()));
                if (triangulation.tltImpliedYieldShockBps() < 5.0) {
                    sb.append(String.format("     [Note: +/-%.2f bps yield shock is light for an FOMC decision; background diffusion subtraction across wide weekly expiry gap absorbs event variance into noise]%n",
                            triangulation.tltImpliedYieldShockBps()));
                }
                sb.append(String.format("     Move if Hike/Event (at p=%.2f%%): %+.2f bps yield | Move if Pause/Surprise: %+.2f bps yield%n",
                        triangulation.pTriangulated() * 100.0, triangulation.tltYieldMoveIfEventBps(), triangulation.tltYieldMoveIfNotEventBps()));
                if (!Double.isNaN(sigmaIef) && sigmaIef > 1e-4) {
                    sb.append(String.format("   * IEF Pure Event Move: %.2f%%%s -> Implied 10Y Yield Shock: +/-%.2f bps (D_mod ~7.5y)%n",
                            sigmaIef * 100.0, isIefSynthetic ? " [Synthetic from TLT]" : "",
                            (sigmaIef / BL.IEF_MODIFIED_DURATION) * 10000.0));
                }
                sb.append(String.format("   * SPY Pure Event Move: %.2f%%%n", sigmaSpy * 100.0));
                double vT2Spy = discoveredVT2.getOrDefault("SPY", (!Double.isNaN(v1Spy) ? v1Spy * 26.0 : 0.0025));
                BL.ResidualSensitivity spySens = BL.calculateResidualSensitivity(
                        v1Spy, vT2Spy, sigmaSpy, 14.0 / 365.0, 0.14
                );
                if (spySens.isFragile()) {
                    sb.append(String.format("     [Note: Fragile residual (<3%% total variance); +/-0.1 vol pt quote error swings move by %+.2f%%]%n",
                            spySens.moveSensitivityToVolPoint() * 100.0));
                }
                sb.append(String.format("     Move if Hike/Event (at p=%.2f%%): %+.2f%% equity | Move if Pause/Surprise: %+.2f%% equity%n",
                        triangulation.pTriangulated() * 100.0, triangulation.spyMoveIfEventPct() * 100.0, triangulation.spyMoveIfNotEventPct() * 100.0));
                sb.append("----------------------------------------------------------------------------------------\n");
                sb.append(String.format("-> ACTIVE CONSENSUS PROBABILITY APPLIED       : %.2f%% (%s)%n", activeP * 100.0, activeSourceName));
                if (!Double.isNaN(effectiveRatesP) && !Double.isNaN(pPolymarket)) {
                    sb.append(String.format("   * Triangulated Blend (p_Tri)               : %.2f%% [65%% CME Fed Funds ZQ (%.2f%%) + 35%% Polymarket (%.2f%%)]%n",
                            triangulation.pTriangulated() * 100.0,
                            effectiveRatesP * 100.0,
                            pPolymarket * 100.0));
                    sb.append("   * Weight Rationale                         : 65% weight on institutional rates reflecting CME Fed Funds 30-day (ZQ) depth\n" +
                              "                                                and bank treasury / SOFR hedging liquidity; 35% weight on Polymarket retail\n" +
                              "                                                crowd positioning and tail discovery. (Judgmental weighting; options layer excluded\n" +
                              "                                                from probability vote due to under-identification from skew).\n");
                } else if (!Double.isNaN(effectiveRatesP)) {
                    sb.append(String.format("   * Triangulated Consensus (p_Tri)           : %.2f%% [100%% CME Fed Funds ZQ (%.2f%%)]%n",
                            triangulation.pTriangulated() * 100.0, effectiveRatesP * 100.0));
                } else if (!Double.isNaN(pPolymarket)) {
                    sb.append(String.format("   * Triangulated Consensus (p_Tri)           : %.2f%% [100%% Polymarket (%.2f%%)]%n",
                            triangulation.pTriangulated() * 100.0, pPolymarket * 100.0));
                } else {
                    sb.append(String.format("   * Triangulated Consensus (p_Tri)           : %.2f%%%n", triangulation.pTriangulated() * 100.0));
                }
                double dynamicBaseRatio = (!Double.isNaN(v1Tlt) && !Double.isNaN(v1Spy) && v1Tlt > 1e-9 && v1Spy > 1e-9)
                        ? Math.max(0.65, Math.min(2.00, Math.sqrt(v1Tlt / v1Spy)))
                        : BL.BASELINE_TLT_SPY_RATIO;
                sb.append(String.format("-> CROSS-ASSET EVENT VOL RATIO (TLT / SPY)    : %.2f  (Empirical Baseline: %.2fx)%n",
                        triangulation.tltToSpyVolRatio(), dynamicBaseRatio));
                if (Double.isNaN(triangulation.spyVolEdgePct())) {
                    sb.append(String.format("-> TRIANGULATED FAIR SPY EVENT MOVE           : %.2f%% (Current Surface: %.2f%% [High-Noise Residual] — Edge Suppressed)%n",
                            triangulation.triangulatedSpyFairSigma() * 100.0, sigmaSpy * 100.0));
                } else {
                    sb.append(String.format("-> TRIANGULATED FAIR SPY EVENT MOVE           : %.2f%% (Current Surface: %.2f%%, Edge: %+.2f%%)%n",
                            triangulation.triangulatedSpyFairSigma() * 100.0, sigmaSpy * 100.0, triangulation.spyVolEdgePct() * 100.0));
                }
                sb.append(String.format("-> REGIME VERDICT & MISPRICING DIAGNOSIS      : [%s]%n", triangulation.regimeVerdict()));
                sb.append(String.format("   %s%n", triangulation.detailedExplanation()));
                sb.append("========================================================================================\n\n");

                // Update UI Banner on Swing Thread
                final BL.CrossAssetTriangulation finalTri = triangulation;
                final double finalPPolymarket = pPolymarket;
                final double finalPBonds = effectiveRatesP;
                final double finalActiveP = activeP;
                final String finalActiveName = activeSourceName;
                SwingUtilities.invokeLater(() -> {
                    String optStr = Double.isNaN(finalTri.pOptions()) ? "N/A" : String.format("%.1f%% (diag)", finalTri.pOptions() * 100.0);
                    summaryLine1.setText(String.format(
                            "ACTIVE PROB: %.1f%% (%s) | CME: %s | PM: %s | TLT Skew Diag: %s (non-voting) | 20Y Yield Shock: +/-%.1f bps | SPY Move: +/-%.2f%%",
                            finalActiveP * 100.0,
                            finalActiveName,
                            Double.isNaN(finalPBonds) ? "N/A" : String.format("%.1f%%", finalPBonds * 100.0),
                            Double.isNaN(finalPPolymarket) ? "N/A" : String.format("%.1f%%", finalPPolymarket * 100.0),
                            optStr,
                            finalTri.tltImpliedYieldShockBps(),
                            finalTri.sigmaSpy() * 100.0
                    ));
                    if (Double.isNaN(finalTri.spyVolEdgePct())) {
                        summaryLine2.setText(String.format(
                                "VERDICT: %s | Fair SPY Move: %.2f%% (Edge Suppressed: High-Noise Residual) — %s",
                                finalTri.regimeVerdict(),
                                finalTri.triangulatedSpyFairSigma() * 100.0,
                                finalTri.detailedExplanation()
                        ));
                    } else {
                        summaryLine2.setText(String.format(
                                "VERDICT: %s | Fair SPY Move: %.2f%% (Edge: %+.2f%%) — %s",
                                finalTri.regimeVerdict(),
                                finalTri.triangulatedSpyFairSigma() * 100.0,
                                finalTri.spyVolEdgePct() * 100.0,
                                finalTri.detailedExplanation()
                        ));
                    }
                });

                return null;
            }

            @Override
            protected void done() {
                try {
                    outputArea.setText(sb.toString());
                } finally {
                    fetchBtn.setEnabled(true);
                }
            }
        };

        mainWorker.execute();
    }

    private static String getSkewInterpretation(String ticker, double skew) {
        if (Double.isNaN(skew)) return "N/A";
        if ("TLT".equalsIgnoreCase(ticker)) {
            return skew >= -0.1 ? "Symmetric / Call-Demand Smile (Flight to Safety)" : "Mild Put Tilt";
        } else if ("HYG".equalsIgnoreCase(ticker)) {
            if (skew < -1.8) return "Severe Left-Tail Credit Spread / Default Risk Blowout";
            if (skew < -0.3) return "Standard Credit Put Skew (Downside Asymmetry)";
            return "Normal Credit Skew";
        } else if ("IEF".equalsIgnoreCase(ticker)) {
            return "Benchmark 10Y Duration Smile";
        } else {
            if (skew < -1.5) return "Elevated Downside Crash Protection (Structural Put Skew)";
            if (skew < -0.3) return "Typical Equity Smirk (Downside Crash Protection)";
            return "Equity Vol Smile";
        }
    }

    private void appendJumpCalculations(StringBuilder sb, String ticker, double p, double sigmaEvent, double blSkewness, boolean isBullish) {
        appendJumpCalculations(sb, ticker, p, sigmaEvent, blSkewness, isBullish, false);
    }

    private void appendJumpCalculations(StringBuilder sb, String ticker, double p, double sigmaEvent, double blSkewness, boolean isBullish, boolean isSynthetic) {
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(sigmaEvent, p, blSkewness, isBullish);
        BL.ThreeStateFomcJump threeState = BL.solveThreeStateJump(sigmaEvent, p, blSkewness, isBullish);

        sb.append(String.format("  Skew-Coupled Jump Solution for %s (Option Horizon Skew=%+.3f | Tree Realized Skew=%+.3f, Dot-Plot Var Share=%.0f%%):%n",
                ticker, Double.isNaN(blSkewness) ? 0.0 : blSkewness, threeState.realizedSkewness(), threeState.dotPlotVarianceShare() * 100.0));
        sb.append(String.format("    2-State Zero-Drift    -> Move if Fed HIKES: %+.4f%% | Move if SURPRISE: %+.4f%%%n",
                jump.moveIfEvent() * 100.0, jump.moveIfNotEvent() * 100.0));
        sb.append(String.format("    3-State FOMC Tree (Rate Decision + SEP Dot-Plot / Powell Press Conference):%n"));
        sb.append(String.format("      [1] Hike + Hawkish Dots/Powell (%5.2f%% prob) : %+.4f%%%n",
                threeState.pHawkish() * 100.0, threeState.moveHawkish() * 100.0));
        sb.append(String.format("      [2] Hike + Dovish Dots/Powell  (%5.2f%% prob) : %+.4f%%%n",
                threeState.pDovish() * 100.0, threeState.moveDovish() * 100.0));
        sb.append(String.format("      [3] Surprise Pause / Pivot     (%5.2f%% prob) : %+.4f%%%n",
                threeState.pSurprise() * 100.0, threeState.moveSurprise() * 100.0));

        // Treasury Duration conversion to basis point yield shock
        if ("TLT".equalsIgnoreCase(ticker)) {
            double yieldShockHikeBps = -(jump.moveIfEvent() / BL.TLT_MODIFIED_DURATION) * 10000.0;
            double yieldShockSurpriseBps = -(jump.moveIfNotEvent() / BL.TLT_MODIFIED_DURATION) * 10000.0;
            double unsignedYieldShockBps = (sigmaEvent / BL.TLT_MODIFIED_DURATION) * 10000.0;
            sb.append(String.format("    [TLT D_mod ~16.5y Yield Shock%s]  : +/-%.2f bps unsigned 20Y Yield Move%n",
                    isSynthetic ? " (Synthetic from IEF)" : "", unsignedYieldShockBps));
            sb.append(String.format("      -> Implied 20Y Yield Move if Hike     : %+.2f bps%n", yieldShockHikeBps));
            sb.append(String.format("      -> Implied 20Y Yield Move if Surprise : %+.2f bps%n", yieldShockSurpriseBps));
        } else if ("IEF".equalsIgnoreCase(ticker)) {
            double yieldShockHikeBps = -(jump.moveIfEvent() / BL.IEF_MODIFIED_DURATION) * 10000.0;
            double yieldShockSurpriseBps = -(jump.moveIfNotEvent() / BL.IEF_MODIFIED_DURATION) * 10000.0;
            double unsignedYieldShockBps = (sigmaEvent / BL.IEF_MODIFIED_DURATION) * 10000.0;
            sb.append(String.format("    [IEF D_mod ~7.5y Yield Shock%s]   : +/-%.2f bps unsigned 10Y Yield Move%n",
                    isSynthetic ? " (Synthetic from TLT)" : "", unsignedYieldShockBps));
            sb.append(String.format("      -> Implied 10Y Yield Move if Hike     : %+.2f bps%n", yieldShockHikeBps));
            sb.append(String.format("      -> Implied 10Y Yield Move if Surprise : %+.2f bps%n", yieldShockSurpriseBps));
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FomcSelectorFrame().setVisible(true));
    }
}

