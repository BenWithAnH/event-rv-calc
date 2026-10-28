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
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            String endDate,
            double volume,
            List<Market> markets
    ) {
        public Event(String id, String slug, String title, List<Market> markets) {
            this(id, slug, title, "", markets.stream().mapToDouble(Market::volume).sum(), markets);
        }

        public Optional<LocalDate> resolutionDate() {
            if (endDate != null && endDate.length() >= 10) {
                try {
                    return Optional.of(LocalDate.parse(endDate.substring(0, 10)));
                } catch (Exception ignored) {}
            }
            return Optional.empty();
        }

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

        public BL.PolymarketTailBrackets extractTailBrackets() {
            double pCut50 = 0.0;
            double pCut25 = 0.0;
            double pHike25 = 0.0;
            double pHike50 = 0.0;

            for (Market m : markets) {
                String label = ((m.groupItemTitle() != null ? m.groupItemTitle() : "") + " " + m.question()).toLowerCase();
                double p = m.yesProbability();

                if (label.contains("50+ bps decrease") || label.contains("50+ bps cut") || label.contains(">50 bps decrease")
                        || label.contains("decrease by 50") || label.contains("cut by 50") || (label.contains("decrease") && label.contains("50"))) {
                    pCut50 += p;
                } else if (label.contains("25 bps decrease") || label.contains("25 bps cut") || (label.contains("decrease") && label.contains("25"))) {
                    pCut25 += p;
                } else if (label.contains("50+ bps increase") || label.contains("50+ bps hike") || label.contains(">50 bps increase")
                        || label.contains("increase by 50") || label.contains("hike by 50") || (label.contains("increase") && label.contains("50"))) {
                    pHike50 += p;
                } else if (label.contains("25 bps increase") || label.contains("25 bps hike") || (label.contains("increase") && label.contains("25"))) {
                    pHike25 += p;
                }
            }
            return new BL.PolymarketTailBrackets(pCut50, pCut25, pHike25, pHike50);
        }

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

    @FunctionalInterface
    public interface MarketResolver {
        String resolveSlugOrId(String query);
    }

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

    public static Event fetchEvent(String urlOrSlug) throws IOException, InterruptedException {
        String slug = extractSlug(urlOrSlug);
        String encoded = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        String json = getJson(GAMMA_API_BASE + "/events?slug=" + encoded);
        return parseEventJson(json);
    }

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
        return parseEventNode(eventNode);
    }

    public static List<Event> parseEventsListJson(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        if (!root.isArray()) {
            return List.of(parseEventNode(root));
        }
        List<Event> events = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            events.add(parseEventNode(node));
        }
        return events;
    }

    private static Event parseEventNode(JsonNode eventNode) throws IOException {
        String id = eventNode.path("id").asText("");
        String slug = eventNode.path("slug").asText("");
        String title = eventNode.path("title").asText("");
        String endDate = eventNode.path("endDate").asText("");
        double volume = eventNode.path("volume").asDouble(0.0);

        List<Market> markets = new ArrayList<>();
        JsonNode marketsNode = eventNode.get("markets");
        if (marketsNode != null && marketsNode.isArray()) {
            for (JsonNode mNode : marketsNode) {
                markets.add(parseMarketNode(mNode));
            }
        }
        if (volume <= 0.0) {
            volume = markets.stream().mapToDouble(Market::volume).sum();
        }
        return new Event(id, slug, title, endDate, volume, markets);
    }

    public static final String FED_CALENDAR_URL = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm";

    public static List<LocalDate> fetchFomcDecisionDates() throws IOException, InterruptedException {
        String html = getJson(FED_CALENDAR_URL);
        return parseFomcDecisionDatesHtml(html);
    }

    public static List<LocalDate> parseFomcDecisionDatesHtml(String html) {
        List<LocalDate> dates = new ArrayList<>();
        Pattern panelPat = Pattern.compile("(?s)<div class=\"panel panel-default\">.*?(\\d{4}) FOMC Meetings.*?(?=<div class=\"panel panel-default\">|</html>)");
        Pattern rowPat = Pattern.compile("(?s)fomc-meeting__month[^>]*>\\s*<strong>([A-Za-z/]+)</strong>.*?fomc-meeting__date[^>]*>\\s*(\\d{1,2})(?:-(\\d{1,2}))?");

        Matcher pm = panelPat.matcher(html);
        while (pm.find()) {
            int year = Integer.parseInt(pm.group(1));
            String panelHtml = pm.group(0);
            Matcher rm = rowPat.matcher(panelHtml);
            while (rm.find()) {
                String monthRaw = rm.group(1).trim();
                String endDayRaw = rm.group(3) != null ? rm.group(3) : rm.group(2);
                String monthName = monthRaw.contains("/") ? monthRaw.substring(monthRaw.indexOf('/') + 1) : monthRaw;
                try {
                    Month month = Month.valueOf(monthName.toUpperCase());
                    int day = Integer.parseInt(endDayRaw);
                    dates.add(LocalDate.of(year, month, day));
                } catch (Exception ignored) {}
            }
        }
        dates.sort(Comparator.naturalOrder());
        return dates;
    }

    public static List<Event> fetchActiveFomcEvents() throws IOException, InterruptedException {
        String json = getJson(GAMMA_API_BASE + "/events?series_slug=fomc&closed=false&limit=25");
        List<Event> all = parseEventsListJson(json);
        List<Event> valid = new ArrayList<>();
        for (Event e : all) {
            if (isValidFomcDecisionEvent(e)) {
                valid.add(e);
            }
        }
        valid.sort(Comparator.comparingDouble(Event::volume).reversed());
        return valid;
    }

    public static Optional<Event> findFomcEventForDate(LocalDate decisionDate, List<Event> activeEvents) {
        String targetMonth = decisionDate.getMonth().name().toLowerCase();
        return activeEvents.stream()
                .filter(Polymarket::isValidFomcDecisionEvent)
                .filter(e -> {
                    Optional<LocalDate> resDate = e.resolutionDate();
                    if (resDate.isPresent()) {
                        long daysDiff = Math.abs(resDate.get().toEpochDay() - decisionDate.toEpochDay());
                        if (daysDiff <= 4) return true;
                    }
                    return e.title().toLowerCase().contains(targetMonth)
                            && e.resolutionDate().map(d -> d.getYear() == decisionDate.getYear()).orElse(true);
                })
                .max(Comparator.comparingDouble(Event::volume));
    }

    private static boolean isValidFomcDecisionEvent(Event e) {
        if (e == null || e.markets() == null || e.markets().isEmpty()) return false;
        boolean hasHike = false;
        boolean hasOther = false;
        for (Market m : e.markets()) {
            String label = ((m.groupItemTitle() != null ? m.groupItemTitle() : "") + " " + m.question()).toLowerCase();
            if (label.contains("increase") || label.contains("hike")) hasHike = true;
            if (label.contains("decrease") || label.contains("cut") || label.contains("no change")) hasOther = true;
        }
        return hasHike && hasOther;
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

    public static BL.ZeroDriftJump solveZeroDriftJump(Market market, double sigmaEvent, boolean isEventBullish) {
        return BL.solveZeroDriftJump(sigmaEvent, market.yesProbability(), isEventBullish);
    }

    public static BL.ZeroDriftJump solveZeroDriftJump(Market market, double sigmaEvent) {
        return BL.solveZeroDriftJump(sigmaEvent, market.yesProbability(), true);
    }

    public static BL.ZeroDriftJump solveZeroDriftJump(Event event, double sigmaEvent, boolean isEventBullish) {
        return BL.solveZeroDriftJump(sigmaEvent, event.yesProbability(), isEventBullish);
    }

    public static BL.ZeroDriftJump solveZeroDriftJump(Event event, double sigmaEvent) {
        // By default for FOMC rate-hike aggregated probability, a rate hike is bearish for equities (false)
        return BL.solveZeroDriftJump(sigmaEvent, event.yesProbability(), false);
    }

    public static BL.ZeroDriftJump solveZeroDriftJump(Event event, String bracketOrId, double sigmaEvent, boolean isEventBullish) {
        return BL.solveZeroDriftJump(sigmaEvent, event.probabilityOfBracket(bracketOrId), isEventBullish);
    }

    public static BL.ZeroDriftJump solveZeroDriftJump(Event event, String bracketOrId, double sigmaEvent) {
        return BL.solveZeroDriftJump(sigmaEvent, event.probabilityOfBracket(bracketOrId), true);
    }

    public static BL.GaussianMixture fitGaussianMixture(List<BL.LogReturnPoint> jumpDensity, Market market) {
        return BL.fitGaussianMixture(jumpDensity, market.yesProbability());
    }

    public static BL.GaussianMixture fitGaussianMixture(List<BL.LogReturnPoint> jumpDensity, Event event) {
        return BL.fitGaussianMixture(jumpDensity, event.yesProbability());
    }

    public static BL.GaussianMixture fitGaussianMixture(List<BL.LogReturnPoint> jumpDensity,
                                                        Event event, String bracketOrId) {
        return BL.fitGaussianMixture(jumpDensity, event.probabilityOfBracket(bracketOrId));
    }
}
