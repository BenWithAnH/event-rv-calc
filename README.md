# Cross-Asset Implied Probability Surface (`event-rv-calc`)

Cross-asset quantitative engine in Java 21 and Python for extracting, calibrating, and decomposing FOMC event jump distributions across prediction markets, equity/index options, and Fed Funds futures.

---

## Architecture Overview

```
src/main/java/com/eventrvcalc/
├── core/
│   └── surface/
│       ├── BL.java                  # Breeden-Litzenberger risk-neutral density extractor & event jump models
│       ├── BS.java                  # Black-Scholes European pricing & SVI volatility smile parametrization
│       ├── CRR.java                 # Cox-Ross-Rubinstein binomial tree & de-Americanization IV solver
│       └── SurfaceRunner.java       # Surface calibration pipeline & end-to-end event variance orchestrator
└── ingestion/
    └── api/
        ├── Polymarket.java          # Polymarket Gamma API client & rate-cut outcome bracket extractor
        ├── YFinanceOptionsIngestion.java # Options chain & CME 30-Day Fed Funds futures (ZQ) ingestion
        └── FomcSelectorFrame.java   # Interactive Swing analysis dashboard & visualization trigger

scripts/
├── fetch_options_yfinance.py        # Python helper for yfinance market data extraction
└── visualize_surface.py             # 3D implied probability surface & cross-asset density plotter
```

---

## Key Quantitative Modules

1. **De-Americanization (`CRR.java`)**:
   - Inverts American option quotes into pure European implied volatilities $\hat{\sigma}_K$ using an optimized 1D rolling backward induction CRR binomial tree.
   - Eliminates early-exercise premium prior to smile interpolation.

2. **Smooth Volatility Smile Parameterization (`BS.java`)**:
   - Calibrates 5-parameter raw SVI (Stochastic Volatility Inspired) total variance curves $w(k)$ to eliminate butterfly arbitrage and enforce smooth wings.

3. **Risk-Neutral Density Extraction & Event Decomposition (`BL.java`)**:
   - Extracts continuous risk-neutral probability density $f_Q(K) = e^{rT} \frac{\partial^2 C}{\partial K^2}$ via Breeden-Litzenberger (1978).
   - Decomposes pre-event ($T_1$), event ($T_2$), and post-event ($T_3$) variances to isolate pure event move variance $\sigma_{\text{event}}^2$.
   - Integrates prediction market probabilities (Polymarket) and institutional Fed Funds futures (CME ZQ) with year-end turn premium adjustments.
   - Deconvolves pure jump densities via radix-2 Cooley-Tukey in-place FFT.

---

## Getting Started

### Prerequisites
- **Java 21** or later
- **Python 3.13+** (with `yfinance`, `pandas`, `scipy`, `matplotlib`)
- **Maven** (or bundled `mvnw.cmd` / `./mvnw`)

### Build & Run Tests
```bash
./mvnw test
```

### Launch Interactive Desktop UI
```bash
run_ui.bat
# or
./mvnw exec:java
```

### Run Batch Surface Calibration CLI
```bash
./mvnw compile exec:java -Dexec.mainClass="com.eventrvcalc.core.surface.SurfaceRunner" -Dexec.args="spy_input.json surface_data.json"
```

