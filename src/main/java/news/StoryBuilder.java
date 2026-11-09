package news;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Turns a group of articles into a Story with coverage statistics. */
final class StoryBuilder {
    private StoryBuilder() {}

    /*
     * Blindspot rule (documented in the Methodology view):
     * a story is a blindspot for the right when at least 3 outlets cover it, left-leaning
     * outlets supply at least 60 percent of coverage, and right-leaning outlets supply at
     * most 10 percent. The mirror rule defines a blindspot for the left.
     */
    static final int BLINDSPOT_MIN_SOURCES = 3;
    static final double BLINDSPOT_DOMINANT = 0.60;
    static final double BLINDSPOT_ABSENT = 0.10;

    private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();
    static {
        CATEGORIES.put("Politics", List.of("trump", "biden", "congress", "senate", "house", "democrat*", "republican", "gop", "election", "vote", "voter", "campaign", "governor", "lawmaker", "white house", "speaker", "poll", "midterm", "administration", "partisan", "legislat*"));
        CATEGORIES.put("World", List.of("ukraine", "russia", "china", "israel", "gaza", "iran", "nato", "united nations", "europe", "africa", "india", "taiwan", "korea", "syria", "sudan", "venezuela", "mexico", "ceasefire", "war", "military", "minister", "embassy", "foreign"));
        CATEGORIES.put("Business", List.of("stock", "market", "economy", "inflation", "fed", "federal reserve", "rate", "tariff", "trade", "earnings", "company", "ceo", "bank", "jobs", "unemployment", "gdp", "price", "oil", "dollar", "strike", "merger", "wall street"));
        CATEGORIES.put("Technology", List.of("ai", "artificial intelligence", "tech", "apple", "google", "microsoft", "meta", "openai", "chip", "software", "cyber", "hack", "data", "app", "tesla", "musk", "crypto", "bitcoin", "privacy", "robot"));
        CATEGORIES.put("Health", List.of("health", "vaccin*", "covid", "virus", "disease", "cancer", "drug", "fda", "cdc", "hospital", "medical", "patient", "measles", "outbreak", "medicaid", "medicare"));
        CATEGORIES.put("Environment", List.of("climate", "wildfire", "hurricane", "storm", "flood", "earthquake", "weather", "heat", "emission", "environment", "drought", "tornado", "snow", "energy"));
        CATEGORIES.put("Science", List.of("nasa", "space", "scientist", "study", "research", "planet", "moon", "rocket", "species", "fossil"));
        CATEGORIES.put("Justice", List.of("court", "judge", "supreme court", "lawsuit", "trial", "police", "shooting", "arrest", "charged", "prison", "doj", "fbi", "indict*", "verdict", "crime", "killed", "murder", "sentenced"));
        CATEGORIES.put("Immigration", List.of("immigra*", "border", "asylum", "migrant", "deport*", "ice", "visa", "refugee"));
        CATEGORIES.put("Sports", List.of("nfl", "nba", "mlb", "nhl", "soccer", "football", "basketball", "baseball", "olympic", "world cup", "championship", "coach", "tennis", "golf", "playoff"));
        CATEGORIES.put("Entertainment", List.of("film", "movie", "music", "album", "celebrity", "actor", "actress", "singer", "oscar", "grammy", "netflix", "hollywood", "tv show", "box office"));
    }

