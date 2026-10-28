package com.eventrvcalc.core.surface;

/**
 * Cox-Ross-Rubinstein (CRR) binomial tree model for American option pricing
 * and implied volatility inversion (de-Americanization).
 *
 * Implements the exact math/logic from the notes:
 * - Tree parameters: deltaT = T / N, u = e^(sigma * sqrt(deltaT)), d = 1 / u
 * - Risk-neutral probability: p = (e^((r - q) * deltaT) - d) / (u - d), down prob = 1 - p
 * - Terminal node prices and payoffs: S_{N, j} = S0 * u^j * d^(N - j), V_{N, j} = max(0, eta * (S_{N, j} - K))
 * - Backward induction: V_{i, j}^{Am}(sigma) = max(eta * (S_{i, j} - K), e^(-r * deltaT) * (p * V_{i+1, j+1} + (1 - p) * V_{i+1, j}))
 * - Root node: V_{0, 0}^{Am}(sigma)
 * - De-Americanized implied volatility: find sigmaHat such that f(sigma) = V_{0, 0}^{Am}(sigma) - V_{mkt}^{Am}(K, T) = 0
 *   using Brent's or Newton-Raphson.
 */
public class CRR {

    private double S0;
    private double K;
    private double T;
    private double r;
    private double q;
    private int N;
    private int eta; // +1 for call, -1 for put

    public CRR() {
    }

    public CRR(double S0, double K, double T, double r, double q, int N, int eta) {
        this.S0 = S0;
        this.K = K;
        this.T = T;
        this.r = r;
        this.q = q;
        this.N = N;
        this.eta = eta;
    }

    /**
     * Prices an American option via CRR binomial tree: V_{0, 0}^{Am}(sigma).
     *
     * @param S0    Underlying price at t = 0
     * @param K     Strike price
     * @param T     Expiry in years
     * @param r     Risk-free rate
     * @param q     Continuous dividend yield
     * @param sigma Volatility
     * @param N     Number of steps
     * @param eta   Option type: +1 for call, -1 for put
     * @return American option value at root node V_{0, 0}^{Am}(sigma)
     */
    public static double price(double S0, double K, double T, double r, double q, double sigma, int N, int eta) {
        if (N <= 0) {
            throw new IllegalArgumentException("Number of steps N must be positive: " + N);
        }
        if (T <= 0) {
            throw new IllegalArgumentException("Expiry T must be positive: " + T);
        }

        double deltaT = T / N;
        double u = Math.exp(sigma * Math.sqrt(deltaT));
        double d = 1.0 / u; // or e^(-sigma * sqrt(deltaT)), u * d = 1
        double p = (Math.exp((r - q) * deltaT) - d) / (u - d);
        p = Math.max(0.0, Math.min(1.0, p));
        double discount = Math.exp(-r * deltaT);

        // Terminal payoff at step i = N:
        // S_{N, j} = S0 * u^j * d^(N - j) for j in (0, 1, ... N)
        // V_{N, j} = max(0, eta * (S_{N, j} - K))
        double[] V = new double[N + 1];
        for (int j = 0; j <= N; j++) {
            double Snj = S0 * Math.pow(u, j) * Math.pow(d, N - j);
            V[j] = Math.max(0.0, eta * (Snj - K));
        }

        // Backward induction from i = N - 1 down to 0:
        // V_{i, j}^{Am}(sigma) = max(eta * (S_{i, j} - K), e^(-r * deltaT) * (p * V_{i+1, j+1} + (1 - p) * V_{i+1, j}))
        for (int i = N - 1; i >= 0; i--) {
            for (int j = 0; j <= i; j++) {
                double Sij = S0 * Math.pow(u, j) * Math.pow(d, i - j);
                double continuation = discount * (p * V[j + 1] + (1.0 - p) * V[j]);
                double exercise = eta * (Sij - K);
                V[j] = Math.max(exercise, continuation);
            }
        }

        return V[0];
    }

