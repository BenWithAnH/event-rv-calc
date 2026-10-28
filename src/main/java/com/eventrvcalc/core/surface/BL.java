package com.eventrvcalc.core.surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Breeden-Litzenberger (1978) risk-neutral probability density extractor.
 *
 * Implements the fundamental relationship:
 * C(K) = e^(-r * T) * E^Q[max(0, S_T - K)] = e^(-r * T) * \int_K^\infty (s - K) * f_Q(s) ds
 *
 * Differentiating twice with respect to strike K yields:
 *   dC/dK = -e^(-r * T) * \int_K^\infty f_Q(s) ds = -e^(-r * T) * Q(S_T >= K)
 *   d^2C/dK^2 = e^(-r * T) * f_Q(K)
 *
 * Therefore, the risk-neutral probability density function f_Q(K) is given by:
 *   f_Q(K) = e^(r * T) * (d^2C / dK^2)
 *
 * and the cumulative distribution function F_Q(K) = Q(S_T <= K) is given by:
 *   F_Q(K) = 1 + e^(r * T) * (dC / dK)
 */
public class BL {

    public static final double DEFAULT_DK = 0.05;

    public record DensityPoint(
            double strike,
            double callPrice,
            double secondDerivative,
            double density,
            double cdf
    ) {}

    /**
     * Computes the numerical second derivative d^2C/dK^2 of the European call price curve
     * via central finite difference:
     *   d^2C/dK^2 = (C(K + dK) - 2 * C(K) + C(K - dK)) / (dK^2)
     *
     * Note: This is equivalent to the price of an infinitesimal butterfly spread
     * centered at strike K divided by dK^2.
     *
     * @param S0       Underlying spot price
     * @param K        Strike price
     * @param T        Expiry in years
     * @param r        Risk-free rate
     * @param q        Continuous dividend yield
     * @param volCurve Smooth volatility curve (e.g. SviVolCurve or QuadraticVolCurve)
     * @param dK       Strike bump size (e.g. 0.05)
     * @return Second derivative d^2C/dK^2
     */
    public static double secondDerivative(double S0, double K, double T, double r, double q,
                                          BS.VolCurve volCurve, double dK) {
        if (dK <= 0.0) {
            throw new IllegalArgumentException("dK must be strictly positive: " + dK);
        }

        double cUp = BS.europeanCallPrice(S0, K + dK, T, r, q, volCurve);
        double cMid = BS.europeanCallPrice(S0, K, T, r, q, volCurve);
        double cDown = BS.europeanCallPrice(S0, K - dK, T, r, q, volCurve);

        return (cUp - 2.0 * cMid + cDown) / (dK * dK);
    }

    public static double secondDerivative(double S0, double K, double T, double r, double q,
                                          BS.VolCurve volCurve) {
        return secondDerivative(S0, K, T, r, q, volCurve, DEFAULT_DK);
    }

    /**
     * Extracts the Breeden-Litzenberger risk-neutral probability density f_Q(K) at strike K:
     *   f_Q(K) = e^(r * T) * (d^2C / dK^2)
     *
     * Clamped to be non-negative (absence of butterfly arbitrage).
     */
    public static double density(double S0, double K, double T, double r, double q,
                                 BS.VolCurve volCurve, double dK) {
        double d2C = secondDerivative(S0, K, T, r, q, volCurve, dK);
        double fQ = Math.exp(r * T) * d2C;
        return Math.max(0.0, fQ);
    }

    public static double density(double S0, double K, double T, double r, double q,
                                 BS.VolCurve volCurve) {
        return density(S0, K, T, r, q, volCurve, DEFAULT_DK);
    }

    /**
     * Extracts the Breeden-Litzenberger risk-neutral cumulative distribution function F_Q(K) = Q(S_T <= K):
     *   F_Q(K) = 1 + e^(r * T) * (dC / dK)
     *
     * Clamped to [0.0, 1.0].
     */
    public static double cdf(double S0, double K, double T, double r, double q,
                             BS.VolCurve volCurve, double dK) {
        double cUp = BS.europeanCallPrice(S0, K + dK, T, r, q, volCurve);
        double cDown = BS.europeanCallPrice(S0, K - dK, T, r, q, volCurve);
        double dCdK = (cUp - cDown) / (2.0 * dK);

        double fVal = 1.0 + Math.exp(r * T) * dCdK;
        return Math.max(0.0, Math.min(1.0, fVal));
    }