    static Models.Story build(List<Models.Article> items, String titleOverride, String summaryOverride) {
        List<Models.Article> arts = new ArrayList<>(items);
        arts.sort(Comparator.comparing(Models.Article::published));

        // Count distinct outlets per side; an outlet with several articles counts once.
        Map<String, Models.Source> outlets = new LinkedHashMap<>();
        arts.forEach(a -> outlets.putIfAbsent(a.source().id(), a.source()));
        int l = 0, c = 0, r = 0;
        for (Models.Source s : outlets.values()) {
            switch (s.group()) {
                case "left" -> l++;
                case "right" -> r++;
                default -> c++;
            }
        }
        int total = outlets.size();
        String blindspot = null;
        if (total >= BLINDSPOT_MIN_SOURCES) {
            double L = (double) l / total, R = (double) r / total;
            if (L >= BLINDSPOT_DOMINANT && R <= BLINDSPOT_ABSENT) blindspot = "right";
            else if (R >= BLINDSPOT_DOMINANT && L <= BLINDSPOT_ABSENT) blindspot = "left";
        }
        boolean polarized = total >= 4 && (double) c / total < 0.25 && l >= 2 && r >= 2;

        Models.Article lead = pickLead(arts);
        String title = titleOverride != null ? titleOverride : lead.title();
        String summary = summaryOverride != null ? summaryOverride : firstSummary(lead, arts);
        String image = lead.image() != null ? lead.image()
                : arts.stream().map(Models.Article::image).filter(Objects::nonNull).findFirst().orElse(null);

        Instant first = arts.get(0).published();
        Instant last = arts.get(arts.size() - 1).published();
        double ageHours = Math.max(0, Duration.between(last, Instant.now()).toMinutes() / 60.0);
        double balance = 1.0 + 0.15 * (Math.min(l, 1) + Math.min(c, 1) + Math.min(r, 1));
        double score = total * balance / Math.pow(ageHours + 2, 0.8);

        String id = Integer.toHexString((arts.get(0).link() + arts.get(0).source().id()).hashCode() & 0x7fffffff);
        return new Models.Story(id, title, summary, categorize(arts), image, first, last,
                List.copyOf(arts), l, c, r, total, blindspot, polarized, score);
    }

    /** Prefers a center outlet headline, then the article whose terms best represent the cluster. */
    private static Models.Article pickLead(List<Models.Article> arts) {
        String core = Clusterer.topTerms(arts, 6);
        Set<String> coreSet = new HashSet<>(Arrays.asList(core.split(" ")));
        Models.Article best = arts.get(0);
        double bestScore = -1;
        for (Models.Article a : arts) {
            double overlap = Clusterer.tokens(a.title()).stream().filter(coreSet::contains).count();
            double s = overlap + ("center".equals(a.source().group()) ? 1.5 : 0)
                    + (a.description().length() > 60 ? 0.5 : 0) + (a.image() != null ? 0.3 : 0);
            if (s > bestScore) { bestScore = s; best = a; }
        }
        return best;
    }

    private static String firstSummary(Models.Article lead, List<Models.Article> arts) {
        if (lead.description().length() > 40) return lead.description();
        return arts.stream().map(Models.Article::description).filter(d -> d.length() > 40)
                .findFirst().orElse(lead.description());
    }

    private static String categorize(List<Models.Article> arts) {
        StringBuilder sb = new StringBuilder(" ");
        arts.forEach(a -> sb.append(a.title()).append(' ').append(a.description()).append(' '));
        String text = sb.toString().toLowerCase(Locale.ROOT);
        String best = "General";
        int bestHits = 0;
        for (Map.Entry<String, List<String>> e : CATEGORIES.entrySet()) {
            int hits = 0;
            for (String kw : e.getValue()) {
                boolean prefix = kw.endsWith("*");
                String k = prefix ? kw.substring(0, kw.length() - 1) : kw;
                int idx = 0;
                while ((idx = text.indexOf(k, idx)) >= 0) {
                    int end = idx + k.length();
                    boolean startOk = idx == 0 || !Character.isLetter(text.charAt(idx - 1));
                    // Whole word, optionally plural; '*' keywords match as word prefixes.
                    boolean endOk = prefix || end >= text.length() || !Character.isLetter(text.charAt(end))
                            || (text.charAt(end) == 's' && (end + 1 >= text.length() || !Character.isLetter(text.charAt(end + 1))));
                    if (startOk && endOk) hits++;
                    idx = end;
                }
            }
            if (hits > bestHits) { bestHits = hits; best = e.getKey(); }
        }
        return best;
    }
}
