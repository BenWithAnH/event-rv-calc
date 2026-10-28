package com.eventrvcalc.core.surface;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Runner that executes CRR American de-Americanization and Black-Scholes
 * volatility curve fitting on real option market data (e.g. SPY).
 */
public class SurfaceRunner {

    public record OptionQuoteInput(
            double strike,
            int eta, // +1 call, -1 put, 0 blended
            double mid,
            double bid,
            double ask,
            double exchangeIv,
            double callMid,
            double putMid,
            double callWeight,
            double putWeight
    ) {}

    public record ProcessedQuote(
            double strike,
            int eta,
            double mid,
            double bid,
            double ask,
            double exchangeIv,
            double deAmSigmaHat,
            double euroCrrPrice,
            double earlyExercisePremium,
            double callWeight,
            double putWeight
    ) {}

    public static void main(String[] args) throws Exception {
        String inputFile = args.length > 0 ? args[0] : "spy_input.json";
        String outputFile = args.length > 1 ? args[1] : "surface_data.json";

        File in = new File(inputFile);
        if (!in.exists()) {
            System.err.println("Input file not found: " + in.getAbsolutePath());
            System.exit(1);
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(in);

        double S0 = root.get("S0").asDouble();
        double T = root.get("T").asDouble();
        double r = root.get("r").asDouble();
        double q = root.get("q").asDouble();
        int N = root.has("N") ? root.get("N").asInt() : 100;
        String quoteDate = root.has("quoteDate") ? root.get("quoteDate").asText() : "";
        String expireDate = root.has("expireDate") ? root.get("expireDate").asText() : "";
        double dte = root.has("dte") ? root.get("dte").asDouble() : T * 365.0;

        if (root.has("atmK") && root.has("atmCallMid") && root.has("atmPutMid")) {
            double atmK = root.get("atmK").asDouble();
            double cMid = root.get("atmCallMid").asDouble();
            double pMid = root.get("atmPutMid").asDouble();
            S0 = CRR.implyEffectiveSpot(atmK, cMid, pMid, T, r, N);
            q = 0.0; // Effective dividend absorbed into implied spot
            System.out.printf("Java CRR: Calibrated true implied spot from Put-Call Parity: S0=%.4f (q=0.0)%n", S0);
        }

        List<OptionQuoteInput> rawQuotes = new ArrayList<>();
        JsonNode quotesNode = root.get("quotes");
        if (quotesNode != null && quotesNode.isArray()) {
            for (JsonNode qNode : quotesNode) {
                double strike = qNode.get("strike").asDouble();
                int eta = qNode.has("eta") ? qNode.get("eta").asInt() : 0;
                double mid = qNode.has("mid") ? qNode.get("mid").asDouble() : 0.0;
                double bid = qNode.has("bid") ? qNode.get("bid").asDouble() : 0.0;
                double ask = qNode.has("ask") ? qNode.get("ask").asDouble() : 0.0;
                double exchangeIv = qNode.has("exchangeIv") ? qNode.get("exchangeIv").asDouble() : 0.0;
                double callMid = qNode.has("callMid") ? qNode.get("callMid").asDouble() : (eta == 1 ? mid : 0.0);
                double putMid = qNode.has("putMid") ? qNode.get("putMid").asDouble() : (eta == -1 ? mid : 0.0);
                double callWeight = qNode.has("callWeight") ? qNode.get("callWeight").asDouble() : (eta == 1 ? 1.0 : 0.0);
                double putWeight = qNode.has("putWeight") ? qNode.get("putWeight").asDouble() : (eta == -1 ? 1.0 : 0.0);

                rawQuotes.add(new OptionQuoteInput(
                        strike, eta, mid, bid, ask, exchangeIv, callMid, putMid, callWeight, putWeight
                ));
            }
        }

        System.out.printf("Processing %d real market quotes for SPY (S0=%.2f, T=%.4f, r=%.4f, q=%.4f, N=%d)...%n",
                rawQuotes.size(), S0, T, r, q, N);

        List<ProcessedQuote> processedQuotes = new ArrayList<>();
        List<Double> validStrikes = new ArrayList<>();
        List<Double> validVols = new ArrayList<>();

        for (OptionQuoteInput quote : rawQuotes) {
            try {
                double sigmaHat;
                double euroCrrPrice;
                double earlyExercisePremium;

                if (quote.callWeight() > 0.0 && quote.putWeight() > 0.0) {
                    // Smooth ATM blend zone: solve sigmaHat for both Call and Put and blend
                    double sigCall = CRR.findSigmaHat(S0, quote.strike(), T, r, q, N, 1, quote.callMid());
                    double sigPut = CRR.findSigmaHat(S0, quote.strike(), T, r, q, N, -1, quote.putMid());
                    sigmaHat = quote.callWeight() * sigCall + quote.putWeight() * sigPut;

                    double euroCall = CRR.europeanPrice(S0, quote.strike(), T, r, q, sigCall, N, 1);
                    double euroPut = CRR.europeanPrice(S0, quote.strike(), T, r, q, sigPut, N, -1);
                    euroCrrPrice = quote.callWeight() * euroCall + quote.putWeight() * euroPut;

                    double callEEP = quote.callMid() - euroCall;
                    double putEEP = quote.putMid() - euroPut;
                    earlyExercisePremium = quote.callWeight() * callEEP + quote.putWeight() * putEEP;
                } else if (quote.callWeight() >= 1.0 || quote.eta() == 1) {
                    sigmaHat = CRR.findSigmaHat(S0, quote.strike(), T, r, q, N, 1, quote.mid());
                    euroCrrPrice = CRR.europeanPrice(S0, quote.strike(), T, r, q, sigmaHat, N, 1);
                    earlyExercisePremium = quote.mid() - euroCrrPrice;
                } else {
                    sigmaHat = CRR.findSigmaHat(S0, quote.strike(), T, r, q, N, -1, quote.mid());
                    euroCrrPrice = CRR.europeanPrice(S0, quote.strike(), T, r, q, sigmaHat, N, -1);
                    earlyExercisePremium = quote.mid() - euroCrrPrice;
                }

                processedQuotes.add(new ProcessedQuote(
                        quote.strike(), quote.eta(), quote.mid(), quote.bid(), quote.ask(), quote.exchangeIv(),
                        sigmaHat, euroCrrPrice, earlyExercisePremium, quote.callWeight(), quote.putWeight()
                ));
                validStrikes.add(quote.strike());
                validVols.add(sigmaHat);
            } catch (Exception e) {
                System.err.printf("Skipping strike %.1f: %s%n", quote.strike(), e.getMessage());
            }
        }

        double[] strikesArr = validStrikes.stream().mapToDouble(Double::doubleValue).toArray();
        double[] volsArr = validVols.stream().mapToDouble(Double::doubleValue).toArray();

        // Fit 5-parameter SVI model to total variance w(k) = sigmaHat_K^2 * T
        BS.SviVolCurve fittedSvi = BS.fitSvi(S0, T, strikesArr, volsArr, 0.10, 0.025);
        System.out.printf("Java BS: Fitted 5-parameter SVI: a=%.6f, b=%.6f, rho=%.6f, m=%.6f, s=%.6f (vertex strike K=%.2f)%n",
                fittedSvi.a(), fittedSvi.b(), fittedSvi.rho(), fittedSvi.m(), fittedSvi.s(), fittedSvi.vertexStrike());

        // Also fit quadratic volatility smile: sigma(K) = a*K^2 + b*K + c for reference
        BS.QuadraticVolCurve fittedCurve = BS.fitVolCurve(strikesArr, volsArr);

        // Generate synthetic European price curves using SVI curve on a dense strike grid
        double minK = strikesArr.length > 0 ? strikesArr[0] - 5.0 : S0 * 0.8;
        double maxK = strikesArr.length > 0 ? strikesArr[strikesArr.length - 1] + 5.0 : S0 * 1.2;
        int gridSteps = 150;
        double[] gridStrikes = new double[gridSteps];
        double step = (maxK - minK) / (gridSteps - 1);
        for (int i = 0; i < gridSteps; i++) {
            gridStrikes[i] = minK + i * step;
        }

        List<BS.PricePoint> euroCalls = BS.generatePriceCurve(S0, gridStrikes, T, r, q, fittedSvi, 1);
        List<BS.PricePoint> euroPuts = BS.generatePriceCurve(S0, gridStrikes, T, r, q, fittedSvi, -1);

        // Extract Breeden-Litzenberger risk-neutral probability density from SVI call curve
        double blMinK = strikesArr.length > 0 ? strikesArr[0] - 15.0 : S0 * 0.75;
        double blMaxK = strikesArr.length > 0 ? strikesArr[strikesArr.length - 1] + 15.0 : S0 * 1.25;
        int blSteps = 250;
        double[] blStrikes = new double[blSteps];
        double blStep = (blMaxK - blMinK) / (blSteps - 1);
        for (int i = 0; i < blSteps; i++) {
            blStrikes[i] = blMinK + i * blStep;
        }

        List<BL.DensityPoint> densitySlice = BL.generateDensitySlice(S0, blStrikes, T, r, q, fittedSvi, 0.05);
        double blExpectedST = BL.calculateExpectedST(densitySlice);
        double blTotalProb = BL.calculateTotalProbability(densitySlice);
        double forwardPrice = S0 * Math.exp((r - q) * T);
        System.out.printf("Java BL: Extracted Breeden-Litzenberger density: totalProb=%.4f, E^Q[S_T]=%.2f (Forward=%.2f)%n",
                blTotalProb, blExpectedST, forwardPrice);

        // Tree convergence study at ATM strike
        double atmVol = fittedSvi.vol(S0);
        double bsExact = BS.callPrice(S0, S0, T, r, q, atmVol);
        List<ObjectNode> convergenceNodes = new ArrayList<>();
        for (int steps = 5; steps <= 150; steps += 3) {
            double crrVal = CRR.price(S0, S0, T, r, q, atmVol, steps, 1);
            ObjectNode cNode = mapper.createObjectNode();
            cNode.put("steps", steps);
            cNode.put("crrPrice", crrVal);
            cNode.put("bsPrice", bsExact);
            convergenceNodes.add(cNode);
        }

        // Build output JSON
        ObjectNode outRoot = mapper.createObjectNode();
        outRoot.put("source", "spy_eod_2023.parquet");
        outRoot.put("quoteDate", quoteDate);
        outRoot.put("expireDate", expireDate);
        outRoot.put("dte", dte);
        outRoot.put("S0", S0);
        outRoot.put("T", T);
        outRoot.put("r", r);
        outRoot.put("q", q);
        outRoot.put("forwardPrice", forwardPrice);
        outRoot.put("blTotalProb", blTotalProb);
        outRoot.put("blExpectedST", blExpectedST);
        outRoot.put("defaultN", N);
        outRoot.put("fittedA", fittedCurve.a());
        outRoot.put("fittedB", fittedCurve.b());
        outRoot.put("fittedC", fittedCurve.c());

        ObjectNode sviNode = outRoot.putObject("svi");
        sviNode.put("a", fittedSvi.a());
        sviNode.put("b", fittedSvi.b());
        sviNode.put("rho", fittedSvi.rho());
        sviNode.put("m", fittedSvi.m());
        sviNode.put("s", fittedSvi.s());
        sviNode.put("vertexStrike", fittedSvi.vertexStrike());

        ArrayNode qArr = outRoot.putArray("quotes");
        for (ProcessedQuote pq : processedQuotes) {
            ObjectNode node = qArr.addObject();
            node.put("strike", pq.strike());
            node.put("eta", pq.eta());
            node.put("mid", pq.mid());
            node.put("bid", pq.bid());
            node.put("ask", pq.ask());
            node.put("exchangeIv", pq.exchangeIv());
            node.put("deAmSigmaHat", pq.deAmSigmaHat());
            node.put("euroCrrPrice", pq.euroCrrPrice());
            node.put("earlyExercisePremium", pq.earlyExercisePremium());
            node.put("callWeight", pq.callWeight());
            node.put("putWeight", pq.putWeight());
        }

        ArrayNode cArr = outRoot.putArray("syntheticEuroCalls");
        for (BS.PricePoint pp : euroCalls) {
            ObjectNode node = cArr.addObject();
            node.put("strike", pp.strike());
            node.put("vol", pp.vol());
            node.put("price", pp.price());
        }

        ArrayNode pArr = outRoot.putArray("syntheticEuroPuts");
        for (BS.PricePoint pp : euroPuts) {
            ObjectNode node = pArr.addObject();
            node.put("strike", pp.strike());
            node.put("vol", pp.vol());
            node.put("price", pp.price());
        }

        ArrayNode blArr = outRoot.putArray("breedenLitzenberger");
        for (BL.DensityPoint dp : densitySlice) {
            ObjectNode node = blArr.addObject();
            node.put("strike", dp.strike());
            node.put("callPrice", dp.callPrice());
            node.put("secondDerivative", dp.secondDerivative());
            node.put("density", dp.density());
            node.put("cdf", dp.cdf());
        }

        ArrayNode convArr = outRoot.putArray("convergence");
        convArr.addAll(convergenceNodes);

        mapper.enable(SerializationFeature.INDENT_OUTPUT).writeValue(new File(outputFile), outRoot);
        System.out.println("Exported real SPY surface data to: " + outputFile);
    }
}
