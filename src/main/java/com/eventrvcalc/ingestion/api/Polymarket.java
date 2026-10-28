package com.eventrvcalc.ingestion.api;

import com.eventrvcalc.core.surface.BL;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polymarket Gamma API client for extracting prediction market outcome probabilities p
 * and feeding them into Breeden-Litzenberger (BL) event jump calculations.
 */
public class Polymarket {

    public static final String GAMMA_API_BASE = "https://gamma-api.polymarket.com";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Outcome(String name, double price) {}

    public record Market(
            String id,
            String question,
            String slug,
            String groupItemTitle,
            List<Outcome> outcomes,
            double volume,
            boolean closed
    ) {
        /**
         * Returns the price (implied probability p in [0, 1]) for a named outcome (e.g. "Yes"),
         * or the first outcome if no exact match is found.
         */
        public double probabilityOf(String outcomeName) {
            for (Outcome o : outcomes) {
                if (o.name().equalsIgnoreCase(outcomeName)) {
                    return o.price();
                }
            }
            throw new IllegalArgumentException("Outcome '" + outcomeName + "' not found in market " + id);
        }

        public double yesProbability() {
            return probabilityOf("Yes");
        }
    }

    public record Event(
            String id,
            String slug,
            String title,
            List<Market> markets
    ) {
        /**
         * Returns a map of bracket/outcome label (groupItemTitle or question) to its "Yes" probability.
         */
        public Map<String, Double> outcomeProbabilities() {
            Map<String, Double> map = new LinkedHashMap<>();
            for (Market m : markets) {
                String label = (m.groupItemTitle() != null && !m.groupItemTitle().isBlank())
                        ? m.groupItemTitle()
                        : m.question();
                map.put(label, m.yesProbability());
            }
            return map;
        }

        /**
         * Looks up the "Yes" probability for a specific sub-market by its groupItemTitle, slug, or id.
         */
        public double probabilityOfBracket(String bracketOrId) {
            for (Market m : markets) {
                if (bracketOrId.equalsIgnoreCase(m.groupItemTitle())
                        || bracketOrId.equalsIgnoreCase(m.slug())
                        || bracketOrId.equalsIgnoreCase(m.id())
                        || m.question().toLowerCase().contains(bracketOrId.toLowerCase())) {
                    return m.yesProbability();
                }
            }
            throw new IllegalArgumentException("Bracket/market '" + bracketOrId + "' not found in event " + slug);
        }

        /**
         * Sums the "Yes" probabilities across all rate-hike / increase brackets
         * (e.g. "25 bps increase", "50+ bps increase") into a single binary "Yes" probability.
         */
        public double yesProbability() {
            if (markets.size() == 1) {
                return markets.get(0).yesProbability();
            }
            double sum = 0.0;
            boolean matchedHike = false;
            for (Market m : markets) {
                String label = ((m.groupItemTitle() != null ? m.groupItemTitle() : "") + " " + m.question()).toLowerCase();
                if (label.contains("increase") || label.contains("hike")) {
                    sum += m.yesProbability();
                    matchedHike = true;
                }
            }
            if (!matchedHike) {
                throw new IllegalStateException("No hike/increase brackets found in event " + slug);
            }
            return Math.min(1.0, sum);
        }
    }

    /**
     * Placeholder for future market discovery logic once selection criteria are defined.
     */
    @FunctionalInterface
    public interface MarketResolver {
        String resolveSlugOrId(String query);
    }

    /**
     * Extracts the event/market slug if a full polymarket.com URL is passed, otherwise returns trimmed input.
     */
    public static String extractSlug(String urlOrSlug) {
        if (urlOrSlug == null || urlOrSlug.isBlank()) {
            throw new IllegalArgumentException("Market URL or slug must not be blank");
        }
        String s = urlOrSlug.trim();
        int qIdx = s.indexOf('?');
        if (qIdx >= 0) s = s.substring(0, qIdx);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int lastSlash = s.lastIndexOf('/');
        return lastSlash >= 0 ? s.substring(lastSlash + 1) : s;
    }

    /**
     * Fetches a Polymarket Event (and all its constituent sub-markets) from the Gamma API
     * given an event slug or full Polymarket URL (e.g. "https://polymarket.com/event/fed-decision-in-december-...").
     */
    public static Event fetchEvent(String urlOrSlug) throws IOException, InterruptedException {
        String slug = extractSlug(urlOrSlug);
        String encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        String json = getJson(GAMMA_API_BASE + "/events?slug=" + encoded);
        return parseEventJson(json);
    }

