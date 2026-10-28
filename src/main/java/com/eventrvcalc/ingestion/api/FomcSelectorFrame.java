package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.BL;
import com.eventrvcalc.core.surface.SurfaceRunner;

import javax.swing.*;
import java.awt.*;
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
    private final JCheckBox fetchYfCheckbox = new JCheckBox("Live yfinance ($SPY)", true);
    private final JCheckBox eventBullishCheckbox = new JCheckBox("Hike is Bullish", false);
    private final JTextField sigmaField = new JTextField("0.02", 6);
    private final JTextArea outputArea = new JTextArea(22, 75);
    private final JButton openPlotBtn = new JButton("Open Surface Plot (.png)");
    private List<Polymarket.Event> cachedEvents = List.of();
    private File lastGeneratedPlot = new File("surface_exploration.png");
    private File lastEventSliceFile = null;

    public FomcSelectorFrame() {
        super("Event RV Calc - FOMC & Real $SPY Options");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("FOMC Date:"));
        topPanel.add(dateCombo);
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
        SwingWorker<Void, Void> plotWorker = new SwingWorker<>() {
            private Exception error;

            @Override
            protected Void doInBackground() {
                try {
                    File outPng = new File("event_surface.png");
                    ProcessBuilder pb = new ProcessBuilder(
                            "py", "-3.13", "scripts/visualize_surface.py",
                            "--input-file", sliceJson.getAbsolutePath(),
                            "--output-png", outPng.getAbsolutePath()
                    );
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    int code = p.waitFor();
                    if (code != 0) {
                        String err = new String(p.getInputStream().readAllBytes());
                        throw new IOException("Plot generation failed: " + err);
                    }
                    lastGeneratedPlot = outPng;
                } catch (Exception ex) {
                    error = ex;
                }
                return null;
            }

            @Override
            protected void done() {
                if (error != null) {
                    outputArea.append("Plot generation error: " + error.getMessage() + "\n");
                } else {
                    outputArea.append("Generated fresh event surface plot: " + lastGeneratedPlot.getAbsolutePath() + "\n");
                    if (openWhenReady) {
                        openImageExternally();
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
                if (dateCombo.getItemCount() > 0) {
                    dateCombo.setSelectedIndex(0);
                    evaluateSelectedDate();
                }
            }
        };
        worker.execute();
    }

    private void evaluateSelectedDate() {
        LocalDate selectedDate = (LocalDate) dateCombo.getSelectedItem();
        if (selectedDate == null) return;

        double sigmaEvent;
        try {
            sigmaEvent = Double.parseDouble(sigmaField.getText().trim());
        } catch (NumberFormatException ex) {
            outputArea.setText("Invalid sigma_event number: " + sigmaField.getText());
            return;
        }

        Optional<Polymarket.Event> matchOpt = Polymarket.findFomcEventForDate(selectedDate, cachedEvents);
        StringBuilder sb = new StringBuilder();
        sb.append("Selected FOMC Decision Date: ").append(selectedDate).append("\n");

        if (matchOpt.isEmpty()) {
            sb.append("No active Polymarket FOMC market found matching ").append(selectedDate).append("\n");
            outputArea.setText(sb.toString());
            return;
        }

        Polymarket.Event ev = matchOpt.get();
        sb.append("Matched Polymarket Event:    ").append(ev.title()).append("\n");
        sb.append("Slug:                        ").append(ev.slug()).append("\n");
        sb.append("Resolution End Date:         ").append(ev.endDate()).append("\n");
        sb.append(String.format("Total Volume:                $%,.2f%n%n", ev.volume()));

        sb.append("--- Sub-Market Outcome Brackets ---\n");
        for (Map.Entry<String, Double> entry : ev.outcomeProbabilities().entrySet()) {
            sb.append(String.format("  %-25s : %.4f (%.2f%%)%n", entry.getKey(), entry.getValue(), entry.getValue() * 100.0));
        }

        double pHike = ev.yesProbability();
        sb.append(String.format("%nAggregated Hike Probability (p): %.4f (%.2f%%)%n", pHike, pHike * 100.0));

        if (pHike > 0.0 && pHike < 1.0) {
            if (fetchYfCheckbox.isSelected()) {
                sb.append("\n--- Fetching Real $SPY Options via yfinance (T1, T2, T3) ---\n");
                outputArea.setText(sb.toString());
                final double finalPHike = pHike;
                final StringBuilder currentSb = new StringBuilder(sb);

                SwingWorker<SurfaceRunner.EventPureMoveResult, Void> optWorker = new SwingWorker<>() {
                    private Exception optError;
                    private YFinanceOptionsIngestion.OptionsManifest manifest;

                    @Override
                    protected SurfaceRunner.EventPureMoveResult doInBackground() {
                        try {
                            manifest = YFinanceOptionsIngestion.fetchEventOptions("SPY", selectedDate, null);
                            lastEventSliceFile = manifest.t2Path().toFile();
                            return YFinanceOptionsIngestion.computePureEventMove(manifest);
                        } catch (Exception ex) {
                            optError = ex;
                            return null;
                        }
                    }

                    @Override
                    protected void done() {
                        try {
                            var optRes = get();
                            if (optError != null || optRes == null) {
                                currentSb.append("Warning: Could not fetch yfinance options (").append(optError != null ? optError.getMessage() : "unknown").append(")\n");
                                currentSb.append("Falling back to manual sigma_event: ").append(sigmaEvent).append("\n");
                                appendJumpCalculations(currentSb, finalPHike, sigmaEvent);
                            } else {
                                double t1Years = optRes.t1Result().T();
                                double t2Years = optRes.t2Result().T();
                                double t3Years = optRes.t3Result().T();

                                double t1AnnVol = Math.sqrt(optRes.vT1() / Math.max(1e-6, t1Years));
                                double t2AnnVol = Math.sqrt(optRes.vT2() / Math.max(1e-6, t2Years));
                                double t3AnnVol = Math.sqrt(optRes.vT3() / Math.max(1e-6, t3Years));

                                currentSb.append(String.format("  T1 (%s, DTE=%.0fd) : V_T1=%.6f (ann vol: %.2f%%)%n",
                                        manifest.t1Expiry(), t1Years * 365.0, optRes.vT1(), t1AnnVol * 100.0));
                                currentSb.append(String.format("  T2 (%s, DTE=%.0fd) : V_T2=%.6f (ann vol: %.2f%%)%n",
                                        manifest.t2Expiry(), t2Years * 365.0, optRes.vT2(), t2AnnVol * 100.0));
                                currentSb.append(String.format("  T3 (%s, DTE=%.0fd) : V_T3=%.6f (ann vol: %.2f%%)%n",
                                        manifest.t3Expiry(), t3Years * 365.0, optRes.vT3(), t3AnnVol * 100.0));

                                currentSb.append(String.format("  Daily Noise (V_1)     : %.8f (from %s to %s, %.0f trading days)%n",
                                        optRes.v1Day(), manifest.t2Expiry(), manifest.t3Expiry(), manifest.tradingDaysT2ToT3()));
                                currentSb.append(String.format("  Window Gap (T1 -> T2) : %.0f trading days (subtracting %.0f * V_1 noise)%n",
                                        manifest.tradingDaysT1ToT2(), manifest.tradingDaysT1ToT2()));
                                currentSb.append(String.format("  Cleaned Pure sigma    : %.6f (%.2f%% pure event move)%n",
                                        optRes.sigmaEvent(), optRes.sigmaEvent() * 100.0));
                                sigmaField.setText(String.format("%.4f", optRes.sigmaEvent()));
                                appendJumpCalculations(currentSb, finalPHike, optRes.sigmaEvent());

                                // Automatically re-generate surface plot with the event's actual post-event slice (T2)
                                if (lastEventSliceFile != null && lastEventSliceFile.exists()) {
                                    currentSb.append("\nRegenerating surface plot for this event...\n");
                                    generateAndOpenPlotInBackground(lastEventSliceFile);
                                }
                            }
                        } catch (Exception ex) {
                            currentSb.append("Options error: ").append(ex.getMessage()).append("\n");
                            appendJumpCalculations(currentSb, finalPHike, sigmaEvent);
                        }
                        outputArea.setText(currentSb.toString());
                    }
                };
                optWorker.execute();
                return;
            } else {
                appendJumpCalculations(sb, pHike, sigmaEvent);
            }
        } else {
            sb.append("\nProbability p is at boundary (0 or 1); cannot compute interior zero-drift jump.\n");
        }

        outputArea.setText(sb.toString());
    }

    private void appendJumpCalculations(StringBuilder sb, double pHike, double sigmaEvent) {
        boolean isBullish = eventBullishCheckbox.isSelected();
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(sigmaEvent, pHike, isBullish);

        sb.append("\n--- BL.java Zero-Drift Jump Solution (Forward Martingale) ---\n");
        sb.append(String.format("  sigma_event : %.4f (%.2f%%)%n", sigmaEvent, sigmaEvent * 100.0));
        sb.append(String.format("  Event (Rate Hike) Probability: %.2f%% [Direction: %s]%n",
                pHike * 100.0, isBullish ? "BULLISH (+)" : "BEARISH (-)"));
        sb.append(String.format("  Surprise (Pause/Cut) Prob   : %.2f%%%n", (1.0 - pHike) * 100.0));

        sb.append(String.format("  Move if Fed HIKES    : %+.4f%% (drop/small expected branch)%n", jump.moveIfEvent() * 100.0));
        sb.append(String.format("  Move if PAUSE/CUT    : %+.4f%% (rally/surprise relief branch)%n", jump.moveIfNotEvent() * 100.0));

        double drift = pHike * jump.moveIfEvent() + (1.0 - pHike) * jump.moveIfNotEvent();
        double varianceCheck = pHike * jump.moveIfEvent() * jump.moveIfEvent()
                + (1.0 - pHike) * jump.moveIfNotEvent() * jump.moveIfNotEvent();

        sb.append(String.format("  Forward Martingale Drift    : %.2e (p*move_event + (1-p)*move_no_event = 0)%n", drift));
        sb.append(String.format("  Implied Variance Match      : %.6f == %.6f (sigma^2)%n", varianceCheck, sigmaEvent * sigmaEvent));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FomcSelectorFrame().setVisible(true));
    }
}