    /**
     * Prices a European option via CRR binomial tree (early exercise turned off).
     *
     * @param S0    Underlying price at t = 0
     * @param K     Strike price
     * @param T     Expiry in years
     * @param r     Risk-free rate
     * @param q     Continuous dividend yield
     * @param sigma Volatility
     * @param N     Number of steps
     * @param eta   Option type: +1 for call, -1 for put
     * @return European option value at root node V_{0, 0}^{Euro}(sigma)
     */
    public static double europeanPrice(double S0, double K, double T, double r, double q, double sigma, int N, int eta) {
        if (N <= 0) {
            throw new IllegalArgumentException("Number of steps N must be positive: " + N);
        }
        if (T <= 0) {
            throw new IllegalArgumentException("Expiry T must be positive: " + T);
        }

        double deltaT = T / N;
        double u = Math.exp(sigma * Math.sqrt(deltaT));
        double d = 1.0 / u;
        double p = (Math.exp((r - q) * deltaT) - d) / (u - d);
        p = Math.max(0.0, Math.min(1.0, p));
        double discount = Math.exp(-r * deltaT);

        double[] V = new double[N + 1];
        for (int j = 0; j <= N; j++) {
            double Snj = S0 * Math.pow(u, j) * Math.pow(d, N - j);
            V[j] = Math.max(0.0, eta * (Snj - K));
        }

        // Backward induction with early exercise turned off
        for (int i = N - 1; i >= 0; i--) {
            for (int j = 0; j <= i; j++) {
                V[j] = discount * (p * V[j + 1] + (1.0 - p) * V[j]);
            }
        }

        return V[0];
    }

    /**
     * Alias matching notation in notes: V_{0, 0}^{Am}(sigma).
     */
    public static double v00Am(double S0, double K, double T, double r, double q, double sigma, int N, int eta) {
        return price(S0, K, T, r, q, sigma, N, eta);
    }

    /**
     * Alias matching notation in notes: V_{0, 0}^{Euro}(sigma).
     */
    public static double v00Euro(double S0, double K, double T, double r, double q, double sigma, int N, int eta) {
        return europeanPrice(S0, K, T, r, q, sigma, N, eta);
    }

    /**
     * Objective function f(sigma) = V_{0, 0}^{Am}(sigma) - V_{mkt}^{Am}(K, T) = 0.
     */
    public static double f(double S0, double K, double T, double r, double q, double sigma, int N, int eta, double vMkt) {
        return price(S0, K, T, r, q, sigma, N, eta) - vMkt;
    }

    /**
     * Checks if option is Out-Of-The-Money (OTM):
     * "Only needed/should be on OTM, ITM has bad spreads + premiums"
     */
    public static boolean isOtm(double S0, double K, int eta) {
        return eta == 1 ? S0 < K : S0 > K;
    }

    /**
     * Calibrates the true market-implied effective spot price from an ATM Call/Put pair
     * by applying Put-Call Parity to de-Americanized European prices (with q = 0):
     * S_Implied_True = C_atm - (P_atm - EEP_atm_put) + K_atm * e^(-r * T)
     *
     * Removes the ATM smile discontinuity caused by American put early exercise premium
     * and dividend yield mismatches.
     *
     * @param KAtm Strike closest to spot
     * @param cMid Call market mid price at KAtm
     * @param pMid Put market mid price at KAtm
     * @param T    Expiry in years
     * @param r    Risk-free rate
     * @param N    Number of tree steps (e.g. 100)
     * @return True market-implied effective spot price
     */
    public static double implyEffectiveSpot(double KAtm, double cMid, double pMid, double T, double r, int N) {
        double discount = Math.exp(-r * T);
        // 1. Initial raw Put-Call Parity: S_raw = (C - P) + K * e^(-rT)
        double sRaw = (cMid - pMid) + KAtm * discount;

        // 2. Compute early exercise premium of the ATM put on CRR tree with q = 0:
        double sigmaHatPut = findSigmaHat(sRaw, KAtm, T, r, 0.0, N, -1, pMid);
        double euroPutPrice = europeanPrice(sRaw, KAtm, T, r, 0.0, sigmaHatPut, N, -1);
        double eepPut = Math.max(0.0, pMid - euroPutPrice);

        // 3. True implied spot with put early exercise premium added back:
        return cMid - (pMid - eepPut) + KAtm * discount;
    }

