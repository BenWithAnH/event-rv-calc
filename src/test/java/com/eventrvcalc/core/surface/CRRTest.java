package com.eventrvcalc.core.surface;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CRRTest {

    @Test
    @DisplayName("CRR American call converges close to Black-Scholes when q=0")
    void testAmericanCallPrice() {
        double S0 = 100.0;
        double K = 100.0;
        double T = 1.0;
        double r = 0.05;
        double q = 0.0;
        double sigma = 0.20;
        int N = 100;
        int eta = 1; // call

        double price = CRR.price(S0, K, T, r, q, sigma, N, eta);

        // Black-Scholes call is ~10.4506, CRR with N=100 is very close
        assertThat(price).isCloseTo(10.45, within(0.15));
    }

    @Test
    @DisplayName("CRR American put respects early exercise intrinsic value")
    void testAmericanPutEarlyExercise() {
        double S0 = 70.0;
        double K = 100.0;
        double T = 1.0;
        double r = 0.05;
        double q = 0.0;
        double sigma = 0.20;
        int N = 100;
        int eta = -1; // put

        double price = CRR.price(S0, K, T, r, q, sigma, N, eta);
        double intrinsic = K - S0; // 30.0

        assertThat(price).isGreaterThanOrEqualTo(intrinsic);
    }

    @Test
    @DisplayName("findSigmaHat Brent and Newton-Raphson recover original volatility")
    void testFindSigmaHatInversion() {
        double S0 = 100.0;
        double K = 105.0; // OTM call
        double T = 0.5;
        double r = 0.04;
        double q = 0.01;
        double trueSigma = 0.28;
        int N = 50;
        int eta = 1;

        double vMkt = CRR.price(S0, K, T, r, q, trueSigma, N, eta);

        double recoveredBrent = CRR.findSigmaHatBrent(S0, K, T, r, q, N, eta, vMkt);
        double recoveredNR = CRR.findSigmaHatNewtonRaphson(S0, K, T, r, q, N, eta, vMkt);

        assertThat(recoveredBrent).isCloseTo(trueSigma, within(1e-5));
        assertThat(recoveredNR).isCloseTo(trueSigma, within(1e-5));
    }

    @Test
    @DisplayName("isOtm correctly identifies OTM calls and puts")
    void testIsOtm() {
        assertThat(CRR.isOtm(95.0, 100.0, 1)).isTrue();   // S0 < K for call
        assertThat(CRR.isOtm(105.0, 100.0, 1)).isFalse();
        assertThat(CRR.isOtm(105.0, 100.0, -1)).isTrue();  // S0 > K for put
        assertThat(CRR.isOtm(95.0, 100.0, -1)).isFalse();
    }

    @Test
    @DisplayName("CRR European price matches Black-Scholes and satisfies American >= European")
    void testEuropeanPrice() {
        double S0 = 100.0;
        double K = 100.0;
        double T = 1.0;
        double r = 0.05;
        double q = 0.0;
        double sigma = 0.20;
        int N = 100;

        double euroCall = CRR.europeanPrice(S0, K, T, r, q, sigma, N, 1);
        double euroPut = CRR.europeanPrice(S0, K, T, r, q, sigma, N, -1);
        double amPut = CRR.price(S0, K, T, r, q, sigma, N, -1);

        assertThat(euroCall).isCloseTo(10.45, within(0.15));
        assertThat(amPut).isGreaterThanOrEqualTo(euroPut);
    }

    @Test
    @DisplayName("implyEffectiveSpot removes ATM smile gap by adjusting for put EEP")
    void testImplyEffectiveSpot() {
        double K = 381.0;
        double cMid = 12.87;
        double pMid = 10.915;
        double T = 45.0 / 365.0;
        double r = 0.043;
        int N = 100;

        double sImplied = CRR.implyEffectiveSpot(K, cMid, pMid, T, r, N);

        // Implied spot should be around 381.10
        assertThat(sImplied).isCloseTo(381.10, within(0.05));

        // Call and put de-Americanized implied vols at ATM should match within 0.05%
        double volCall = CRR.findSigmaHat(sImplied, K, T, r, 0.0, N, 1, cMid);
        double volPut = CRR.findSigmaHat(sImplied, K, T, r, 0.0, N, -1, pMid);
        assertThat(volCall).isCloseTo(volPut, within(0.001));
    }

    @Test
    @DisplayName("findBlendedSigmaHat smoothly interpolates call and put implied vols")
    void testFindBlendedSigmaHat() {
        double S0 = 381.10;
        double K = 381.0;
        double T = 45.0 / 365.0;
        double r = 0.043;
        double q = 0.0;
        int N = 100;
        double cMid = 12.87;
        double pMid = 10.915;

        double purePutVol = CRR.findBlendedSigmaHat(S0, K, T, r, q, N, cMid, pMid, 0.0, 1.0);
        double halfVol = CRR.findBlendedSigmaHat(S0, K, T, r, q, N, cMid, pMid, 0.5, 0.5);
        double pureCallVol = CRR.findBlendedSigmaHat(S0, K, T, r, q, N, cMid, pMid, 1.0, 0.0);

        assertThat(purePutVol).isCloseTo(pureCallVol, within(0.001));
        assertThat(halfVol).isCloseTo(0.5 * (purePutVol + pureCallVol), within(1e-6));
    }
}