    /**
     * Fetches a single Polymarket Market by its numeric Gamma ID or slug.
     */
    public static Market fetchMarket(String idOrSlug) throws IOException, InterruptedException {
        String clean = extractSlug(idOrSlug);
        String url = clean.chars().allMatch(Character::isDigit)
                ? GAMMA_API_BASE + "/markets/" + clean
                : GAMMA_API_BASE + "/markets?slug=" + URLEncoder.encode(clean, StandardCharsets.UTF_8);
        String json = getJson(url);
        return parseMarketJson(json);
    }

    public static Event parseEventJson(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode eventNode = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
        if (eventNode == null || eventNode.isNull()) {
            throw new IllegalArgumentException("Empty or missing event in Gamma API response");
        }
        String id = eventNode.path("id").asText("");
        String slug = eventNode.path("slug").asText("");
        String title = eventNode.path("title").asText("");

        List<Market> markets = new ArrayList<>();
        JsonNode marketsNode = eventNode.get("markets");
        if (marketsNode != null && marketsNode.isArray()) {
            for (JsonNode mNode : marketsNode) {
                markets.add(parseMarketNode(mNode));
            }
        }
        return new Event(id, slug, title, markets);
    }

    public static Market parseMarketJson(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode mNode = root.isArray() ? (root.isEmpty() ? null : root.get(0)) : root;
        if (mNode == null || mNode.isNull()) {
            throw new IllegalArgumentException("Empty or missing market in Gamma API response");
        }
        return parseMarketNode(mNode);
    }

    private static Market parseMarketNode(JsonNode mNode) throws IOException {
        String id = mNode.path("id").asText("");
        String question = mNode.path("question").asText("");
        String slug = mNode.path("slug").asText("");
        String groupItemTitle = mNode.path("groupItemTitle").asText("");
        double volume = mNode.path("volumeNum").asDouble(mNode.path("volume").asDouble(0.0));
        boolean closed = mNode.path("closed").asBoolean(false);

        List<String> names = parseStringArrayField(mNode.get("outcomes"));
        List<String> prices = parseStringArrayField(mNode.get("outcomePrices"));

        int count = Math.min(names.size(), prices.size());
        List<Outcome> outcomes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            outcomes.add(new Outcome(names.get(i), Double.parseDouble(prices.get(i))));
        }

        return new Market(id, question, slug, groupItemTitle, outcomes, volume, closed);
    }

    private static List<String> parseStringArrayField(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return List.of();
        JsonNode arrNode = node.isTextual() ? MAPPER.readTree(node.asText()) : node;
        if (!arrNode.isArray()) return List.of();
        List<String> result = new ArrayList<>(arrNode.size());
        for (JsonNode item : arrNode) {
            result.add(item.asText());
        }
        return result;
    }

    private static String getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "event-rv-calc/1.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Gamma API HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    // --- BL.java Calculation Bridges ---

    /**
     * Passes a market's "Yes" outcome probability p into BL.solveZeroDriftJump(sigmaEvent, p).
     */
    public static BL.ZeroDriftJump solveZeroDriftJump(Market market, double sigmaEvent) {
        return BL.solveZeroDriftJump(sigmaEvent, market.yesProbability());
    }

    /**
     * Passes an Event's aggregated hike "Yes" probability p into BL.solveZeroDriftJump(sigmaEvent, p).
     */
    public static BL.ZeroDriftJump solveZeroDriftJump(Event event, double sigmaEvent) {
        return BL.solveZeroDriftJump(sigmaEvent, event.yesProbability());
    }

    /**
     * Passes a specific bracket's probability p from a multi-bracket Event into BL.solveZeroDriftJump(sigmaEvent, p).
     */
    public static BL.ZeroDriftJump solveZeroDriftJump(Event event, String bracketOrId, double sigmaEvent) {
        return BL.solveZeroDriftJump(sigmaEvent, event.probabilityOfBracket(bracketOrId));
    }

    /**
     * Passes a market's "Yes" outcome probability p into BL.fitGaussianMixture(jumpDensity, p).
     */
    public static BL.GaussianMixture fitGaussianMixture(List<BL.LogReturnPoint> jumpDensity, Market market) {
        return BL.fitGaussianMixture(jumpDensity, market.yesProbability());
    }

    /**
     * Passes an Event's aggregated hike "Yes" probability p into BL.fitGaussianMixture(jumpDensity, p).
     */
    public static BL.GaussianMixture fitGaussianMixture(List<BL.LogReturnPoint> jumpDensity, Event event) {
        return BL.fitGaussianMixture(jumpDensity, event.yesProbability());
    }

    /**
     * Passes a specific bracket's probability p from a multi-bracket Event into BL.fitGaussianMixture(jumpDensity, p).
     */
    public static BL.GaussianMixture fitGaussianMixture(List<BL.LogReturnPoint> jumpDensity,
                                                        Event event, String bracketOrId) {
        return BL.fitGaussianMixture(jumpDensity, event.probabilityOfBracket(bracketOrId));
    }
}