    /**
     * Inverts the CRR tree to find the de-Americanized implied volatility sigmaHat
     * that matches market price V_{mkt}^{Am}(K, T), using Brent's method by default.
     */
    public static double findSigmaHat(double S0, double K, double T, double r, double q, int N, int eta, double vMkt) {
        return findSigmaHatBrent(S0, K, T, r, q, N, eta, vMkt);
    }

    /**
     * Inverts the CRR tree to find sigmaHat using Brent's method.
     */
    public static double findSigmaHatBrent(double S0, double K, double T, double r, double q, int N, int eta, double vMkt) {
        return findSigmaHatBrent(S0, K, T, r, q, N, eta, vMkt, 0.005, 5.0, 1e-7, 100);
    }

    /**
     * Inverts the CRR tree using Brent's method with custom bracket and convergence parameters.
     */
    public static double findSigmaHatBrent(double S0, double K, double T, double r, double q, int N, int eta, double vMkt,
                                          double a, double b, double tol, int maxIter) {
        double fa = f(S0, K, T, r, q, a, N, eta, vMkt);
        double fb = f(S0, K, T, r, q, b, N, eta, vMkt);

        if (Math.abs(fa) < tol) return a;
        if (Math.abs(fb) < tol) return b;

        if (fa * fb > 0.0) {
            // Try expanding search bracket
            if (fa > 0.0) {
                a = 0.001;
                fa = f(S0, K, T, r, q, a, N, eta, vMkt);
            } else {
                b = 10.0;
                fb = f(S0, K, T, r, q, b, N, eta, vMkt);
            }
            if (fa * fb > 0.0) {
                throw new IllegalArgumentException("Root not bracketed: f(" + a + ")=" + fa + ", f(" + b + ")=" + fb);
            }
        }

        double c = a;
        double fc = fa;
        double d = 0.0;
        double e = 0.0;

        for (int iter = 0; iter < maxIter; iter++) {
            if ((fb > 0.0 && fc > 0.0) || (fb < 0.0 && fc < 0.0)) {
                c = a;
                fc = fa;
                d = b - a;
                e = d;
            }
            if (Math.abs(fc) < Math.abs(fb)) {
                a = b;
                b = c;
                c = a;
                fa = fb;
                fb = fc;
                fc = fa;
            }

            double tol1 = 2.0 * 1e-15 * Math.abs(b) + 0.5 * tol;
            double xm = 0.5 * (c - b);

            if (Math.abs(xm) <= tol1 || Math.abs(fb) < tol) {
                return b;
            }

            if (Math.abs(e) >= tol1 && Math.abs(fa) > Math.abs(fb)) {
                double s = fb / fa;
                double p;
                double qVal;
                if (a == c) {
                    // Secant method
                    p = 2.0 * xm * s;
                    qVal = 1.0 - s;
                } else {
                    // Inverse quadratic interpolation
                    qVal = fa / fc;
                    double rVal = fb / fc;
                    p = s * (2.0 * xm * qVal * (qVal - rVal) - (b - a) * (rVal - 1.0));
                    qVal = (qVal - 1.0) * (rVal - 1.0) * (s - 1.0);
                }
                if (p > 0.0) {
                    qVal = -qVal;
                }
                p = Math.abs(p);
                if (2.0 * p < Math.min(3.0 * xm * qVal - Math.abs(tol1 * qVal), Math.abs(e * qVal))) {
                    e = d;
                    d = p / qVal;
                } else {
                    d = xm;
                    e = d;
                }
            } else {
                // Bisection step
                d = xm;
                e = d;
            }

            a = b;
            fa = fb;
            if (Math.abs(d) > tol1) {
                b += d;
            } else {
                b += Math.copySign(tol1, xm);
            }
            fb = f(S0, K, T, r, q, b, N, eta, vMkt);
        }

        return b;
    }