    public static double cdf(double S0, double K, double T, double r, double q,
                             BS.VolCurve volCurve) {
        return cdf(S0, K, T, r, q, volCurve, DEFAULT_DK);
    }

    /**
     * Generates a complete Breeden-Litzenberger risk-neutral probability density slice
     * across a grid of strikes.
     *
     * @param S0            Underlying spot price
     * @param targetStrikes Array of strikes to evaluate
     * @param T             Expiry in years
     * @param r             Risk-free rate
     * @param q             Continuous dividend yield
     * @param volCurve      Smooth volatility curve (e.g. SviVolCurve)
     * @param dK            Finite difference step size
     * @return List of DensityPoint (strike, callPrice, secondDerivative, density, cdf)
     */
    public static List<DensityPoint> generateDensitySlice(double S0, double[] targetStrikes,
                                                         double T, double r, double q,
                                                         BS.VolCurve volCurve, double dK) {
        List<DensityPoint> slice = new ArrayList<>(targetStrikes.length);
        for (double K : targetStrikes) {
            double px = BS.europeanCallPrice(S0, K, T, r, q, volCurve);
            double d2C = secondDerivative(S0, K, T, r, q, volCurve, dK);
            double pdf = Math.max(0.0, Math.exp(r * T) * d2C);
            double cdfVal = cdf(S0, K, T, r, q, volCurve, dK);
            slice.add(new DensityPoint(K, px, d2C, pdf, cdfVal));
        }
        return slice;
    }

    public static List<DensityPoint> generateDensitySlice(double S0, double[] targetStrikes,
                                                         double T, double r, double q,
                                                         BS.VolCurve volCurve) {
        return generateDensitySlice(S0, targetStrikes, T, r, q, volCurve, DEFAULT_DK);
    }

