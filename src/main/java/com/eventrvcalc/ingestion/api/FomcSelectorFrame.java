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
    private final JTextField sigmaField = new JTextField("0.02", 6);
    private final JTextArea outputArea = new JTextArea(18, 60);
    private List<Polymarket.Event> cachedEvents = List.of();

    public FomcSelectorFrame() {
        super("Event RV Calc - FOMC Polymarket Selector");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.add(new JLabel("Event: FOMC Decision Date:"));
        topPanel.add(dateCombo);
        topPanel.add(new JLabel("Implied Event Vol (sigma_event):"));
        topPanel.add(sigmaField);

        JButton fetchBtn = new JButton("Find Market & Run BL");
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
            BL.ZeroDriftJump jump = Polymarket.solveZeroDriftJump(ev, sigmaEvent);
            sb.append("\n--- BL.java Zero-Drift Jump Solution ---\n");
            sb.append(String.format("  sigma_event : %.4f%n", sigmaEvent));
            sb.append(String.format("  Jump if Hike (u) : %+.6f (%+.2f%%)%n", jump.u(), jump.u() * 100.0));
            sb.append(String.format("  Drop if Not  (d) : %+.6f (%+.2f%%)%n", jump.d(), jump.d() * 100.0));
            sb.append(String.format("  Expected Drift   : %.2e%n", pHike * jump.u() + (1.0 - pHike) * jump.d()));
        } else {
            sb.append("\nProbability p is at boundary (0 or 1); cannot compute interior zero-drift jump.\n");
        }

        outputArea.setText(sb.toString());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new FomcSelectorFrame().setVisible(true));
    }
}