    /**
     * Inverts the CRR tree to find sigmaHat using Newton-Raphson.
     */
    public static double findSigmaHatNewtonRaphson(double S0, double K, double T, double r, double q, int N, int eta, double vMkt) {
        return findSigmaHatNewtonRaphson(S0, K, T, r, q, N, eta, vMkt, 0.3, 1e-7, 100);
    }

    /**
     * Inverts the CRR tree using Newton-Raphson with custom initial guess and convergence parameters.
     */
    public static double findSigmaHatNewtonRaphson(double S0, double K, double T, double r, double q, int N, int eta, double vMkt,
                                                  double initialGuess, double tol, int maxIter) {
        double sigma = initialGuess;
        double h = 1e-4;

        for (int iter = 0; iter < maxIter; iter++) {
            double fVal = f(S0, K, T, r, q, sigma, N, eta, vMkt);
            if (Math.abs(fVal) < tol) {
                return sigma;
            }

            double fUp = f(S0, K, T, r, q, sigma + h, N, eta, vMkt);
            double fDown = f(S0, K, T, r, q, Math.max(1e-6, sigma - h), N, eta, vMkt);
            double fPrime = (fUp - fDown) / (sigma + h - Math.max(1e-6, sigma - h));

            if (Math.abs(fPrime) < 1e-12) {
                break;
            }

            double delta = fVal / fPrime;
            double nextSigma = sigma - delta;

            if (nextSigma <= 0.0) {
                nextSigma = sigma * 0.5;
            }

            if (Math.abs(nextSigma - sigma) < tol) {
                return nextSigma;
            }
            sigma = nextSigma;
        }

        return sigma;
    }

    /**
     * Blends Call and Put implied volatility smoothly for ATM transition strikes.
     *
     * @param S0 Underlying spot
     * @param K Strike price
     * @param T Expiry in years
     * @param r Risk-free rate
     * @param q Continuous dividend yield
     * @param N Binomial steps
     * @param callMid Call mid price
     * @param putMid Put mid price
     * @param callWeight Weight assigned to Call IV (0.0 to 1.0)
     * @param putWeight Weight assigned to Put IV (0.0 to 1.0)
     * @return Blended implied volatility sigmaHat
     */
    public static double findBlendedSigmaHat(double S0, double K, double T, double r, double q, int N,
                                            double callMid, double putMid, double callWeight, double putWeight) {
        if (callWeight <= 0.0) {
            return findSigmaHat(S0, K, T, r, q, N, -1, putMid);
        }
        if (putWeight <= 0.0) {
            return findSigmaHat(S0, K, T, r, q, N, 1, callMid);
        }
        double sigCall = findSigmaHat(S0, K, T, r, q, N, 1, callMid);
        double sigPut = findSigmaHat(S0, K, T, r, q, N, -1, putMid);
        return callWeight * sigCall + putWeight * sigPut;
    }

    // Instance methods delegating to the mathematical functions:

    public double price(double sigma) {
        return price(this.S0, this.K, this.T, this.r, this.q, sigma, this.N, this.eta);
    }

    public double findSigmaHat(double vMkt) {
        return findSigmaHat(this.S0, this.K, this.T, this.r, this.q, this.N, this.eta, vMkt);
    }

    public double findSigmaHatBrent(double vMkt) {
        return findSigmaHatBrent(this.S0, this.K, this.T, this.r, this.q, this.N, this.eta, vMkt);
    }

    public double findSigmaHatNewtonRaphson(double vMkt) {
        return findSigmaHatNewtonRaphson(this.S0, this.K, this.T, this.r, this.q, this.N, this.eta, vMkt);
    }

    public double getS0() { return S0; }
    public void setS0(double S0) { this.S0 = S0; }

    public double getK() { return K; }
    public void setK(double K) { this.K = K; }

    public double getT() { return T; }
    public void setT(double T) { this.T = T; }

    public double getR() { return r; }
    public void setR(double r) { this.r = r; }

    public double getQ() { return q; }
    public void setQ(double q) { this.q = q; }

    public int getN() { return N; }
    public void setN(int N) { this.N = N; }

    public int getEta() { return eta; }
    public void setEta(int eta) { this.eta = eta; }
}