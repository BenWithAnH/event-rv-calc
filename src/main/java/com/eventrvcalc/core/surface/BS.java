package com.eventrvcalc.core.surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Black-Scholes (B.S.) European option pricing and volatility curve fitting.
 *
 * Implements the B.S. section of the notes:
 * - Once sigmaHat_K is computed for each strike via CRR, early exercise premium is removed.
 * - Fits a smooth volatility smile curve sigma(K) to de-Americanized vols.
 *   (Note: "fit SVI (stochastic vol inspired), which is just kinda a flat parabola.
 *    Then this trendline sigma(K) is plugged into BS - C_BS(K, sigma(K)) which gives
 *    smooth synthetic euro call price curve - then go into BL.")
 * - Evaluates Black-Scholes European call/put price curve using the fitted volatility.
 */
public class BS {

    @FunctionalInterface
    public interface VolCurve {
        double vol(double strike);
    }

    public record PricePoint(double strike, double vol, double price) {}

    /**
     * Quadratic polynomial volatility smile curve: sigma(K) = a * K^2 + b * K + c.
     *
     * ponytail: parabola approximation to SVI smile as noted in notes ("just kinda a flat parabola");
     * upgrade path is full non-linear quasi-explicit SVI calibration if asymmetric wings require it.
     */
    public record QuadraticVolCurve(double a, double b, double c) implements VolCurve {
        @Override
        public double vol(double strike) {
            double v = a * strike * strike + b * strike + c;
            // Guard against non-positive volatility
            return Math.max(1e-4, v);
        }
    }

    /**
     * Gatheral's 5-parameter raw SVI (Stochastic Volatility Inspired) total variance curve:
     * w(k) = a + b * (rho * (k - m) + sqrt((k - m)^2 + s^2))
     * where k = ln(K / S0) is spot log-moneyness, and sigma(K) = sqrt(w(k) / T).
     *
     * @param a   Overall variance level
     * @param b   Angle between asymptotes (b >= 0)
     * @param rho Skew / rotation parameter (-1 < rho < 1)
     * @param m   Translation parameter / vertex location in log-moneyness
     * @param s   Smoothness / roundness parameter (s > 0)
     * @param S0  Underlying spot price
     * @param T   Expiry in years
     */
    public record SviVolCurve(double a, double b, double rho, double m, double s, double S0, double T) implements VolCurve {
        @Override
        public double vol(double strike) {
            double k = Math.log(strike / S0);
            double w = totalVariance(k);
            return Math.sqrt(Math.max(1e-8, w) / T);
        }

        public double totalVariance(double k) {
            double diff = k - m;
            return a + b * (rho * diff + Math.sqrt(diff * diff + s * s));
        }

        /**
         * Returns the strike price at the vertex of the SVI hyperbola: K_vertex = S0 * e^m.
         */
        public double vertexStrike() {
            return S0 * Math.exp(m);
        }
    }

    /**
     * Standard Normal Cumulative Distribution Function Phi(x).
     */
    public static double normCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * Error function erf(z) via Chebyshev rational approximation (max error < 1.2e-7).
     */
    public static double erf(double z) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(z));
        double ans = 1.0 - t * Math.exp(-z * z - 1.26551223 +
                t * (1.00002368 +
                t * (0.37409196 +
                t * (0.09678418 +
                t * (-0.18628806 +
                t * (0.27886807 +
                t * (-1.13520398 +
                t * (1.48851587 +
                t * (-0.82215223 +
                t * 0.17087277)))))))));
        return z >= 0.0 ? ans : -ans;
    }

    /**
     * Black-Scholes European option pricing: C_BS(S0, K, T, r, q, sigma).
     *
     * @param S0    Underlying spot price
     * @param K     Strike price
     * @param T     Expiry in years
     * @param r     Risk-free rate
     * @param q     Continuous dividend yield
     * @param sigma Volatility
     * @param eta   Option type: +1 for call, -1 for put
     * @return European option price
     */
    public static double price(double S0, double K, double T, double r, double q, double sigma, int eta) {
        if (T <= 0.0) {
            return Math.max(0.0, eta * (S0 - K));
        }
        if (sigma <= 0.0) {
            double forward = S0 * Math.exp((r - q) * T);
            return Math.exp(-r * T) * Math.max(0.0, eta * (forward - K));
        }

        double sqrtT = Math.sqrt(T);
        double d1 = (Math.log(S0 / K) + (r - q + 0.5 * sigma * sigma) * T) / (sigma * sqrtT);
        double d2 = d1 - sigma * sqrtT;

        double dfQ = Math.exp(-q * T);
        double dfR = Math.exp(-r * T);

        return eta * (S0 * dfQ * normCdf(eta * d1) - K * dfR * normCdf(eta * d2));
    }

    /**
     * European call price shorthand: C_BS(S0, K, T, r, q, sigma).
     */
    public static double callPrice(double S0, double K, double T, double r, double q, double sigma) {
        return price(S0, K, T, r, q, sigma, 1);
    }

    /**
     * European put price shorthand: P_BS(S0, K, T, r, q, sigma).
     */
    public static double putPrice(double S0, double K, double T, double r, double q, double sigma) {
        return price(S0, K, T, r, q, sigma, -1);
    }

    /**
     * Fits a quadratic curve sigma(K) = a * K^2 + b * K + c through strikes and volatilities
     * via ordinary least squares (normal equations).
     */
    public static QuadraticVolCurve fitVolCurve(double[] strikes, double[] vols) {
        if (strikes == null || vols == null || strikes.length != vols.length || strikes.length == 0) {
            throw new IllegalArgumentException("Strikes and vols must be non-empty and of equal length");
        }

        int n = strikes.length;
        if (n == 1) {
            return new QuadraticVolCurve(0.0, 0.0, vols[0]);
        }
        if (n == 2) {
            // Linear fit
            double slope = (vols[1] - vols[0]) / (strikes[1] - strikes[0]);
            double intercept = vols[0] - slope * strikes[0];
            return new QuadraticVolCurve(0.0, slope, intercept);
        }

        // Construct 3x3 normal equations: (X^T * X) * beta = X^T * y
        double s0 = n;
        double s1 = 0, s2 = 0, s3 = 0, s4 = 0;
        double sy = 0, sxy = 0, sx2y = 0;

        for (int i = 0; i < n; i++) {
            double x = strikes[i];
            double y = vols[i];
            double x2 = x * x;
            s1 += x;
            s2 += x2;
            s3 += x2 * x;
            s4 += x2 * x2;
            sy += y;
            sxy += x * y;
            sx2y += x2 * y;
        }

        // System matrix:
        // [ s4  s3  s2 ] [ a ]   [ sx2y ]
        // [ s3  s2  s1 ] [ b ] = [ sxy  ]
        // [ s2  s1  s0 ] [ c ]   [ sy   ]
        double m00 = s4, m01 = s3, m02 = s2;
        double m10 = s3, m11 = s2, m12 = s1;
        double m20 = s2, m21 = s1, m22 = s0;

        double det = m00 * (m11 * m22 - m12 * m21)
                   - m01 * (m10 * m22 - m12 * m20)
                   + m02 * (m10 * m21 - m11 * m20);

        if (Math.abs(det) < 1e-12) {
            // Fallback to average vol
            return new QuadraticVolCurve(0.0, 0.0, sy / n);
        }

        // Cramer's rule:
        double detA = sx2y * (m11 * m22 - m12 * m21)
                    - m01  * (sxy  * m22 - m12 * sy)
                    + m02  * (sxy  * m21 - m11 * sy);

        double detB = m00  * (sxy  * m22 - m12 * sy)
                    - sx2y * (m10  * m22 - m12 * m20)
                    + m02  * (m10  * sy  - sxy  * m20);

        double detC = m00  * (m11 * sy   - sxy  * m21)
                    - m01  * (m10 * sy   - sxy  * m20)
                    + sx2y * (m10 * m21  - m11 * m20);

        return new QuadraticVolCurve(detA / det, detB / det, detC / det);
    }

    /**
     * Fits a quadratic volatility curve from a Map of strike -> implied vol.
     */
    public static QuadraticVolCurve fitVolCurve(Map<Double, Double> strikeToVol) {
        int size = strikeToVol.size();
        double[] strikes = new double[size];
        double[] vols = new double[size];
        int idx = 0;
        for (Map.Entry<Double, Double> entry : strikeToVol.entrySet()) {
            strikes[idx] = entry.getKey();
            vols[idx] = entry.getValue();
            idx++;
        }
        return fitVolCurve(strikes, vols);
    }

    /**
     * Fits the 5 SVI parameters (a, b, rho, m, s) to total variance w(k) = sigmaHat_K^2 * T
     * using quasi-explicit calibration: 3x3 linear least-squares for (a, b, rho) combined
     * with 2D Nelder-Mead simplex search over (m, s).
     *
     * @param S0       Underlying spot price
     * @param T        Expiry in years
     * @param strikes  Array of option strike prices
     * @param vols     Array of de-Americanized implied volatilities sigmaHat_K
     * @param mInitial Initial guess for vertex location m (e.g. 0.10)
     * @param sInitial Initial guess for smoothness s (e.g. 0.025)
     * @return Fitted SviVolCurve
     */
    public static SviVolCurve fitSvi(double S0, double T, double[] strikes, double[] vols,
                                     double mInitial, double sInitial) {
        if (strikes == null || vols == null || strikes.length != vols.length || strikes.length < 5) {
            throw new IllegalArgumentException("At least 5 strikes and vols are required for 5-parameter SVI fit");
        }

        int n = strikes.length;
        double[] k = new double[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            k[i] = Math.log(strikes[i] / S0);
            w[i] = vols[i] * vols[i] * T;
        }

        // Objective evaluation helper for given (m, s)
        class SviEvaluator {
            double lastA, lastB, lastRho;

            double evaluate(double m, double s) {
                if (s < 1e-4) {
                    return 1e8 + (1e-4 - s) * 1e8;
                }
                // Construct normal equations for w = a + c1*(k - m) + c2*sqrt((k - m)^2 + s^2)
                double s00 = 0, s01 = 0, s02 = 0;
                double s11 = 0, s12 = 0, s22 = 0;
                double sy0 = 0, sy1 = 0, sy2 = 0;

                for (int i = 0; i < n; i++) {
                    double x1 = k[i] - m;
                    double x2 = Math.sqrt(x1 * x1 + s * s);
                    double wi = w[i];

                    s00 += 1.0;
                    s01 += x1;
                    s02 += x2;
                    s11 += x1 * x1;
                    s12 += x1 * x2;
                    s22 += x2 * x2;

                    sy0 += wi;
                    sy1 += x1 * wi;
                    sy2 += x2 * wi;
                }

                // 3x3 system:
                // [ s00 s01 s02 ] [ a  ]   [ sy0 ]
                // [ s01 s11 s12 ] [ c1 ] = [ sy1 ]
                // [ s02 s12 s22 ] [ c2 ]   [ sy2 ]
                double det = s00 * (s11 * s22 - s12 * s12)
                           - s01 * (s01 * s22 - s12 * s02)
                           + s02 * (s01 * s12 - s11 * s02);

                if (Math.abs(det) < 1e-15) {
                    return 1e8;
                }

                double detA = sy0 * (s11 * s22 - s12 * s12)
                            - s01 * (sy1 * s22 - s12 * sy2)
                            + s02 * (sy1 * s12 - s11 * sy2);

                double detC1 = s00 * (sy1 * s22 - s12 * sy2)
                             - sy0 * (s01 * s22 - s12 * s02)
                             + s02 * (s01 * sy2 - sy1 * s02);

                double detC2 = s00 * (s11 * sy2 - sy1 * s12)
                             - s01 * (s01 * sy2 - sy1 * s02)
                             + sy0 * (s01 * s12 - s11 * s02);

                double a = detA / det;
                double c1 = detC1 / det;
                double c2 = detC2 / det;

                double b = Math.max(1e-5, Math.min(1.5 * T, c2));
                double rho = Math.max(-0.95, Math.min(0.95, c1 / b));

                double sse = 0.0;
                for (int i = 0; i < n; i++) {
                    double diff = k[i] - m;
                    double wPred = a + b * (rho * diff + Math.sqrt(diff * diff + s * s));
                    double err = wPred - w[i];
                    sse += err * err;
                }

                if (c2 < 1e-5) {
                    sse += 10.0 * (1e-5 - c2) * (1e-5 - c2);
                } else if (c2 > 1.5 * T) {
                    double excessB = c2 - 1.5 * T;
                    sse += 10.0 * excessB * excessB;
                }
                if (a < 0.0) {
                    sse += 10.0 * a * a;
                }
                double minVertexVar = a + b * s * Math.sqrt(Math.max(0.0, 1.0 - rho * rho));
                if (minVertexVar < 0.0) {
                    sse += 50.0 * minVertexVar * minVertexVar;
                }

                this.lastA = a;
                this.lastB = b;
                this.lastRho = rho;
                return sse;
            }
        }

        SviEvaluator eval = new SviEvaluator();

        // 2D Nelder-Mead Simplex on (m, s)
        double[][] p = new double[][] {
            { mInitial, sInitial },
            { mInitial + 0.02, sInitial },
            { mInitial, sInitial + 0.01 }
        };
        double[] f = new double[3];
        for (int i = 0; i < 3; i++) {
            f[i] = eval.evaluate(p[i][0], p[i][1]);
        }

        for (int iter = 0; iter < 80; iter++) {
            int[] order = new int[] { 0, 1, 2 };
            if (f[order[0]] > f[order[1]]) { int tmp = order[0]; order[0] = order[1]; order[1] = tmp; }
            if (f[order[1]] > f[order[2]]) { int tmp = order[1]; order[1] = order[2]; order[2] = tmp; }
            if (f[order[0]] > f[order[1]]) { int tmp = order[0]; order[0] = order[1]; order[1] = tmp; }

            int best = order[0];
            int second = order[1];
            int worst = order[2];

            double cm = 0.5 * (p[best][0] + p[second][0]);
            double cs = 0.5 * (p[best][1] + p[second][1]);

            // Reflection
            double rm = cm + 1.0 * (cm - p[worst][0]);
            double rs = cs + 1.0 * (cs - p[worst][1]);
            double fr = eval.evaluate(rm, rs);

            if (fr >= f[best] && fr < f[second]) {
                p[worst][0] = rm; p[worst][1] = rs; f[worst] = fr;
            } else if (fr < f[best]) {
                // Expansion
                double em = cm + 2.0 * (rm - cm);
                double es = cs + 2.0 * (rs - cs);
                double fe = eval.evaluate(em, es);
                if (fe < fr) {
                    p[worst][0] = em; p[worst][1] = es; f[worst] = fe;
                } else {
                    p[worst][0] = rm; p[worst][1] = rs; f[worst] = fr;
                }
            } else {
                // Contraction
                boolean outside = fr < f[worst];
                double contrM = outside ? cm + 0.5 * (rm - cm) : cm - 0.5 * (cm - p[worst][0]);
                double contrS = outside ? cs + 0.5 * (rs - cs) : cs - 0.5 * (cs - p[worst][1]);
                double fc = eval.evaluate(contrM, contrS);
                if (fc < f[worst]) {
                    p[worst][0] = contrM; p[worst][1] = contrS; f[worst] = fc;
                } else {
                    // Shrink
                    for (int i = 1; i < 3; i++) {
                        int idx = order[i];
                        p[idx][0] = p[best][0] + 0.5 * (p[idx][0] - p[best][0]);
                        p[idx][1] = p[best][1] + 0.5 * (p[idx][1] - p[best][1]);
                        f[idx] = eval.evaluate(p[idx][0], p[idx][1]);
                    }
                }
            }
        }

        int bestIdx = 0;
        if (f[1] < f[bestIdx]) bestIdx = 1;
        if (f[2] < f[bestIdx]) bestIdx = 2;

        double optM = p[bestIdx][0];
        double optS = p[bestIdx][1];
        eval.evaluate(optM, optS);

        return new SviVolCurve(eval.lastA, eval.lastB, eval.lastRho, optM, optS, S0, T);
    }

    /**
     * Fits the 5 SVI parameters (a, b, rho, m, s) to total variance with default vertex guess m ~ +0.10.
     */
    public static SviVolCurve fitSvi(double S0, double T, double[] strikes, double[] vols) {
        return fitSvi(S0, T, strikes, vols, 0.10, 0.025);
    }

    /**
     * De-Americanizes market prices across strikes using CRR to find sigmaHat for each strike,
     * then fits a volatility curve.
     *
     * @param S0           Underlying price
     * @param T            Expiry in years
     * @param r            Risk-free rate
     * @param q            Continuous dividend yield
     * @param N            CRR tree steps
     * @param strikes      Array of option strikes
     * @param marketPrices Mid market prices for each strike
     * @param etas         Option type for each strike (+1 for call, -1 for put)
     * @return Fitted volatility curve sigma(K)
     */
    public static QuadraticVolCurve fitFromCrr(double S0, double T, double r, double q, int N,
                                               double[] strikes, double[] marketPrices, int[] etas) {
        if (strikes.length != marketPrices.length || strikes.length != etas.length) {
            throw new IllegalArgumentException("Input arrays must have the same length");
        }

        double[] vols = new double[strikes.length];
        for (int i = 0; i < strikes.length; i++) {
            vols[i] = CRR.findSigmaHat(S0, strikes[i], T, r, q, N, etas[i], marketPrices[i]);
        }

        return fitVolCurve(strikes, vols);
    }

    /**
     * Evaluates a synthetic European option price using the fitted volatility curve:
     * C_BS(S0, K, T, r, q, sigma(K))
     */
    public static double europeanPrice(double S0, double K, double T, double r, double q, VolCurve volCurve, int eta) {
        double vol = volCurve.vol(K);
        return price(S0, K, T, r, q, vol, eta);
    }

    /**
     * Evaluates a synthetic European call price using the fitted volatility curve.
     */
    public static double europeanCallPrice(double S0, double K, double T, double r, double q, VolCurve volCurve) {
        return europeanPrice(S0, K, T, r, q, volCurve, 1);
    }

    /**
     * Generates a smooth synthetic European price curve across target strikes.
     *
     * @param S0            Underlying spot price
     * @param targetStrikes Array of strikes to evaluate on the price curve
     * @param T             Expiry in years
     * @param r             Risk-free rate
     * @param q             Continuous dividend yield
     * @param volCurve      Fitted volatility curve sigma(K)
     * @param eta           Option type (+1 for call, -1 for put)
     * @return List of PricePoint (strike, vol, synthetic European price)
     */
    public static List<PricePoint> generatePriceCurve(double S0, double[] targetStrikes, double T, double r, double q,
                                                      VolCurve volCurve, int eta) {
        List<PricePoint> points = new ArrayList<>(targetStrikes.length);
        for (double K : targetStrikes) {
            double vol = volCurve.vol(K);
            double px = price(S0, K, T, r, q, vol, eta);
            points.add(new PricePoint(K, vol, px));
        }
        return points;
    }
}
