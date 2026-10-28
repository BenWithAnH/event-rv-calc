package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.BL;

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
    private final JTextField sigmaField = new JTextField("0.02", 6);
    private final JTextArea outputArea = new JTextArea(20, 65);
    private List<Polymarket.Event> cachedEvents = List.of();

    public FomcSelectorFrame() {
        super("Event RV Calc - FOMC & Real $SPY Options");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("FOMC Date:"));
        topPanel.add(dateCombo);
        topPanel.add(fetchYfCheckbox);
        topPanel.add(new JLabel("Manual sigma_event:"));
        topPanel.add(sigmaField);

        JButton fetchBtn = new JButton("Fetch Market & Run Math Engine");
        topPanel.add(fetchBtn);
        add(topPanel, BorderLayout.NORTH);

        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(new JScrollPane(outputArea), BorderLayout.CENTER);

        fetchBtn.addActionListener(e -> evaluateSelectedDate());

        pack();
        setLocationRelativeTo(null);
        loadInitialData();
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

                    @Override
                    protected SurfaceRunner.EventPureMoveResult doInBackground() {
                        try {
                            var manifest = YFinanceOptionsIngestion.fetchEventOptions("SPY", selectedDate, null);
                            return YFinanceOptionsIngestion.computePureEventMove(manifest, 5.0);
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
                                currentSb.append(String.format("  V_T1 (1d before)   : %.6f (vol: %.2f%%)%n", optRes.vT1(), Math.sqrt(optRes.vT1() * 365.0) * 100.0));
                                currentSb.append(String.format("  V_T2 (1d after)    : %.6f (vol: %.2f%%)%n", optRes.vT2(), Math.sqrt(optRes.vT2() * 365.0) * 100.0));
                                currentSb.append(String.format("  V_T3 (1-wk after)  : %.6f (vol: %.2f%%)%n", optRes.vT3(), Math.sqrt(optRes.vT3() * 365.0) * 100.0));
                                currentSb.append(String.format("  V_1 (daily noise)  : %.6f%n", optRes.v1Day()));
                                currentSb.append(String.format("  Cleaned Pure sigma : %.6f (%.2f%% pure move)%n", optRes.sigmaEvent(), optRes.sigmaEvent() * 100.0));
                                sigmaField.setText(String.format("%.4f", optRes.sigmaEvent()));
                                appendJumpCalculations(currentSb, finalPHike, optRes.sigmaEvent());
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
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(sigmaEvent, pHike);
        sb.append("\n--- BL.java Zero-Drift Jump Solution ---\n");
        sb.append(String.format("  sigma_event : %.4f%n", sigmaEvent));
        sb.append(String.format("  Jump if Hike (u) : %+.6f (%+.2f%%)%n", jump.u(), jump.u() * 100.0));
        sb.append(String.format("  Drop if Not  (d) : %+.6f (%+.2f%%)%n", jump.d(), jump.d() * 100.0));
        sb.append(String.format("  Expected Drift   : %.2e%n", pHike * jump.u() + (1.0 - pHike) * jump.d()));
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FomcSelectorFrame().setVisible(true));
    }
}

