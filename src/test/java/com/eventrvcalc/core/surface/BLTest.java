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
    }

    private static double gauss(double x, double mu, double sigma) {
        double z = (x - mu) / sigma;
        return Math.exp(-0.5 * z * z) / (sigma * Math.sqrt(2.0 * Math.PI));
    }
}