    /**
     * Calculates the expected underlying price E^Q[S_T] = \int K * f_Q(K) dK via trapezoidal integration.
     * Should match the forward price F = S0 * e^((r - q) * T).
     */
    public static double calculateExpectedST(List<DensityPoint> slice) {
        if (slice == null || slice.size() < 2) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < slice.size() - 1; i++) {
            DensityPoint p0 = slice.get(i);
            DensityPoint p1 = slice.get(i + 1);
            double dK = p1.strike() - p0.strike();
            double y0 = p0.strike() * p0.density();
            double y1 = p1.strike() * p1.density();
            sum += 0.5 * (y0 + y1) * dK;
        }
        return sum;
    }

    /**
     * Calculates the total probability \int f_Q(K) dK via trapezoidal integration.
     * Should equal 1.0 across an extended strike support.
     */
    public static double calculateTotalProbability(List<DensityPoint> slice) {
        if (slice == null || slice.size() < 2) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < slice.size() - 1; i++) {
            DensityPoint p0 = slice.get(i);
            DensityPoint p1 = slice.get(i + 1);
            double dK = p1.strike() - p0.strike();
            sum += 0.5 * (p0.density() + p1.density()) * dK;
        }
        return sum;
    }

    // --- Event Comparison & Jump Extraction (from notes) ---

    public record LogReturnPoint(double x, double density) {}

    public record GaussianMixture(
            double p,
            double muUp,
            double sigmaUp,
            double muDown,
            double sigmaDown
    ) {
        public double density(double x) {
            return p * normalPdf(x, muUp, sigmaUp) + (1.0 - p) * normalPdf(x, muDown, sigmaDown);
        }
    }

    public record ZeroDriftJump(double u, double d) {}

    /**
     * Probability spread to wings: \Delta CDF(K) = F_{T2}(K) - F_{T1}(K)
     * across an overlay of two expiries (T1 before event, T2 after event).
     */
    public static double deltaCdf(double S0, double K,
                                  double T1, BS.VolCurve vol1,
                                  double T2, BS.VolCurve vol2,
                                  double r, double q, double dK) {
        return cdf(S0, K, T2, r, q, vol2, dK) - cdf(S0, K, T1, r, q, vol1, dK);
    }

    public static double deltaCdf(double S0, double K,
                                  double T1, BS.VolCurve vol1,
                                  double T2, BS.VolCurve vol2,
                                  double r, double q) {
        return deltaCdf(S0, K, T1, vol1, T2, vol2, r, q, DEFAULT_DK);
    }

    /**
     * Variance of an expiry window from its BL density slice:
     *   V_T = \int (\ln(K / F))^2 * f_T(K) dK
     */
    public static double calculateLogVariance(List<DensityPoint> slice, double forward) {
        if (slice == null || slice.size() < 2 || forward <= 0.0) {
            throw new IllegalArgumentException("Valid density slice (>=2 points) and positive forward required");
        }
        double sum = 0.0;
        for (int i = 0; i < slice.size() - 1; i++) {
            DensityPoint p0 = slice.get(i);
            DensityPoint p1 = slice.get(i + 1);
            if (p0.strike() <= 0.0 || p1.strike() <= 0.0) {
                throw new IllegalArgumentException("Strikes must be strictly positive");
            }
            double dK = p1.strike() - p0.strike();
            double x0 = Math.log(p0.strike() / forward);
            double x1 = Math.log(p1.strike() / forward);
            double y0 = x0 * x0 * p0.density();
            double y1 = x1 * x1 * p1.density();
            sum += 0.5 * (y0 + y1) * dK;
        }
        return sum;
    }

    /**
     * Background 1-day variance V_1 using a third expiry T3 (a week or two after event)
     * where T2 and T3 both include the same event jump:
     *   V_1 = (V_{T3} - V_{T2}) / tradingDaysBetweenT2AndT3
     */
    public static double calculateDailyNoiseVariance(double vT2, double vT3, double tradingDaysBetweenT2AndT3) {
        if (tradingDaysBetweenT2AndT3 <= 0.0) {
            throw new IllegalArgumentException("tradingDaysBetweenT2AndT3 must be strictly positive");
        }
        return (vT3 - vT2) / tradingDaysBetweenT2AndT3;
    }

    /**
     * Implied pure event move \sigma_{event} stripping out background noise over the window:
     *   \sigma_{event} = \sqrt{max(0, V_{T2} - V_{T1} - windowDays * V_1)}
     */
    public static double impliedPureEventMove(double vT1, double vT2, double v1Day, double windowDays) {
        if (windowDays < 0.0) {
            throw new IllegalArgumentException("windowDays must be non-negative");
        }
        return Math.sqrt(Math.max(0.0, vT2 - vT1 - windowDays * v1Day));
    }

    public static double impliedPureEventMove(double vT1, double vT2, double v1Day) {
        return impliedPureEventMove(vT1, vT2, v1Day, 1.0);
    }

    /**
     * Converts a strike-space BL density slice f_Q(K) into log-return space x = \ln(K / F),
     * with Jacobian adjustment f_X(x) = K * f_Q(K), resampled onto a uniform grid [xMin, xMax].
     */
    public static List<LogReturnPoint> toLogReturnSpace(List<DensityPoint> slice, double forward,
                                                        double xMin, double xMax, int numPoints) {
        if (slice == null || slice.size() < 2 || forward <= 0.0 || numPoints < 2 || xMax <= xMin) {
            throw new IllegalArgumentException("Invalid arguments for log-return conversion");
        }
        int n = slice.size();
        double[] xs = new double[n];
        double[] fx = new double[n];
        for (int i = 0; i < n; i++) {
            DensityPoint dp = slice.get(i);
            xs[i] = Math.log(dp.strike() / forward);
            fx[i] = dp.strike() * dp.density();
        }

        List<LogReturnPoint> out = new ArrayList<>(numPoints);
        double dx = (xMax - xMin) / (numPoints - 1);
        int idx = 0;
        for (int i = 0; i < numPoints; i++) {
            double x = xMin + i * dx;
            double val = 0.0;
            if (x > xs[0] && x < xs[n - 1]) {
                while (idx < n - 2 && xs[idx + 1] < x) {
                    idx++;
                }
                double t = (x - xs[idx]) / (xs[idx + 1] - xs[idx]);
                val = Math.max(0.0, fx[idx] + t * (fx[idx + 1] - fx[idx]));
            }
            out.add(new LogReturnPoint(x, val));
        }
        return out;
    }

    /**
     * Extracts the probability distribution of the jump itself by dividing the post-event
     * and pre-event log-return densities in frequency space via Tikhonov-regularized
     * Fourier transform:
     *   \Phi_J(\omega) = \frac{\Phi_2(\omega) \cdot \overline{\Phi_1(\omega)}}{|\Phi_1(\omega)|^2 + \lambda}
     */
    public static List<LogReturnPoint> deconvolveJumpDensity(List<LogReturnPoint> f1,
                                                             List<LogReturnPoint> f2,
                                                             double regularization) {
        if (f1 == null || f2 == null || f1.size() != f2.size() || f1.size() < 2) {
            throw new IllegalArgumentException("f1 and f2 must be non-null and share the same grid size >= 2");
        }
        int n = f1.size();
        if ((n & (n - 1)) != 0) {
            throw new IllegalArgumentException("Grid size must be a power of 2 for FFT: " + n);
        }
        double dx = f1.get(1).x() - f1.get(0).x();
        double xMin = f1.get(0).x();

        double[] re1 = new double[n];
        double[] im1 = new double[n];
        double[] re2 = new double[n];
        double[] im2 = new double[n];

        // Shift zero-return center to index 0 so phase aligns with origin x = 0
        int zeroShift = (int) Math.round(-xMin / dx);
        for (int i = 0; i < n; i++) {
            int shiftedIdx = Math.floorMod(i - zeroShift, n);
            re1[shiftedIdx] = f1.get(i).density() * dx;
            re2[shiftedIdx] = f2.get(i).density() * dx;
        }

        fft(re1, im1, false);
        fft(re2, im2, false);

        double[] reJ = new double[n];
        double[] imJ = new double[n];
        double reg = Math.max(1e-12, regularization);

        for (int k = 0; k < n; k++) {
            double mag1Sq = re1[k] * re1[k] + im1[k] * im1[k];
            double denom = mag1Sq + reg;
            // (re2 + i*im2) * (re1 - i*im1) / denom
            reJ[k] = (re2[k] * re1[k] + im2[k] * im1[k]) / denom;
            imJ[k] = (im2[k] * re1[k] - re2[k] * im1[k]) / denom;
        }

        fft(reJ, imJ, true);

        double[] rawDensity = new double[n];
        double area = 0.0;
        for (int i = 0; i < n; i++) {
            int shiftedIdx = Math.floorMod(i - zeroShift, n);
            rawDensity[i] = Math.max(0.0, reJ[shiftedIdx] / dx);
            if (i > 0) {
                area += 0.5 * (rawDensity[i - 1] + rawDensity[i]) * dx;
            }
        }

        List<LogReturnPoint> result = new ArrayList<>(n);
        double norm = area > 0.0 ? area : 1.0;
        for (int i = 0; i < n; i++) {
            result.add(new LogReturnPoint(f1.get(i).x(), rawDensity[i] / norm));
        }
        return result;
    }

    /**
     * Solves the two-bell-curve Gaussian mixture:
     *   f_Q(x) = p * N(\mu_{up}, \sigma_{up}^2) + (1 - p) * N(\mu_{down}, \sigma_{down}^2)
     * via Expectation-Maximization (EM).
     *
     * @param logDensity Target density in log-return space x = \ln(K / F)
     * @param pFixed     Optional external probability p \in (0, 1) (e.g. from prediction market;
     *                   null if unimplemented/unspecified to solve p from the density itself)
     */
    public static GaussianMixture fitGaussianMixture(List<LogReturnPoint> logDensity, Double pFixed) {
        if (logDensity == null || logDensity.size() < 3) {
            throw new IllegalArgumentException("logDensity must contain at least 3 points");
        }
        if (pFixed != null && (pFixed <= 0.0 || pFixed >= 1.0)) {
            throw new IllegalArgumentException("pFixed must be strictly in (0, 1)");
        }

        int n = logDensity.size();
        double dx = logDensity.get(1).x() - logDensity.get(0).x();
        double[] x = new double[n];
        double[] w = new double[n];
        double wSum = 0.0;
        for (int i = 0; i < n; i++) {
            x[i] = logDensity.get(i).x();
            w[i] = Math.max(0.0, logDensity.get(i).density()) * dx;
            wSum += w[i];
        }
        if (wSum <= 0.0) {
            throw new IllegalArgumentException("Density has zero total mass");
        }
        for (int i = 0; i < n; i++) {
            w[i] /= wSum;
        }

        double mean = 0.0;
        for (int i = 0; i < n; i++) mean += w[i] * x[i];
        double var = 0.0;
        for (int i = 0; i < n; i++) {
            double d = x[i] - mean;
            var += w[i] * d * d;
        }
        double std = Math.sqrt(Math.max(1e-8, var));

        double p = pFixed != null ? pFixed : 0.5;
        double muUp = mean + 0.5 * std;
        double muDown = mean - 0.5 * std;
        double sigmaUp = std * 0.7;
        double sigmaDown = std * 0.7;

        // ponytail: 200-step EM loop is O(N) per step; sufficient for 1D grids <= 4096 points.
        for (int iter = 0; iter < 200; iter++) {
            double sumGammaUp = 0.0;
            double sumGammaDown = 0.0;
            double newMuUp = 0.0;
            double newMuDown = 0.0;
            double[] gammaUp = new double[n];

            for (int i = 0; i < n; i++) {
                double pUp = p * normalPdf(x[i], muUp, sigmaUp);
                double pDown = (1.0 - p) * normalPdf(x[i], muDown, sigmaDown);
                double denom = pUp + pDown;
                double rUp = denom > 1e-30 ? pUp / denom : p;
                gammaUp[i] = rUp;

                double wUp = w[i] * rUp;
                double wDown = w[i] * (1.0 - rUp);
                sumGammaUp += wUp;
                sumGammaDown += wDown;
                newMuUp += wUp * x[i];
                newMuDown += wDown * x[i];
            }

            if (sumGammaUp > 1e-12) muUp = newMuUp / sumGammaUp;
            if (sumGammaDown > 1e-12) muDown = newMuDown / sumGammaDown;

            double varUp = 0.0;
            double varDown = 0.0;
            for (int i = 0; i < n; i++) {
                double dUp = x[i] - muUp;
                double dDown = x[i] - muDown;
                varUp += w[i] * gammaUp[i] * dUp * dUp;
                varDown += w[i] * (1.0 - gammaUp[i]) * dDown * dDown;
            }
            if (sumGammaUp > 1e-12) sigmaUp = Math.sqrt(Math.max(1e-8, varUp / sumGammaUp));
            if (sumGammaDown > 1e-12) sigmaDown = Math.sqrt(Math.max(1e-8, varDown / sumGammaDown));

            if (pFixed == null) {
                p = Math.max(1e-4, Math.min(1.0 - 1e-4, sumGammaUp));
            }
        }

        if (muUp < muDown) {
            // If pFixed was supplied, keep p associated with the positive (up) branch by re-anchoring
            if (pFixed == null) {
                return new GaussianMixture(1.0 - p, muDown, sigmaDown, muUp, sigmaUp);
            }
        }
        return new GaussianMixture(p, muUp, sigmaUp, muDown, sigmaDown);
    }

    /**
     * Combines event probability p \in (0, 1) with the zero-drift forward condition:
     *   p * u - (1 - p) * d = 0,   Var(J) = \sigma_{event}^2
     * yielding:
     *   jump if good (u) = +\sigma_{event} * \sqrt{(1 - p) / p}
     *   drop if poor (d) = -\sigma_{event} * \sqrt{p / (1 - p)}
     */
    public static ZeroDriftJump solveZeroDriftJump(double sigmaEvent, double p) {
        if (sigmaEvent < 0.0) {
            throw new IllegalArgumentException("sigmaEvent must be non-negative");
        }
        if (p <= 0.0 || p >= 1.0) {
            throw new IllegalArgumentException("Probability p must be strictly in (0, 1)");
        }
        double u = sigmaEvent * Math.sqrt((1.0 - p) / p);
        double d = -sigmaEvent * Math.sqrt(p / (1.0 - p));
        return new ZeroDriftJump(u, d);
    }

    private static double normalPdf(double x, double mu, double sigma) {
        double z = (x - mu) / sigma;
        return Math.exp(-0.5 * z * z) / (sigma * Math.sqrt(2.0 * Math.PI));
    }

    private static void fft(double[] re, double[] im, boolean invert) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tmpRe = re[i]; re[i] = re[j]; re[j] = tmpRe;
                double tmpIm = im[i]; im[i] = im[j]; im[j] = tmpIm;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2.0 * Math.PI / len * (invert ? 1 : -1);
            double wlenRe = Math.cos(ang);
            double wlenIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double wRe = 1.0;
                double wIm = 0.0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j], uIm = im[i + j];
                    double vRe = re[i + j + len / 2] * wRe - im[i + j + len / 2] * wIm;
                    double vIm = re[i + j + len / 2] * wIm + im[i + j + len / 2] * wRe;
                    re[i + j] = uRe + vRe;
                    im[i + j] = uIm + vIm;
                    re[i + j + len / 2] = uRe - vRe;
                    im[i + j + len / 2] = uIm - vIm;
                    double nextWRe = wRe * wlenRe - wIm * wlenIm;
                    wIm = wRe * wlenIm + wIm * wlenRe;
                    wRe = nextWRe;
                }
            }
        }
        if (invert) {
            for (int i = 0; i < n; i++) {
                re[i] /= n;
                im[i] /= n;
            }
        }
    }
}
