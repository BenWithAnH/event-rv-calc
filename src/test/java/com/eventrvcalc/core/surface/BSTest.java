package com.eventrvcalc.core.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BSTest {

    @Test
    @DisplayName("Black-Scholes call matches known analytical benchmark")
    void testCallPriceBenchmark() {
        double S0 = 100.0;
        double K = 100.0;
        double T = 1.0;
        double r = 0.05;
        double q = 0.0;
        double sigma = 0.20;

        double call = BS.callPrice(S0, K, T, r, q, sigma);

        // Analytical Black-Scholes call price is ~10.4505756
        assertThat(call).isCloseTo(10.45058, within(1e-4));
    }

    @Test
    @DisplayName("Put-call parity holds for European Black-Scholes prices")
    void testPutCallParity() {
        double S0 = 105.0;
        double K = 100.0;
        double T = 0.75;
        double r = 0.04;
        double q = 0.02;
        double sigma = 0.25;

        double call = BS.callPrice(S0, K, T, r, q, sigma);
        double put = BS.putPrice(S0, K, T, r, q, sigma);

        double parityRhs = S0 * Math.exp(-q * T) - K * Math.exp(-r * T);

        assertThat(call - put).isCloseTo(parityRhs, within(1e-5));
    }

    @Test
    @DisplayName("fitVolCurve accurately fits quadratic smile curve")
    void testFitVolCurve() {
        double[] strikes = {90.0, 95.0, 100.0, 105.0, 110.0};
        // Parabola: sigma(K) = 0.0001*(K - 100)^2 + 0.20 = 0.0001*K^2 - 0.02*K + 1.20
        double[] vols = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            vols[i] = 0.0001 * Math.pow(strikes[i] - 100.0, 2) + 0.20;
        }

        BS.QuadraticVolCurve curve = BS.fitVolCurve(strikes, vols);

        for (int i = 0; i < strikes.length; i++) {
            assertThat(curve.vol(strikes[i])).isCloseTo(vols[i], within(1e-5));
        }

        // Interpolation at strike 97.5
        double expectedAt975 = 0.0001 * Math.pow(97.5 - 100.0, 2) + 0.20;
        assertThat(curve.vol(97.5)).isCloseTo(expectedAt975, within(1e-5));
    }

    @Test
    @DisplayName("Integration: CRR de-Americanization fits vol curve and generates European price curve")
    void testCrrToBsPriceCurve() {
        double S0 = 100.0;
        double T = 0.5;
        double r = 0.03;
        double q = 0.01;
        int N = 50;

        double[] strikes = {90.0, 100.0, 110.0};
        int[] etas = {1, 1, 1}; // Calls
        double[] marketVols = {0.24, 0.20, 0.23}; // Smile

        // Generate synthetic American market prices using CRR
        double[] marketPrices = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            marketPrices[i] = CRR.price(S0, strikes[i], T, r, q, marketVols[i], N, etas[i]);
        }

        // De-Americanize and fit vol curve
        BS.QuadraticVolCurve fittedCurve = BS.fitFromCrr(S0, T, r, q, N, strikes, marketPrices, etas);

        // Vol curve should match the original smile vols
        for (int i = 0; i < strikes.length; i++) {
            assertThat(fittedCurve.vol(strikes[i])).isCloseTo(marketVols[i], within(1e-4));
        }

        // Generate synthetic European price curve on a finer strike grid
        double[] grid = {85.0, 90.0, 95.0, 100.0, 105.0, 110.0, 115.0};
        List<BS.PricePoint> priceCurve = BS.generatePriceCurve(S0, grid, T, r, q, fittedCurve, 1);

        assertThat(priceCurve).hasSize(grid.length);

        // European call price curve must be strictly positive and monotonically decreasing with strike
        for (int i = 0; i < priceCurve.size() - 1; i++) {
            BS.PricePoint curr = priceCurve.get(i);
            BS.PricePoint next = priceCurve.get(i + 1);

            assertThat(curr.price()).isGreaterThan(0.0);
            assertThat(curr.price()).isGreaterThan(next.price());
        }
    }

    @Test
    @DisplayName("SviVolCurve evaluates total variance and implied volatility accurately")
    void testSviVolCurve() {
        double S0 = 381.10;
        double T = 0.1233;
        double a = 0.0039;
        double b = 0.0146;
        double rho = -0.54;
        double m = 0.09;
        double s = 0.021;

        BS.SviVolCurve svi = new BS.SviVolCurve(a, b, rho, m, s, S0, T);

        // At vertex k = m: w(m) = a + b * s
        double expectedAtM = a + b * s;
        assertThat(svi.totalVariance(m)).isCloseTo(expectedAtM, within(1e-6));

        // Vertex strike should be ~S0 * e^m = 381.10 * e^0.09 ~ 416.9
        assertThat(svi.vertexStrike()).isCloseTo(381.10 * Math.exp(0.09), within(0.1));

        // Volatility at vertex
        double expectedVol = Math.sqrt(expectedAtM / T);
        assertThat(svi.vol(svi.vertexStrike())).isCloseTo(expectedVol, within(1e-4));
    }

    @Test
    @DisplayName("fitSvi calibrates 5 SVI parameters with vertex around m ~ +0.10 (K ~ 421)")
    void testFitSvi() {
        double S0 = 381.10;
        double T = 0.1233;
        double trueA = 0.00392;
        double trueB = 0.01462;
        double trueRho = -0.543;
        double trueM = 0.090; // vertex near ~0.10
        double trueS = 0.0210;

        BS.SviVolCurve trueCurve = new BS.SviVolCurve(trueA, trueB, trueRho, trueM, trueS, S0, T);

        double[] strikes = {340.0, 360.0, 380.0, 400.0, 420.0, 440.0};
        double[] vols = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            vols[i] = trueCurve.vol(strikes[i]);
        }

        BS.SviVolCurve fitted = BS.fitSvi(S0, T, strikes, vols, 0.10, 0.025);

        // Vertex m should be close to 0.09 - 0.10
        assertThat(fitted.m()).isCloseTo(trueM, within(0.015));
        // Vertex strike should be around 415 - 425
        assertThat(fitted.vertexStrike()).isBetween(410.0, 430.0);

        // Volatilities at test strikes should match within 0.1% vol
        for (int i = 0; i < strikes.length; i++) {
            assertThat(fitted.vol(strikes[i])).isCloseTo(vols[i], within(0.001));
        }
    }
}
