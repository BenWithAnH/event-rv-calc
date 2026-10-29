package com.eventrvcalc.core.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BLTest {

    @Test
    @DisplayName("Breeden-Litzenberger matches analytical Black-Scholes log-normal density for constant vol")
    void testConstantVolDensityBenchmark() {
        double S0 = 100.0;
        double K = 100.0;
        double T = 1.0;
        double r = 0.05;
        double q = 0.0;
        double sigma = 0.20;

        BS.VolCurve flatVol = strike -> sigma;

        // Analytical Black-Scholes risk-neutral density:
        // f_Q(K) = (1 / (K * sigma * sqrt(T))) * phi(d2)
        double sqrtT = Math.sqrt(T);
        double d1 = (Math.log(S0 / K) + (r - q + 0.5 * sigma * sigma) * T) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;
        double phiD2 = (1.0 / Math.sqrt(2.0 * Math.PI)) * Math.exp(-0.5 * d2 * d2);
        double analyticalDensity = phiD2 / (K * sigma * sqrtT);

        double blDensity = BL.density(S0, K, T, r, q, flatVol, 0.01);

        assertThat(blDensity).isCloseTo(analyticalDensity, within(1e-4));
    }

    @Test
    @DisplayName("BL density on SVI curve satisfies total probability = 1 and martingale E[S_T] = F")
    void testSviRiskNeutralMartingale() {
        double S0 = 381.10;
        double T = 0.1233;
        double r = 0.043;
        double q = 0.0;

        // Calibrated SVI parameters for SPY
        BS.SviVolCurve svi = new BS.SviVolCurve(0.003922, 0.014614, -0.543830, 0.089377, 0.020981, S0, T);

        // Dense strike grid spanning 5 standard deviations
        int nStrikes = 400;
        double minK = 260.0;
        double maxK = 520.0;
        double[] strikes = new double[nStrikes];
        double step = (maxK - minK) / (nStrikes - 1);
        for (int i = 0; i < nStrikes; i++) {
            strikes[i] = minK + i * step;
        }

        List<BL.DensityPoint> slice = BL.generateDensitySlice(S0, strikes, T, r, q, svi, 0.05);

        // 1. Density must be non-negative everywhere (absence of butterfly arbitrage)
        for (BL.DensityPoint dp : slice) {
            assertThat(dp.density()).isGreaterThanOrEqualTo(0.0);
            assertThat(dp.cdf()).isBetween(0.0, 1.0);
        }

        // 2. Total probability integral \int f_Q(K) dK must be ~1.0
        double totalProb = BL.calculateTotalProbability(slice);
        assertThat(totalProb).isCloseTo(1.0, within(0.01));

        // 3. Martingale condition: E^Q[S_T] = F = S0 * e^((r - q) * T)
        double expectedST = BL.calculateExpectedST(slice);
        double forward = S0 * Math.exp((r - q) * T);
        assertThat(expectedST).isCloseTo(forward, within(0.50)); // within $0.50 on $383 forward
    }

    @Test
    @DisplayName("CDF is monotonically increasing across strikes")
    void testCdfMonotonicity() {
        double S0 = 100.0;
        double T = 0.5;
        double r = 0.03;
        double q = 0.0;
        BS.VolCurve flatVol = strike -> 0.25;

        double[] strikes = {80.0, 90.0, 100.0, 110.0, 120.0};
        List<BL.DensityPoint> slice = BL.generateDensitySlice(S0, strikes, T, r, q, flatVol, 0.05);

        for (int i = 0; i < slice.size() - 1; i++) {
            assertThat(slice.get(i).cdf()).isLessThan(slice.get(i + 1).cdf());
        }
    }

    @Test
    @DisplayName("Delta CDF captures probability spread to wings across event expiry")
    void testDeltaCdfEventSpread() {
        double S0 = 100.0;
        double r = 0.0;
        double q = 0.0;
        double T1 = 5.0 / 252.0;
        double T2 = 6.0 / 252.0;
        BS.VolCurve volBefore = strike -> 0.15;
        BS.VolCurve volAfter = strike -> 0.40;

        // OTM downside strike (K = 95 < F = 100) has higher cumulative left-tail probability after event
        double dCdfDownside = BL.deltaCdf(S0, 95.0, T1, volBefore, T2, volAfter, r, q);
        assertThat(dCdfDownside).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("Log variance, 3-expiry noise variance V1, and implied pure event move")
    void testVarianceDecompositionAndPureEventMove() {
        double S0 = 100.0;
        double r = 0.0;
        double q = 0.0;
        double T = 10.0 / 252.0;
        double sigma = 0.25;
        BS.VolCurve flatVol = strike -> sigma;

        int nStrikes = 500;
        double[] strikes = new double[nStrikes];
        for (int i = 0; i < nStrikes; i++) {
            strikes[i] = 60.0 + i * (80.0 / (nStrikes - 1));
        }

        List<BL.DensityPoint> slice = BL.generateDensitySlice(S0, strikes, T, r, q, flatVol, 0.02);
        double vT = BL.calculateLogVariance(slice, S0);
        // For log-normal S_T with log-return X ~ N(-0.5*sigma^2*T, sigma^2*T), E[X^2] = sigma^2*T + 0.25*sigma^4*T^2
        double expectedSecondMoment = sigma * sigma * T + 0.25 * Math.pow(sigma, 4) * T * T;
        assertThat(vT).isCloseTo(expectedSecondMoment, within(1e-4));

        // 3-expiry setup: T1 = 10/27 (1d), T2 = 10/29 (2d + event), T3 = 11/05 (7d + event, 5 trading days after T2)
        double v1DayTrue = 0.0001; // 1% daily vol -> 0.0001 daily variance
        double sigmaEventTrue = 0.04; // 4% pure event move -> 0.0016 event variance
        double v1027 = 1.0 * v1DayTrue;
        double v1029 = 2.0 * v1DayTrue + sigmaEventTrue * sigmaEventTrue;
        double v1105 = 7.0 * v1DayTrue + sigmaEventTrue * sigmaEventTrue;

        double v1Extracted = BL.calculateDailyNoiseVariance(v1029, v1105, 5.0);
        assertThat(v1Extracted).isCloseTo(v1DayTrue, within(1e-12));

        double impliedMove = BL.impliedPureEventMove(v1027, v1029, v1Extracted, 1.0);
        assertThat(impliedMove).isCloseTo(sigmaEventTrue, within(1e-9));
    }

    @Test
    @DisplayName("Fourier deconvolution recovers pure jump distribution and bimodal Gaussian mixture")
    void testFourierDeconvolutionAndGaussianMixture() {
        int n = 512;
        double xMin = -0.30;
        double xMax = 0.30;
        double dx = (xMax - xMin) / (n - 1);

        double sigmaNoise = 0.02;
        double pTrue = 0.60;
        double muUpTrue = 0.05;
        double muDownTrue = -0.075;
        double sigmaJump = 0.015;

        // Pre-event log-return density: N(0, sigmaNoise^2)
        // Post-event log-return density: convolution of pre-event noise with bimodal jump mixture
        double sigmaPost = Math.sqrt(sigmaNoise * sigmaNoise + sigmaJump * sigmaJump);
        List<BL.LogReturnPoint> f1 = new java.util.ArrayList<>(n);
        List<BL.LogReturnPoint> f2 = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double x = xMin + i * dx;
            double d1 = gauss(x, 0.0, sigmaNoise);
            double d2 = pTrue * gauss(x, muUpTrue, sigmaPost) + (1.0 - pTrue) * gauss(x, muDownTrue, sigmaPost);
            f1.add(new BL.LogReturnPoint(x, d1));
            f2.add(new BL.LogReturnPoint(x, d2));
        }

        List<BL.LogReturnPoint> jumpDensity = BL.deconvolveJumpDensity(f1, f2, 1e-6);

        // Fit bimodal mixture with given prediction market probability p = 0.60
        BL.GaussianMixture gmFixed = BL.fitGaussianMixture(jumpDensity, pTrue);
        assertThat(gmFixed.p()).isCloseTo(pTrue, within(1e-6));
        assertThat(gmFixed.muUp()).isCloseTo(muUpTrue, within(0.005));
        assertThat(gmFixed.muDown()).isCloseTo(muDownTrue, within(0.005));

        // Zero-drift jump equations: u = +sigma_event * sqrt((1-p)/p), d = -sigma_event * sqrt(p/(1-p))
        double sigmaEvent = 0.06;
        BL.ZeroDriftJump jump = BL.solveZeroDriftJump(sigmaEvent, pTrue);
        // Zero-drift condition: p * u + (1 - p) * d == 0 (where d is signed negative drop)
        assertThat(pTrue * jump.u() + (1.0 - pTrue) * jump.d()).isCloseTo(0.0, within(1e-12));
        // Variance condition: p * u^2 + (1 - p) * d^2 == sigma_event^2
        assertThat(pTrue * jump.u() * jump.u() + (1.0 - pTrue) * jump.d() * jump.d())
                .isCloseTo(sigmaEvent * sigmaEvent, within(1e-12));

        // Bearish event test (e.g. rate hike on equities):
        BL.ZeroDriftJump bearishJump = BL.solveZeroDriftJump(sigmaEvent, pTrue, false);
        assertThat(bearishJump.moveIfEvent()).isLessThan(0.0); // drop if hike
        assertThat(bearishJump.moveIfNotEvent()).isGreaterThan(0.0); // rally if no hike
        assertThat(pTrue * bearishJump.moveIfEvent() + (1.0 - pTrue) * bearishJump.moveIfNotEvent())
                .isCloseTo(0.0, within(1e-12));
    }

    @Test
    void testCrossAssetTriangulationAndYieldShock() {
        // Prompt specific values: p = 81.95%, sigmaTLT = 0.03943 (or moves -1.85% vs +8.40%)
        // With p = 0.8195 and isEventBullish = false (hike drops bonds):
        // d = -sigma * sqrt(p / (1-p)) -> if sigma = 0.03943: moveIfEvent = -0.0185 (-1.85%), moveIfNotEvent = +0.0840 (+8.40%)
        double p = 0.8195;
        double sigmaTlt = 0.039435;
        BL.ZeroDriftJump tltJump = BL.solveZeroDriftJump(sigmaTlt, p, false);
        assertThat(tltJump.moveIfEvent()).isCloseTo(-0.0185, within(0.001));
        assertThat(tltJump.moveIfNotEvent()).isCloseTo(0.0840, within(0.001));

        // Yield shock in basis points via D_mod = 16.5:
        // Move if Hike: -1.85% bond price drop -> -(-0.0185) / 16.5 * 10000 = +11.21 bps yield jump
        double yieldShockHikeBps = -(tltJump.moveIfEvent() / BL.TLT_MODIFIED_DURATION) * 10000.0;
        double yieldShockSurpriseBps = -(tltJump.moveIfNotEvent() / BL.TLT_MODIFIED_DURATION) * 10000.0;
        assertThat(yieldShockHikeBps).isCloseTo(11.21, within(0.1));
        assertThat(yieldShockSurpriseBps).isCloseTo(-50.91, within(0.1));

        // Cross-asset triangulation scenario from prompt:
        // TLT pricing 2.50% move (+-15.15 bps yield shock) while SPY prices only 0.60%
        BL.CrossAssetTriangulation res = BL.triangulateFomcVenues(0.80, 0.85, 0.025, 0.006, false);
        assertThat(res.tltImpliedYieldShockBps()).isCloseTo(15.15, within(0.1));
        assertThat(res.tltToSpyVolRatio()).isCloseTo(4.167, within(0.01));
        // Fair SPY = 2.50% / 1.25 = 2.00%
        assertThat(res.triangulatedSpyFairSigma()).isCloseTo(0.020, within(1e-6));
        // Edge = 2.00% - 0.60% = +1.40%
        assertThat(res.spyVolEdgePct()).isCloseTo(0.014, within(1e-6));
        assertThat(res.regimeVerdict()).contains("EQUITY VOL CHEAP");
        assertThat(res.spyVolEdgePct()).isGreaterThan(0.0);

        // Prompt user bug scenario: TLT = 1.41%, SPY = 1.94%
        // Fair SPY = 1.41% / 1.25 = 1.128% (~1.13%)
        // Edge = 1.128% - 1.94% = -0.812% (~-0.81%)
        BL.CrossAssetTriangulation res2 = BL.triangulateFomcVenues(0.80, 0.85, 0.0141, 0.0194, false);
        assertThat(res2.triangulatedSpyFairSigma() * 100.0).isCloseTo(1.128, within(0.01));
        assertThat(res2.spyVolEdgePct() * 100.0).isCloseTo(-0.812, within(0.01));
        assertThat(res2.regimeVerdict()).contains("RATES VOL CHEAP vs EQUITIES");
    }

    @Test
    void testTailAdjustedCmeProbability() {
        // Exact numbers from user prompt:
        // Delta_r = +20.89 bps = 0.2089%
        // Polymarket brackets:
        // 50+ bps decrease: 0.40% (0.0040)
        // 25 bps decrease: 0.55% (0.0055)
        // 50+ bps increase: 2.45% (0.0245)
        // 25 bps increase: 79.40% (0.7940)
        double deltaRatePct = 0.2089;
        BL.PolymarketTailBrackets tails = new BL.PolymarketTailBrackets(
                0.0040, 0.0055, 0.7940, 0.0245
        );

        BL.TailAdjustedCmeResult res = BL.solveTailAdjustedCme(deltaRatePct, tails);

        // Binary: 20.89 / 25 = 83.56%
        assertThat(res.pBondsBinary() * 100.0).isCloseTo(83.56, within(0.01));

        // Tail contribution = -50(0.0040) - 25(0.0055) + 50(0.0245) = -0.20 - 0.1375 + 1.225 = +0.8875 bps
        // p_hike25_zq = (20.89 - 0.8875) / 25 = 20.0025 / 25 = 80.01%
        assertThat(res.pHike25Zq() * 100.0).isCloseTo(80.01, within(0.01));

        // p_Bonds_TailAdjusted = 80.01% + 2.45% = 82.46%
        assertThat(res.pBondsTailAdjusted() * 100.0).isCloseTo(82.46, within(0.01));

        // Direct Polymarket expected bps comparison (no circular injection):
        // E_PM[Delta r] = -50(0.0040) - 25(0.0055) + 25(0.7940) + 50(0.0245) = +20.7375 bps
        assertThat(tails.expectedRateChangeBps()).isCloseTo(20.7375, within(0.01));
    }

    @Test
    @DisplayName("Issue 1 Fix: SPY Dec 2026 forward variance avoids Christmas V1 trap and strips NFP/CPI/Quad-Witching cluster")
    void testDecFomcSpyForwardVarianceAndMacroDeClustering() {
        // Exact critique numbers:
        // T1 (2026-12-04): 14.05% (~37 calendar days / 26 trading days)
        // T2 (2026-12-18): 14.73% (~51 calendar days / 36 trading days, 10 trading days after T1)
        // T3 (2026-12-31): 14.08% (~64 calendar days / 45 trading days, 9 trading days after T2)
        double vT1 = Math.pow(0.1405, 2) * (37.0 / 365.0); // 0.00200107
        double vT2 = Math.pow(0.1473, 2) * (51.0 / 365.0); // 0.00303168
        double vT3 = Math.pow(0.1408, 2) * (64.0 / 365.0); // 0.00347609

        // True pre-event daily trading variance (26 trading days to T1) vs Christmas holiday decay (T2 -> T3):
        double v1Pre = vT1 / 26.0; // ~0.00007696/day
        double v1PostHoliday = BL.calculateDailyNoiseVariance(vT2, vT3, 9.0); // ~0.00004938/day

        assertThat(v1PostHoliday).isLessThan(0.85 * v1Pre); // Holiday decay detected

        java.time.LocalDate t1Date = java.time.LocalDate.of(2026, 12, 4);
        java.time.LocalDate t2Date = java.time.LocalDate.of(2026, 12, 18);
        double extraMacroDays = BL.estimateMacroClusterExtraDays(t1Date, t2Date);
        // Window (Dec 4, Dec 18] includes mid-month CPI and Dec 18 Quadruple Witching
        assertThat(extraMacroDays).isGreaterThan(1.0);

        double sigmaCleaned = BL.impliedPureEventMove(vT1, vT2, v1Pre, 10.0, extraMacroDays);
        // Cleaned SPY FOMC move is ~1.12% (far below the broken 2.30% illusion)
        assertThat(sigmaCleaned * 100.0).isBetween(1.00, 1.35);
    }

    @Test
    @DisplayName("Issue 3 Fix: 2-state zero-drift jump solver and 3-state FOMC tree satisfy exact zero-drift and variance moments")
    void testSkewCoupledAndThreeStateFomcJumpSolver() {
        double p = 0.7999;
        double sigmaSpy = 0.0125;
        double spySkew = -1.4670; // Downside equity smirk
        double sigmaTlt = 0.0141;
        double tltSkew = +0.1500; // Symmetric / slightly positive bond call skew

        BL.ZeroDriftJump spyJump = BL.solveZeroDriftJump(sigmaSpy, p, spySkew, false);
        BL.ZeroDriftJump tltJump = BL.solveZeroDriftJump(sigmaTlt, p, tltSkew, false);

        // 2-state tree satisfies exact zero-drift martingale E[J] = 0 and Var(J) = sigma^2 at actual probability p:
        double spyMean2 = p * spyJump.moveIfEvent() + (1.0 - p) * spyJump.moveIfNotEvent();
        double spyVar2 = p * Math.pow(spyJump.moveIfEvent(), 2) + (1.0 - p) * Math.pow(spyJump.moveIfNotEvent(), 2);
        assertThat(spyMean2).isCloseTo(0.0, within(1e-9));
        assertThat(spyVar2).isCloseTo(sigmaSpy * sigmaSpy, within(1e-9));

        double tltMean2 = p * tltJump.moveIfEvent() + (1.0 - p) * tltJump.moveIfNotEvent();
        double tltVar2 = p * Math.pow(tltJump.moveIfEvent(), 2) + (1.0 - p) * Math.pow(tltJump.moveIfNotEvent(), 2);
        assertThat(tltMean2).isCloseTo(0.0, within(1e-9));
        assertThat(tltVar2).isCloseTo(sigmaTlt * sigmaTlt, within(1e-9));

        // 3-State FOMC Jump Tree (Hike + Hawkish Dots, Hike + Dovish Dots, Surprise Pause):
        BL.ThreeStateFomcJump threeState = BL.solveThreeStateJump(sigmaSpy, p, spySkew, false);
        BL.ThreeStateFomcJump threeStateTlt = BL.solveThreeStateJump(sigmaTlt, p, tltSkew, false);
        assertThat(threeState.pHawkish() + threeState.pDovish()).isCloseTo(p, within(1e-9));
        assertThat(threeState.pSurprise()).isCloseTo(1.0 - p, within(1e-9));
        // Skewness coupling differentiates SPY vs TLT 3-state branch probabilities and realized skewness:
        assertThat(threeState.realizedSkewness()).isLessThan(threeStateTlt.realizedSkewness());
        // No boundary clamping at 0.15 * p:
        assertThat(threeState.pHawkish()).isGreaterThan(0.155 * p);

        // 1. Exact zero-drift martingale:
        double mean = threeState.pHawkish() * threeState.moveHawkish()
                + threeState.pDovish() * threeState.moveDovish()
                + threeState.pSurprise() * threeState.moveSurprise();
        assertThat(mean).isCloseTo(0.0, within(1e-9));

        // 2. Exact total pure event variance:
        double variance = threeState.pHawkish() * Math.pow(threeState.moveHawkish(), 2)
                + threeState.pDovish() * Math.pow(threeState.moveDovish(), 2)
                + threeState.pSurprise() * Math.pow(threeState.moveSurprise(), 2);
        assertThat(variance).isCloseTo(sigmaSpy * sigmaSpy, within(1e-9));

        // 3. Hawkish dots produces a larger drop than Dovish dots, and Dot-Plot variance share is substantial:
        assertThat(threeState.moveHawkish()).isLessThan(threeState.moveDovish());
        assertThat(threeState.dotPlotVarianceShare()).isBetween(0.20, 0.85);
    }

    @Test
    @DisplayName("Issue 4 Fix: ZQZ26 effective-date weighting uses 9/31 and 22/31 for Dec 9 FOMC (+21.14 bps, 84.55% prob)")
    void testZqEffectiveDateWeightingDec9() {
        // Exact numbers from user critique:
        // Pre-FOMC ZQX26 = 96.12 (3.8800%), Dec ZQZ26 = 95.97 (4.0300%), FOMC on 2026-12-09 (effective Dec 10)
        // 9 days at 3.8800% and 22 days at r_post => r_post = (31 * 4.03 - 9 * 3.88) / 22 = 4.09136% (+21.14 bps)
        var res = com.eventrvcalc.ingestion.api.YFinanceOptionsIngestion.computeFedFundsProbability(
                96.12, 95.97, java.time.LocalDate.of(2026, 12, 9)
        );
        assertThat(res.ratePrePct()).isCloseTo(3.8800, within(1e-4));
        assertThat(res.ratePostImpliedPct()).isCloseTo(4.0914, within(1e-4));
        assertThat(res.deltaRatePct() * 100.0).isCloseTo(21.14, within(0.01));
        assertThat(res.pBonds() * 100.0).isCloseTo(84.55, within(0.02));
    }

    @Test
    @DisplayName("Fixed income tickers (TLT, IEF) exclude equity Quadruple Witching in macro de-clustering")
    void testTltFixedIncomeExcludesQuadWitching() {
        java.time.LocalDate t1 = java.time.LocalDate.of(2026, 12, 4);
        java.time.LocalDate t2 = java.time.LocalDate.of(2026, 12, 18);

        double extraSpy = BL.estimateMacroClusterExtraDays(t1, t2, "SPY");
        double extraTlt = BL.estimateMacroClusterExtraDays(t1, t2, "TLT");

        // SPY has both CPI (0.85) and Quad Witching (0.85) => 1.70
        assertThat(extraSpy).isCloseTo(1.70, within(1e-6));
        // TLT is fixed income, so Quad Witching is excluded => CPI only (0.85)
        assertThat(extraTlt).isCloseTo(0.85, within(1e-6));

        // When applied to TLT variance in Dec 2026, sigmaCleaned is positive (~0.61%):
        double vT1 = 0.00180632;
        double vT2 = 0.00256982;
        double v1Pre = 6.690086e-5;
        double sigmaTlt = BL.impliedPureEventMove(vT1, vT2, v1Pre, 10.0, extraTlt);
        assertThat(sigmaTlt).isGreaterThan(0.005);
    }

    @Test
    @DisplayName("HYG cross-asset beta sanity bounds: Capped relative to SPY and TLT")
    void testHygCrossAssetBetaBounds() {
        double rawHygSigma = 0.0197; // 1.97% implausible move
        double spySigma = 0.0025;    // 0.25%
        double tltSigma = 0.0053;    // 0.53% (3.2 bps shock)

        double boundedHyg = BL.applyCrossAssetSanityBounds("HYG", rawHygSigma, spySigma, tltSigma);

        // Theoretical cap: 0.50 * 0.0025 + 0.30 * 0.0053 = 0.00125 + 0.00159 = 0.00284 (0.284%)
        assertThat(boundedHyg).isLessThan(0.005);
        assertThat(boundedHyg).isCloseTo(0.00284, within(0.0005));

        // Normal HYG move within beta bounds is untouched:
        double normalHyg = 0.0020;
        assertThat(BL.applyCrossAssetSanityBounds("HYG", normalHyg, spySigma, tltSigma)).isEqualTo(normalHyg);
    }

    @Test
    @DisplayName("SPY residual sensitivity and SNR: Flags fragile residual and ill-conditioned edge")
    void testSpyResidualSensitivityAndSnr() {
        double vT1 = 0.0020;
        double vT2 = 0.0030;
        double sigmaEvent = 0.0025; // 0.25%
        double t2Years = 14.0 / 252.0;
        double atmVolT2 = 0.14;

        BL.ResidualSensitivity sens = BL.calculateResidualSensitivity(vT1, vT2, sigmaEvent, t2Years, atmVolT2);

        // Event variance is (0.0025)^2 = 0.00000625, which is ~0.2% of V_T2 (well under 3%)
        assertThat(sens.eventVarianceShare()).isLessThan(0.01);
        assertThat(sens.isFragile()).isTrue();

        // 0.1 vol point error swings event move by ~0.31%, which is > 100% of the move itself!
        assertThat(sens.moveSensitivityToVolPoint()).isGreaterThan(0.0025);

        // Solid event move (e.g. 1.2% on 2-day straddle) is not fragile:
        BL.ResidualSensitivity robust = BL.calculateResidualSensitivity(0.0005, 0.0008, 0.0120, 2.0 / 252.0, 0.15);
        assertThat(robust.isFragile()).isFalse();
    }

    @Test
    @DisplayName("Turn-adjusted CME ZQ vs Polymarket: Definitional divergence and December turn")
    void testTailAdjustedApplesToApplesDivergenceWithTurn() {
        double deltaRatePct = 0.2114; // +21.14 bps
        BL.PolymarketTailBrackets tails = new BL.PolymarketTailBrackets(
                0.0040, 0.0055, 0.7940, 0.0245
        );

        // 1. Unadjusted binary: 21.14 / 25 = 84.56%
        // Tail-adjusted: accounts for 50+ bps hike bracket (2.45%) and cut brackets
        BL.TailAdjustedCmeResult res = BL.solveTailAdjustedCme(deltaRatePct, tails, 0.0);
        assertThat(res.pBondsBinary() * 100.0).isCloseTo(84.56, within(0.02));

        // 2. With 2.0 bps December year-end turn discount:
        BL.TailAdjustedCmeResult resWithTurn = BL.solveTailAdjustedCme(deltaRatePct, tails, 2.0);
        assertThat(resWithTurn.deltaRateTurnAdjustedBps()).isCloseTo(19.14, within(0.01));
        assertThat(resWithTurn.pBondsTurnAndTailAdjusted()).isLessThan(res.pBondsTailAdjusted());
    }

    @Test
    @DisplayName("Cross-asset triangulation gates verdict on fragile SPY residual and fixes light shock label")
    void testCrossAssetTriangulationGatingOnFragileSpy() {
        double sigmaTlt = 0.005313; // 3.22 bps shock (0.005313 / 16.5 * 10000 = 3.22 bps)
        double sigmaSpy = 0.0025;   // Fragile 0.25% SPY residual
        double pRates = 0.8455;
        double pPoly = 0.8195;

        BL.CrossAssetTriangulation tri = BL.triangulateFomcVenues(
                pPoly, pRates, sigmaTlt, sigmaSpy, 6.69e-5, 7.69e-5, -0.15, -1.65, false
        );

        // Must NOT declare EQUITY VOL CHEAP based on fragile noise:
        assertThat(tri.regimeVerdict()).isEqualTo("INDETERMINATE (SPY RESIDUAL HIGH NOISE)");
        assertThat(tri.detailedExplanation()).contains("ill-conditioned residual");
        assertThat(tri.detailedExplanation()).contains("measurement noise");
        // Edge must be suppressed (NaN) when residual is fragile:
        assertThat(tri.spyVolEdgePct()).isNaN();
    }

    @Test
    @DisplayName("Venue 3 option skew is diagnostic only and does not drag down consensus probability")
    void testVenue3OptionSkewIsDiagnosticAndDoesNotDragDownProbability() {
        double pRates = 0.80;
        double pPoly = 0.80;
        double sigmaTlt = 0.012;
        double sigmaSpy = 0.010;

        // When TLT skew is NaN / absent, pTri = 0.65 * 0.80 + 0.35 * 0.80 = 0.80:
        var triNoOpt = BL.triangulateFomcVenues(pPoly, pRates, sigmaTlt, sigmaSpy, 1e-4, 1e-4, Double.NaN, Double.NaN, false);
        assertThat(triNoOpt.pOptions()).isNaN();
        assertThat(triNoOpt.pTriangulated()).isCloseTo(0.80, within(1e-4));

        // When TLT skew is -0.50 (put skew), option-implied jump probability pOptions diagnostic is ~62.13%:
        // S = -0.50 => p = 0.5 * (1 + 0.50 / sqrt(4 + 0.25)) = 0.5 * (1 + 0.2425) = 62.13%
        // But skew is under-identified; options layer must NOT vote in pTri:
        var triWithOpt = BL.triangulateFomcVenues(pPoly, pRates, sigmaTlt, sigmaSpy, 1e-4, 1e-4, -0.50, -1.50, false);
        assertThat(triWithOpt.pOptions()).isCloseTo(0.6213, within(0.001));
        // pTri must remain 0.80 (equal to 65/35 rates/prediction market blend), NOT dragged down to 77.3%:
        assertThat(triWithOpt.pTriangulated()).isEqualTo(triNoOpt.pTriangulated());
        assertThat(triWithOpt.pTriangulated()).isCloseTo(0.80, within(1e-4));

        // When explicit activeP is injected via overload, pTriangulated matches activeP exactly:
        var triActive = BL.triangulateFomcVenues(0.8053, pPoly, pRates, sigmaTlt, sigmaSpy, 1e-4, 1e-4, -0.50, -1.50, false);
        assertThat(triActive.pTriangulated()).isCloseTo(0.8053, within(1e-4));
    }

    private static double gauss(double x, double mu, double sigma) {
        double z = (x - mu) / sigma;
        return Math.exp(-0.5 * z * z) / (sigma * Math.sqrt(2.0 * Math.PI));
    }
}
