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
        if (dK <= 0.0) {
            throw new IllegalArgumentException("dK must be strictly positive: " + dK);
        }
        List<DensityPoint> slice = new ArrayList<>(targetStrikes.length);
        double expRT = Math.exp(r * T);
        double dK2 = dK * dK;
        double twoDk = 2.0 * dK;
        for (double K : targetStrikes) {
            double cMid = BS.europeanCallPrice(S0, K, T, r, q, volCurve);
            double cUp = BS.europeanCallPrice(S0, K + dK, T, r, q, volCurve);
            double cDown = BS.europeanCallPrice(S0, K - dK, T, r, q, volCurve);

            double d2C = (cUp - 2.0 * cMid + cDown) / dK2;
            double pdf = Math.max(0.0, expRT * d2C);
            double dCdK = (cUp - cDown) / twoDk;
            double cdfVal = Math.max(0.0, Math.min(1.0, 1.0 + expRT * dCdK));

            slice.add(new DensityPoint(K, cMid, d2C, pdf, cdfVal));
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

    public record ZeroDriftJump(
            double moveIfEvent,
            double moveIfNotEvent,
            double u,
            double d,
            boolean isEventBullish
    ) {
        public ZeroDriftJump(double u, double d) {
            this(u, d, u, d, true);
        }
    }

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
        DensityPoint p0 = slice.get(0);
        if (p0.strike() <= 0.0) {
            throw new IllegalArgumentException("Strikes must be strictly positive");
        }
        double x0 = Math.log(p0.strike() / forward);
        double y0 = x0 * x0 * p0.density();
        double sum = 0.0;

        for (int i = 0; i < slice.size() - 1; i++) {
            DensityPoint p1 = slice.get(i + 1);
            if (p1.strike() <= 0.0) {
                throw new IllegalArgumentException("Strikes must be strictly positive");
            }
            double dK = p1.strike() - p0.strike();
            double x1 = Math.log(p1.strike() / forward);
            double y1 = x1 * x1 * p1.density();
            sum += 0.5 * (y0 + y1) * dK;
            p0 = p1;
            x0 = x1;
            y0 = y1;
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
        return impliedPureEventMove(vT1, vT2, v1Day, windowDays, 0.0);
    }

    /**
     * Implied pure event move \sigma_{event} stripping out both baseline trading-day diffusion
     * (windowDays * V_1) and scheduled non-FOMC macro cluster variance (extraMacroDays * V_1)
     * such as NFP, CPI, and Quadruple Witching when the [T1, T2] straddle spans multiple weeks.
     */
    public static double impliedPureEventMove(double vT1, double vT2, double v1Day, double windowDays, double extraMacroDays) {
        if (windowDays < 0.0) {
            throw new IllegalArgumentException("windowDays must be non-negative");
        }
        double effectiveDays = windowDays + Math.max(0.0, extraMacroDays);
        double diff = vT2 - vT1 - effectiveDays * v1Day;
        // If stripping estimated macro cluster variance extinguishes the event jump,
        // but raw trading-day window diffusion is positive, fall back to raw window diffusion.
        if (diff <= 0.0 && extraMacroDays > 0.0) {
            double rawDiff = vT2 - vT1 - windowDays * v1Day;
            if (rawDiff > 0.0) {
                diff = rawDiff;
            }
        }
        return Math.sqrt(Math.max(0.0, diff));
    }

    public static double impliedPureEventMove(double vT1, double vT2, double v1Day) {
        return impliedPureEventMove(vT1, vT2, v1Day, 1.0, 0.0);
    }

    /**
     * Counts trading days (Monday through Friday, excluding major US market holidays)
     * in the half-open interval (d1, d2].
     */
    public static int countTradingDays(java.time.LocalDate d1, java.time.LocalDate d2) {
        if (d1 == null || d2 == null) {
            return 1;
        }
        if (d1.isAfter(d2)) {
            java.time.LocalDate tmp = d1;
            d1 = d2;
            d2 = tmp;
        }
        int count = 0;
        java.time.LocalDate cur = d1.plusDays(1);
        while (!cur.isAfter(d2)) {
            java.time.DayOfWeek dow = cur.getDayOfWeek();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY && !isUsMarketHoliday(cur)) {
                count++;
            }
            cur = cur.plusDays(1);
        }
        return Math.max(1, count);
    }

    private static boolean isUsMarketHoliday(java.time.LocalDate d) {
        int m = d.getMonthValue();
        int day = d.getDayOfMonth();
        java.time.DayOfWeek dow = d.getDayOfWeek();
        // New Year's Day (Jan 1 or observed)
        if (m == 1 && (day == 1 || (day == 2 && dow == java.time.DayOfWeek.MONDAY))) return true;
        // Independence Day (Jul 4 or observed)
        if (m == 7 && (day == 4 || (day == 3 && dow == java.time.DayOfWeek.FRIDAY) || (day == 5 && dow == java.time.DayOfWeek.MONDAY))) return true;
        // Thanksgiving (4th Thursday of November)
        if (m == 11 && dow == java.time.DayOfWeek.THURSDAY && day >= 22 && day <= 28) return true;
        // Christmas Day (Dec 25 or observed)
        if (m == 12 && (day == 25 || (day == 24 && dow == java.time.DayOfWeek.FRIDAY) || (day == 26 && dow == java.time.DayOfWeek.MONDAY))) return true;
        return false;
    }

    /**
     * Checks if a ticker is a fixed-income or Treasury instrument (not subject to equity Quadruple Witching).
     */
    public static boolean isFixedIncomeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) return false;
        String t = ticker.trim().toUpperCase();
        return t.equals("TLT") || t.equals("IEF") || t.equals("SHY") || t.equals("IEI")
                || t.equals("TLH") || t.equals("BIL") || t.equals("BND") || t.equals("AGG")
                || t.equals("HYG") || t.equals("JNK") || t.equals("LQD") || t.equals("TIP")
                || t.startsWith("ZQ") || t.startsWith("ZB") || t.startsWith("ZN") || t.startsWith("ZT");
    }

    /**
     * Estimates additional baseline variance day-equivalents inside (t1Date, t2Date]
     * caused by non-FOMC macro releases (NFP, CPI) and Quarterly OpEx / Quadruple Witching
     * when the option straddle window spans more than 3 trading days.
     */
    public static double estimateMacroClusterExtraDays(java.time.LocalDate t1Date, java.time.LocalDate t2Date) {
        return estimateMacroClusterExtraDays(t1Date, t2Date, null);
    }

    public static double estimateMacroClusterExtraDays(java.time.LocalDate t1Date, java.time.LocalDate t2Date, String ticker) {
        if (t1Date == null || t2Date == null || !t2Date.isAfter(t1Date)) {
            return 0.0;
        }
        int windowTradingDays = countTradingDays(t1Date, t2Date);
        if (windowTradingDays <= 3) {
            return 0.0; // Tight 1-to-3 day straddle around FOMC isolates FOMC cleanly
        }
        double extraDays = 0.0;
        boolean hasCpiWindow = false;
        boolean hasNfp = false;
        boolean hasQuadWitching = false;
        boolean isBond = isFixedIncomeTicker(ticker);

        java.time.LocalDate cur = t1Date.plusDays(1);
        while (!cur.isAfter(t2Date)) {
            java.time.DayOfWeek dow = cur.getDayOfWeek();
            int dom = cur.getDayOfMonth();
            int month = cur.getMonthValue();
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                // 1. NFP: First Friday of the month (or 2nd Friday if 1st is Jan 1 / holiday)
                if (dow == java.time.DayOfWeek.FRIDAY && dom <= 7) {
                    hasNfp = true;
                }
                // 2. CPI / PPI: Mid-month inflation release window (10th-14th on Tue/Wed/Thu)
                if (dom >= 10 && dom <= 14 && (dow == java.time.DayOfWeek.TUESDAY
                        || dow == java.time.DayOfWeek.WEDNESDAY || dow == java.time.DayOfWeek.THURSDAY)) {
                    hasCpiWindow = true;
                }
                // 3. Quadruple Witching / S&P 500 Quarterly Rebalancing (3rd Friday of Mar, Jun, Sep, Dec)
                // Applies strictly to equities/indices (SPY); fixed income / Treasury ETFs do not experience Quad Witching.
                if (!isBond && (month == 3 || month == 6 || month == 9 || month == 12)
                        && dow == java.time.DayOfWeek.FRIDAY && dom >= 15 && dom <= 21) {
                    hasQuadWitching = true;
                }
            }
            cur = cur.plusDays(1);
        }
        if (hasNfp) extraDays += 0.75;
        if (hasCpiWindow) extraDays += 0.85;
        if (hasQuadWitching) extraDays += 0.85;
        return extraDays;
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

        // Hoist allocation outside the 200-step EM loop to eliminate heap churn
        double[] gammaUp = new double[n];

        // ponytail: 200-step EM loop is O(N) per step; sufficient for 1D grids <= 4096 points.
        for (int iter = 0; iter < 200; iter++) {
            double sumGammaUp = 0.0;
            double sumGammaDown = 0.0;
            double newMuUp = 0.0;
            double newMuDown = 0.0;

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
     * Calculates the 3rd centralized moment (skewness numerator / standardized skewness)
     * of log-returns from a Breeden-Litzenberger density slice:
     *   Skew = \frac{1}{\sigma^3} \int (x - \mu)^3 f_X(x) dx
     */
    public static double calculateLogSkewness(List<DensityPoint> slice, double forward) {
        if (slice == null || slice.size() < 2 || forward <= 0.0) {
            throw new IllegalArgumentException("Valid density slice and forward required");
        }
        double totalProb = 0.0;
        double mean = 0.0;
        int n = slice.size();
        double[] x = new double[n];
        double[] pdf = new double[n];
        for (int i = 0; i < n; i++) {
            DensityPoint dp = slice.get(i);
            x[i] = Math.log(dp.strike() / forward);
            pdf[i] = dp.density();
        }
        for (int i = 0; i < n - 1; i++) {
            double dK = slice.get(i + 1).strike() - slice.get(i).strike();
            double pMid = 0.5 * (pdf[i] + pdf[i + 1]);
            double xMid = 0.5 * (x[i] + x[i + 1]);
            totalProb += pMid * dK;
            mean += xMid * pMid * dK;
        }
        if (totalProb > 0) mean /= totalProb;

        double var = 0.0;
        double m3 = 0.0;
        for (int i = 0; i < n - 1; i++) {
            double dK = slice.get(i + 1).strike() - slice.get(i).strike();
            double pMid = 0.5 * (pdf[i] + pdf[i + 1]);
            double xMid = 0.5 * (x[i] + x[i + 1]);
            double diff = xMid - mean;
            var += diff * diff * pMid * dK;
            m3 += diff * diff * diff * pMid * dK;
        }
        if (totalProb > 0) {
            var /= totalProb;
            m3 /= totalProb;
        }
        double std = Math.sqrt(Math.max(1e-10, var));
        return m3 / (std * std * std);
    }

    /**
     * Solves the zero-drift jump outcomes under the zero-drift forward martingale condition:
     *   p * moveIfEvent + (1 - p) * moveIfNotEvent = 0,   Var(J) = \sigma_{event}^2
     *
     * @param sigmaEvent     Cleaned pure event volatility
     * @param pEvent         Probability of the specified event happening
     * @param isEventBullish True if event occurrence is bullish for underlying (e.g. rate cut, positive earnings);
     *                       False if event occurrence is bearish (e.g. rate hike).
     */
    public static ZeroDriftJump solveZeroDriftJump(double sigmaEvent, double pEvent, boolean isEventBullish) {
        return solveZeroDriftJump(sigmaEvent, pEvent, Double.NaN, isEventBullish);
    }

    /**
     * Solves the zero-drift 2-state jump outcomes under the exact zero-drift forward martingale
     * and variance-matching conditions at the actual event probability pEvent:
     *   pEvent * moveIfEvent + (1 - pEvent) * moveIfNotEvent = 0,   Var(J) = \sigma_{event}^2
     *
     * Note: In a 2-state Bernoulli tree with fixed probability pEvent and variance \sigma_{event}^2,
     * the two jump states are uniquely locked by E[J] = 0 and Var(J) = \sigma_{event}^2.
     * Surface skewness (blSkewness) requires a 3rd degree of freedom and is therefore solved in
     * {@link #solveThreeStateJump(double, double, double, boolean)}, whose conditional expectation
     * over the event sub-branches (Hawkish + Dovish) reduces to this exact 2-state moveIfEvent.
     */
    public static ZeroDriftJump solveZeroDriftJump(double sigmaEvent, double pEvent, double blSkewness, boolean isEventBullish) {
        double safeSigma = (Double.isNaN(sigmaEvent) || sigmaEvent < 0.0) ? 0.0 : sigmaEvent;
        double safeP = Double.isNaN(pEvent) ? 0.50 : Math.max(1e-4, Math.min(1.0 - 1e-4, pEvent));

        double branchSmall = safeSigma * Math.sqrt((1.0 - safeP) / safeP);
        double branchSurprise = safeSigma * Math.sqrt(safeP / (1.0 - safeP));

        double moveIfEvent;
        double moveIfNotEvent;
        double u;
        double d;

        if (isEventBullish) {
            moveIfEvent = +branchSmall;
            moveIfNotEvent = -branchSurprise;
            u = moveIfEvent;
            d = moveIfNotEvent;
        } else {
            moveIfEvent = -branchSmall;
            moveIfNotEvent = +branchSurprise;
            u = moveIfNotEvent;
            d = moveIfEvent;
        }
        return new ZeroDriftJump(moveIfEvent, moveIfNotEvent, u, d, isEventBullish);
    }

    /**
     * 3-State FOMC Jump Decomposition incorporating Summary of Economic Projections (Dot Plot),
     * Powell Press Conference dispersion, and Breeden-Litzenberger surface skewness:
     *   - State 1 (pHawkish): Base Policy Decision + Hawkish Dots / Press Conference
     *   - State 2 (pDovish) : Base Policy Decision + Dovish Dots / Press Conference
     *   - State 3 (pSurprise): Surprise Policy Rate Decision (1 - p)
     */
    public record ThreeStateFomcJump(
            double pHawkish,
            double moveHawkish,
            double pDovish,
            double moveDovish,
            double pSurprise,
            double moveSurprise,
            double dotPlotVarianceShare,
            double realizedSkewness
    ) {}

    /**
     * Solves a 3-state FOMC jump distribution matching:
     *   1. Zero-drift martingale: p_H * m_H + p_D * m_D + p_S * m_S = 0
     *   2. Total pure event variance: p_H * m_H^2 + p_D * m_D^2 + p_S * m_S^2 = \sigma_{event}^2
     *   3. Breeden-Litzenberger 3rd moment (skewness S): calibrates Dot-Plot variance share \theta
     *      and intra-decision Hawkish/Dovish asymmetry to match empirical option surface skew.
     */
    public static ThreeStateFomcJump solveThreeStateJump(
            double sigmaEvent, double pEvent, double blSkewness, boolean isEventBullish) {
        double safeSigma = (Double.isNaN(sigmaEvent) || sigmaEvent < 0.0) ? 0.0 : sigmaEvent;
        double p = Double.isNaN(pEvent) ? 0.50 : Math.max(0.05, Math.min(0.95, pEvent));
        double pSurprise = 1.0 - p;
        double targetSkew = (Double.isNaN(blSkewness) || !Double.isFinite(blSkewness)) ? 0.0
                : Math.max(-2.5, Math.min(2.5, blSkewness));

        if (safeSigma <= 1e-8) {
            return new ThreeStateFomcJump(0.5 * p, 0.0, 0.5 * p, 0.0, pSurprise, 0.0, 0.0, 0.0);
        }

        // Fine grid-search with smooth interior regularization over Dot-Plot variance share theta in [0.20, 0.78]
        // and intra-decision split alpha in [0.18, 0.82] (avoiding boundary clipping at 0.15 * p)
        // where pHawkish = alpha * p, pDovish = (1 - alpha) * p:
        // Base policy mean move: m_base = sign * sigma * sqrt((1 - theta) * (1 - p) / p)
        // Surprise policy move:  m_surp = - (p / (1 - p)) * m_base
        // Dot-plot dispersion around m_base with zero conditional drift:
        //   alpha * d_H + (1 - alpha) * d_D = 0,  p * (alpha * d_H^2 + (1 - alpha) * d_D^2) = theta * sigma^2
        double bestTheta = 0.50;
        double bestAlpha = 0.50;
        double bestErr = Double.POSITIVE_INFINITY;
        double signBase = isEventBullish ? 1.0 : -1.0;
        double safeSigma3 = safeSigma * safeSigma * safeSigma;
        double priorTheta = Math.min(0.72, Math.max(0.28, p * 0.75));

        for (int ti = 20; ti <= 78; ti++) {
            double theta = ti / 100.0;
            double mBase = signBase * safeSigma * Math.sqrt((1.0 - theta) * pSurprise / p);
            double mSurp = -(p / pSurprise) * mBase;
            double dotStd = safeSigma * Math.sqrt(theta / p);
            double surpTerm = pSurprise * mSurp * mSurp * mSurp;
            double thetaDiff = theta - priorTheta;
            double thetaPenalty = 0.25 * thetaDiff * thetaDiff;

            for (int ai = 18; ai <= 82; ai++) {
                double alpha = ai / 100.0; // probability share of Hawkish Dots within base decision
                // Hawkish dots is always more negative for asset price than Dovish dots:
                double dHawk = -dotStd * Math.sqrt((1.0 - alpha) / alpha);
                double dDov = +dotStd * Math.sqrt(alpha / (1.0 - alpha));
                double mHawk = mBase + dHawk;
                double mDov = mBase + dDov;

                double pH = alpha * p;
                double pD = (1.0 - alpha) * p;
                double m3 = pH * mHawk * mHawk * mHawk + pD * mDov * mDov * mDov + surpTerm;
                double skew = m3 / safeSigma3;

                double skewDiff = skew - targetSkew;
                double alphaDiff = alpha - 0.50;
                // Smooth quadratic objective prevents hard-boundary clamping when |targetSkew| is large
                double err = skewDiff * skewDiff + thetaPenalty + 0.35 * alphaDiff * alphaDiff;
                if (err < bestErr) {
                    bestErr = err;
                    bestTheta = theta;
                    bestAlpha = alpha;
                }
            }
        }

        double mBase = signBase * safeSigma * Math.sqrt((1.0 - bestTheta) * pSurprise / p);
        double mSurp = -(p / pSurprise) * mBase;
        double dotStd = safeSigma * Math.sqrt(bestTheta / p);
        double dHawk = -dotStd * Math.sqrt((1.0 - bestAlpha) / bestAlpha);
        double dDov = +dotStd * Math.sqrt(bestAlpha / (1.0 - bestAlpha));
        double mHawk = mBase + dHawk;
        double mDov = mBase + dDov;
        double pH = bestAlpha * p;
        double pD = (1.0 - bestAlpha) * p;
        double m3 = pH * mHawk * mHawk * mHawk + pD * mDov * mDov * mDov + pSurprise * mSurp * mSurp * mSurp;
        double realizedSkew = m3 / (safeSigma * safeSigma * safeSigma);

        return new ThreeStateFomcJump(pH, mHawk, pD, mDov, pSurprise, mSurp, bestTheta, realizedSkew);
    }

    /**
     * Backward-compatible overload defaulting to isEventBullish = true (canonical notes notation).
     */
    public static ZeroDriftJump solveZeroDriftJump(double sigmaEvent, double p) {
        return solveZeroDriftJump(sigmaEvent, p, Double.NaN, true);
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

    // --- Cross-Asset FOMC Triangulation & Mispricing Models ---

    public static final double TLT_MODIFIED_DURATION = 16.5;
    public static final double IEF_MODIFIED_DURATION = 7.5;
    public static final double BASELINE_TLT_SPY_RATIO = 1.25;

    public record PolymarketTailBrackets(
            double pCut50,
            double pCut25,
            double pHike25,
            double pHike50
    ) {
        public static PolymarketTailBrackets empty() {
            return new PolymarketTailBrackets(0.0, 0.0, 0.0, 0.0);
        }

        /**
         * Returns the implied "No change / Hold" (0 bps) bracket probability:
         *   p_{no\_change} = \max(0, 1 - (p_{cut50} + p_{cut25} + p_{hike25} + p_{hike50}))
         */
        public double pNoChange() {
            double c50 = Double.isFinite(pCut50) ? Math.max(0.0, pCut50) : 0.0;
            double c25 = Double.isFinite(pCut25) ? Math.max(0.0, pCut25) : 0.0;
            double h25 = Double.isFinite(pHike25) ? Math.max(0.0, pHike25) : 0.0;
            double h50 = Double.isFinite(pHike50) ? Math.max(0.0, pHike50) : 0.0;
            return Math.max(0.0, 1.0 - (c50 + c25 + h25 + h50));
        }

        public double expectedRateChangeBps() {
            double c50 = Double.isFinite(pCut50) ? Math.max(0.0, pCut50) : 0.0;
            double c25 = Double.isFinite(pCut25) ? Math.max(0.0, pCut25) : 0.0;
            double h25 = Double.isFinite(pHike25) ? Math.max(0.0, pHike25) : 0.0;
            double h50 = Double.isFinite(pHike50) ? Math.max(0.0, pHike50) : 0.0;
            return -50.0 * c50 - 25.0 * c25 + 25.0 * h25 + 50.0 * h50;
        }
    }

    public record TailAdjustedCmeResult(
            double deltaRateBps,
            double pBondsBinary,
            double pHike25Zq,
            double pBondsTailAdjusted,
            double tailCorrectionBps,
            boolean isHikeImplied,
            double yearEndTurnBps,
            double deltaRateTurnAdjustedBps,
            double pBondsTurnAndTailAdjusted
    ) {
        public TailAdjustedCmeResult(
                double deltaRateBps,
                double pBondsBinary,
                double pHike25Zq,
                double pBondsTailAdjusted,
                double tailCorrectionBps,
                boolean isHikeImplied) {
            this(deltaRateBps, pBondsBinary, pHike25Zq, pBondsTailAdjusted, tailCorrectionBps, isHikeImplied, 0.0, deltaRateBps, pBondsTailAdjusted);
        }
    }

    /**
     * Solves for the tail-adjusted CME Fed Funds futures probability by accounting for
     * tail brackets (50+ bps cuts/hikes and 25 bps cuts):
     * Delta_r_ZQ (in bps) = -50 * p_cut50 - 25 * p_cut25 + 25 * p_hike25_ZQ + 50 * p_hike50
     * => p_hike25_ZQ = (Delta_r_ZQ - (-50*p_cut50 - 25*p_cut25 + 50*p_hike50)) / 25
     * => p_Bonds_TailAdjusted = p_hike25_ZQ + p_hike50
     */
    public static TailAdjustedCmeResult solveTailAdjustedCme(
            double deltaRatePct,
            PolymarketTailBrackets tails) {
        return solveTailAdjustedCme(deltaRatePct, tails, 0.0);
    }

    /**
     * Solves for tail-adjusted CME Fed Funds futures probability, optionally accounting for
     * year-end balance-sheet turn funding pressure in December contracts.
     */
    public static TailAdjustedCmeResult solveTailAdjustedCme(
            double deltaRatePct,
            PolymarketTailBrackets tails,
            double yearEndTurnBps) {

        double safeDeltaPct = Double.isFinite(deltaRatePct) ? deltaRatePct : 0.0;
        double deltaBps = safeDeltaPct * 100.0;
        double pBinary = Math.min(1.0, Math.max(0.0, Math.abs(deltaBps) / 25.0));

        double turnBps = (Double.isFinite(yearEndTurnBps) && yearEndTurnBps > 0.0) ? yearEndTurnBps : 0.0;
        double adjustedDeltaBps = deltaBps >= 0.0 ? Math.max(0.0, deltaBps - turnBps) : Math.min(0.0, deltaBps + turnBps);

        if (tails == null) {
            double pTurnBinary = (turnBps <= 0.0) ? pBinary : Math.min(1.0, Math.max(0.0, Math.abs(adjustedDeltaBps) / 25.0));
            return new TailAdjustedCmeResult(
                    deltaBps, pBinary, pBinary, pBinary, 0.0, deltaBps >= 0.0,
                    turnBps, adjustedDeltaBps, pTurnBinary
            );
        }

        double safeCut50 = Double.isFinite(tails.pCut50()) ? Math.max(0.0, Math.min(1.0, tails.pCut50())) : 0.0;
        double safeCut25 = Double.isFinite(tails.pCut25()) ? Math.max(0.0, Math.min(1.0, tails.pCut25())) : 0.0;
        double safeHike50 = Double.isFinite(tails.pHike50()) ? Math.max(0.0, Math.min(1.0, tails.pHike50())) : 0.0;

        // Tails contribution outside of 25 bps hike:
        // tailContribution = -50 * p_cut50 - 25 * p_cut25 + 50 * p_hike50
        double tailContribution = -50.0 * safeCut50 - 25.0 * safeCut25 + 50.0 * safeHike50;
        double pHike25Zq = (deltaBps - tailContribution) / 25.0;
        pHike25Zq = Math.max(0.0, Math.min(1.0, pHike25Zq));

        double pBondsTailAdjusted = Math.max(0.0, Math.min(1.0, pHike25Zq + safeHike50));
        double tailCorrectionBps = deltaBps - (25.0 * pBinary);

        // Turn-adjusted calculation (short-circuit redundant math if turnBps == 0):
        double pHike25ZqTurn;
        double pBondsTurnAndTail;
        if (turnBps <= 0.0) {
            pHike25ZqTurn = pHike25Zq;
            pBondsTurnAndTail = pBondsTailAdjusted;
        } else {
            pHike25ZqTurn = (adjustedDeltaBps - tailContribution) / 25.0;
            pHike25ZqTurn = Math.max(0.0, Math.min(1.0, pHike25ZqTurn));
            pBondsTurnAndTail = Math.max(0.0, Math.min(1.0, pHike25ZqTurn + safeHike50));
        }

        return new TailAdjustedCmeResult(
                deltaBps,
                pBinary,
                pHike25Zq,
                pBondsTailAdjusted,
                tailCorrectionBps,
                deltaBps >= 0.0,
                turnBps,
                adjustedDeltaBps,
                pBondsTurnAndTail
        );
    }

    public record ResidualSensitivity(
            double sigmaEvent,
            double totalVarianceT2,
            double eventVarianceShare,
            double sensitivityFactor,
            double moveSensitivityToVolPoint,
            boolean isFragile
    ) {}

    /**
     * Evaluates whether an extracted event move is an ill-conditioned, fragile residual of two nearly
     * equal total variances (e.g. sigma_event^2 < 3% of V_T2) where a 0.1 vol-point error swings the move by multiples.
     */
    public static ResidualSensitivity calculateResidualSensitivity(
            double vT1, double vT2, double sigmaEvent, double t2Years, double atmVolT2) {
        double safeSigma = (!Double.isFinite(sigmaEvent) || sigmaEvent < 0.0) ? 0.0 : sigmaEvent;
        double safeVT2 = (!Double.isFinite(vT2) || vT2 < 1e-8) ? 1e-8 : vT2;
        double eventVar = safeSigma * safeSigma;
        double varShare = eventVar / safeVT2;

        double safeT2 = (!Double.isFinite(t2Years) || t2Years < 1.0 / 252.0) ? (1.0 / 252.0) : t2Years;
        double safeAtmVol = (Double.isFinite(atmVolT2) && atmVolT2 > 1e-4)
                ? atmVolT2
                : Math.sqrt(safeVT2 / safeT2);
        // d(sigma_event) / d(sigma_quote) = (sigma_quote * T2) / sigma_event
        double dSigmaEvent_dSigmaQuote = (safeAtmVol * safeT2) / Math.max(1e-4, safeSigma);
        double moveDeltaFor10BpsQuote = dSigmaEvent_dSigmaQuote * 0.001; // 0.1 vol point error = 0.001

        boolean fragile = (!Double.isFinite(sigmaEvent) || safeSigma < 0.0035 || varShare < 0.03
                || moveDeltaFor10BpsQuote > 0.50 * safeSigma);

        return new ResidualSensitivity(safeSigma, safeVT2, varShare, dSigmaEvent_dSigmaQuote, moveDeltaFor10BpsQuote, fragile);
    }

    /**
     * Regularizes extracted event moves using cross-asset empirical beta bounds to prevent
     * term-structure slope and holiday illiquidity (e.g. HYG Thanksgiving week) from generating
     * implausible event moves (e.g. HYG 8x SPY).
     */
    public static double applyCrossAssetSanityBounds(String ticker, double sigmaEvent, double sigmaSpy, double sigmaTlt) {
        if (ticker == null || !Double.isFinite(sigmaEvent) || sigmaEvent <= 1e-6) {
            return sigmaEvent;
        }
        if (ticker.equalsIgnoreCase("HYG") || ticker.equalsIgnoreCase("JNK")) {
            // High Yield corporate bonds: beta to SPY ~0.35-0.45, duration weight to TLT ~3.8 / 16.5 = ~0.23
            boolean hasSpy = Double.isFinite(sigmaSpy) && sigmaSpy > 1e-4;
            boolean hasTlt = Double.isFinite(sigmaTlt) && sigmaTlt > 1e-4;
            if (hasSpy && hasTlt) {
                double cap = 0.50 * sigmaSpy + 0.30 * sigmaTlt;
                double maxAllowed = Math.max(cap, Math.min(sigmaSpy, sigmaTlt) * 0.85);
                if (sigmaEvent > maxAllowed) {
                    return maxAllowed;
                }
            } else if (hasSpy && sigmaEvent > 0.70 * sigmaSpy) {
                return 0.70 * sigmaSpy;
            } else if (hasTlt && sigmaEvent > 0.85 * sigmaTlt) {
                return 0.85 * sigmaTlt;
            }
        }
        return sigmaEvent;
    }

    public record CrossAssetTriangulation(
            double pPolymarket,
            double pRates,
            double pOptions,
            double pTriangulated,
            double sigmaTlt,
            double sigmaSpy,
            double tltImpliedYieldShockBps,
            double tltYieldMoveIfEventBps,
            double tltYieldMoveIfNotEventBps,
            double spyMoveIfEventPct,
            double spyMoveIfNotEventPct,
            double tltToSpyVolRatio,
            double spyMovePer10BpsYieldPct,
            double triangulatedSpyFairSigma,
            double spyVolEdgePct,
            String regimeVerdict,
            String detailedExplanation
    ) {}

    /**
     * Triangulates across 3 venues:
     * 1. Venue 1 (Prediction Market - Polymarket/Kalshi)
     * 2. Venue 2 (Short-Term Rates - CME Fed Funds ZQ)
     * 3. Venue 3 (TLT & SPY Breeden-Litzenberger Surfaces with TLT Duration Conversion)
     */
    public static CrossAssetTriangulation triangulateFomcVenues(
            double pPolymarket,
            double pRates,
            double sigmaTlt,
            double sigmaSpy,
            boolean isEventBullish) {
        return triangulateFomcVenues(
                pPolymarket, pRates, sigmaTlt, sigmaSpy,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, isEventBullish
        );
    }

    /**
     * Full cross-asset FOMC triangulation incorporating empirical pre-event diffusion variance
     * ratio (\sqrt{V_{1,TLT} / V_{1,SPY}}) instead of a static 1.25x constant, and coupling
     * Breeden-Litzenberger log-skewness into the conditional asset jump moves.
     */
    public static CrossAssetTriangulation triangulateFomcVenues(
            double pPolymarket,
            double pRates,
            double sigmaTlt,
            double sigmaSpy,
            double v1Tlt,
            double v1Spy,
            double skewTlt,
            double skewSpy,
            boolean isEventBullish) {

        double safeSigmaTlt = (!Double.isFinite(sigmaTlt) || sigmaTlt < 0.0) ? 0.0 : sigmaTlt;
        double safeSigmaSpy = (!Double.isFinite(sigmaSpy) || sigmaSpy < 0.0) ? 0.0 : sigmaSpy;

        // 1. Synthesize Consensus Event Probability across 3 Venues:
        // Venue 1: Polymarket retail prediction crowd
        // Venue 2: CME 30-Day Fed Funds futures (ZQ) institutional rate
        // Venue 3: TLT/SPY option surface risk-neutral jump skewness
        double pOptions = Double.NaN;
        if (Double.isFinite(skewTlt) && Math.abs(skewTlt) > 1e-4) {
            // Closed-form option-implied jump probability from standardized risk-neutral skewness:
            // S = (1 - 2p) / sqrt(p(1 - p)) => p = 0.5 * (1 + |S| / sqrt(4 + S^2))
            double s = Math.max(-2.5, Math.min(2.5, skewTlt));
            pOptions = 0.5 * (1.0 + Math.abs(s) / Math.sqrt(4.0 + s * s));
        }

        double pTri;
        boolean hasRates = Double.isFinite(pRates);
        boolean hasPoly = Double.isFinite(pPolymarket);
        boolean hasOpt = Double.isFinite(pOptions);

        if (hasRates && hasPoly && hasOpt) {
            pTri = 0.55 * pRates + 0.30 * pPolymarket + 0.15 * pOptions;
        } else if (hasRates && hasPoly) {
            pTri = 0.65 * pRates + 0.35 * pPolymarket; // institutional bond desks lead rate cuts/hikes
        } else if (hasRates) {
            pTri = pRates;
        } else if (hasPoly) {
            pTri = pPolymarket;
        } else if (hasOpt) {
            pTri = pOptions;
        } else {
            pTri = 0.50;
        }
        pTri = Math.max(1e-4, Math.min(1.0 - 1e-4, pTri));

        // 2. TLT Duration to 20Y Yield Shock (bps)
        // Delta_y_bps = -(Delta_P / P) / D_mod * 10,000
        double tltYieldShockBps = (safeSigmaTlt / TLT_MODIFIED_DURATION) * 10000.0;
        ZeroDriftJump tltJump = solveZeroDriftJump(safeSigmaTlt, pTri, skewTlt, isEventBullish);
        // Note: Bond price drops when yields rise (Hike/Event is bearish bonds -> positive yield shock)
        double tltYieldMoveIfEventBps = -(tltJump.moveIfEvent() / TLT_MODIFIED_DURATION) * 10000.0;
        double tltYieldMoveIfNotEventBps = -(tltJump.moveIfNotEvent() / TLT_MODIFIED_DURATION) * 10000.0;

        // 3. SPY Jump Solution (coupled to SPY B-L skewness)
        ZeroDriftJump spyJump = solveZeroDriftJump(safeSigmaSpy, pTri, skewSpy, isEventBullish);

        // 4. Cross-Asset Vol Ratio (MOVE vs VIX event proxy)
        double ratio = safeSigmaSpy > 1e-6 ? (safeSigmaTlt / safeSigmaSpy) : Double.NaN;

        // Dynamic baseline TLT/SPY ratio from empirical background diffusion sqrt(V1_TLT / V1_SPY),
        // falling back to BASELINE_TLT_SPY_RATIO (1.25) only when V1 is not provided.
        double baselineRatio = BASELINE_TLT_SPY_RATIO;
        if (Double.isFinite(v1Tlt) && Double.isFinite(v1Spy) && v1Tlt > 1e-9 && v1Spy > 1e-9) {
            baselineRatio = Math.max(0.65, Math.min(2.00, Math.sqrt(v1Tlt / v1Spy)));
        }

        double fairSpySigma = safeSigmaTlt / baselineRatio;
        double rawSpyVolEdge = fairSpySigma - safeSigmaSpy;
        double spyMovePer10Bps = (tltYieldShockBps > 1e-4) ? (safeSigmaSpy / (tltYieldShockBps / 10.0)) : Double.NaN;

        String verdict;
        String explanation;
        double shock = tltYieldShockBps;
        String shockDesc = shock < 5.0 ? "mild / light" : shock < 10.0 ? "typical" : "heavy";

        // Gating when SPY event variance is a fragile residual in the noise regime:
        boolean spyIsFragile = safeSigmaSpy > 1e-6 && (safeSigmaSpy < 0.0035 || (safeSigmaSpy < 0.006 && ratio > 1.8 * baselineRatio));
        double spyVolEdge = spyIsFragile ? Double.NaN : rawSpyVolEdge;

        if (Double.isNaN(ratio) || safeSigmaSpy <= 1e-6 || safeSigmaTlt <= 1e-6) {
            verdict = "INSUFFICIENT DATA (UNABLE TO TRIANGULATE)";
            explanation = "Insufficient or zero volatility estimates for TLT or SPY to determine cross-asset pricing regime.";
        } else if (spyIsFragile) {
            verdict = "INDETERMINATE (SPY RESIDUAL HIGH NOISE)";
            explanation = String.format("SPY pure event move (+/-%.2f%%) is an ill-conditioned residual (<3%% of total variance, where a 0.1 vol-point error swings the move by multiples). Edge is suppressed as measurement noise; do not trade unhedged.",
                    safeSigmaSpy * 100.0);
        } else if (ratio > 1.45 * baselineRatio) {
            verdict = "EQUITY VOL CHEAP vs RATES (MOVE/VIX DIVERGENCE)";
            explanation = String.format("TLT B-L prices a %s +/-%.1f bps 20Y yield shock while SPY B-L prices only +/-%.2f%% equity move. " +
                    "Equity options appear cheap relative to rates volatility based on the %.2fx empirical baseline.",
                    shockDesc, tltYieldShockBps, safeSigmaSpy * 100.0, baselineRatio);
        } else if (ratio < 0.60 * baselineRatio) {
            verdict = "RATES VOL CHEAP vs EQUITIES";
            explanation = String.format("Equity options are pricing a large +/-%.2f%% move while TLT options price a %s +/-%.1f bps of yield shock. " +
                    "TLT straddles are cheap relative to SPY straddles based on the %.2fx empirical baseline.",
                    safeSigmaSpy * 100.0, shockDesc, tltYieldShockBps, baselineRatio);
        } else {
            verdict = "CROSS-ASSET VOL IN EQUILIBRIUM";
            explanation = String.format("TLT (implied yield shock +/-%.1f bps [%s]) and SPY (implied move +/-%.2f%%) B-L surfaces are pricing consistent macro event risk (ratio %.2f vs %.2fx baseline).",
                    tltYieldShockBps, shockDesc, safeSigmaSpy * 100.0, ratio, baselineRatio);
        }

        return new CrossAssetTriangulation(
                pPolymarket,
                pRates,
                pOptions,
                pTri,
                safeSigmaTlt,
                safeSigmaSpy,
                tltYieldShockBps,
                tltYieldMoveIfEventBps,
                tltYieldMoveIfNotEventBps,
                spyJump.moveIfEvent(),
                spyJump.moveIfNotEvent(),
                ratio,
                spyMovePer10Bps,
                fairSpySigma,
                spyVolEdge,
                verdict,
                explanation
        );
    }
}
