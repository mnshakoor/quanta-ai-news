package news;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** JSON view models, shared by the live HTTP API (Main) and the static exporter (Export). */
final class Views {
    private final SourceRegistry registry;
    private final StoryStore.Snapshot snap;
    private final Instant nextRefresh;
    private final boolean refreshing;
    private final int refreshMinutes;

    Views(SourceRegistry registry, StoryStore.Snapshot snap, Instant nextRefresh, boolean refreshing, int refreshMinutes) {
        this.registry = registry;
        this.snap = snap;
        this.nextRefresh = nextRefresh;
        this.refreshing = refreshing;
        this.refreshMinutes = refreshMinutes;
    }

    Map<String, Object> meta() {
                int l = 0, c = 0, r = 0;
        Map<String, Integer> cats = new TreeMap<>();
        for (Models.Story s : snap.stories()) {
            l += s.left(); c += s.center(); r += s.right();
            cats.merge(s.category(), 1, Integer::sum);
        }
        int t = Math.max(1, l + c + r);
        long ok = snap.feeds().values().stream().filter(Models.FeedStatus::ok).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", snap.mode());
        m.put("refreshedAt", snap.refreshedAt());
        m.put("nextRefresh", nextRefresh);
        m.put("refreshing", refreshing);
        m.put("refreshMinutes", refreshMinutes);
        m.put("storyCount", snap.stories().size());
        m.put("articleCount", snap.articleCount());
        m.put("sourceCount", registry.all().size());
        m.put("feedsOk", ok);
        m.put("feedsTotal", registry.all().stream().filter(Models.Source::fetchable).count());
        m.put("blindspotCount", snap.stories().stream().filter(s -> s.blindspot() != null).count());
        Map<String, Object> cov = new LinkedHashMap<>();
        cov.put("left", Math.round(100.0 * l / t));
        cov.put("center", Math.round(100.0 * c / t));
        cov.put("right", Math.round(100.0 * r / t));
        m.put("coverage", cov);
        m.put("categories", cats);
        Map<String, Integer> regions = new LinkedHashMap<>();
        for (String rg : Regions.ALL) regions.put(rg, 0);
        regions.put(Regions.UNSPECIFIED, 0);
        snap.stories().forEach(s -> regions.merge(s.eventRegion(), 1, Integer::sum));
        m.put("regions", regions);
        Map<String, Integer> outletRegions = new LinkedHashMap<>();
        for (String rg : Regions.ALL) outletRegions.put(rg, 0);
        registry.all().forEach(s -> outletRegions.merge(s.region(), 1, Integer::sum));
        m.put("outletRegions", outletRegions);
        m.put("unratedOutlets", registry.all().stream().filter(s -> s.group().equals("unrated")).count());
        m.put("summaryCount", snap.summaries().size());
        m.put("summaryModel", snap.summaries().values().stream().map(Summarizer.Summary::model).findFirst().orElse(null));
        String report = System.getenv("REPORT_URL");
        if (report != null && report.startsWith("https://")) m.put("reportUrl", report);
        m.put("method", Map.of("clusterThreshold", Clusterer.THRESHOLD, "minSources", Clusterer.MIN_SOURCES,
                "blindspotMinSources", StoryBuilder.BLINDSPOT_MIN_SOURCES,
                "blindspotDominant", StoryBuilder.BLINDSPOT_DOMINANT,
                "blindspotAbsent", StoryBuilder.BLINDSPOT_ABSENT));
        return m;
    }

    Map<String, Object> listStories(Map<String, String> q) {
        String view = q.getOrDefault("view", "top");
        String bias = q.getOrDefault("bias", "all");
        String category = q.getOrDefault("category", "");
        String region = q.getOrDefault("region", "");
        String text = q.getOrDefault("q", "").toLowerCase(Locale.ROOT).trim();
        String ids = q.getOrDefault("ids", "");
        int limit = Math.min(300, Math.max(1, parseInt(q.get("limit"), 60)));

        List<Models.Story> list = snap.stories();
        if (!ids.isEmpty()) {
            Set<String> want = new HashSet<>(Arrays.asList(ids.split(",")));
            list = list.stream().filter(s -> want.contains(s.id())).toList();
        }
        var stream = list.stream();
        if (view.equals("blindspot")) stream = stream.filter(s -> s.blindspot() != null);
        if (view.equals("polarized")) stream = stream.filter(Models.Story::polarized);
        if (view.equals("latest")) stream = stream.sorted(Comparator.comparing(Models.Story::lastUpdated).reversed());
        if (!bias.equals("all")) stream = stream.filter(s -> switch (bias) {
            case "left" -> s.left() > 0; case "right" -> s.right() > 0; case "center" -> s.center() > 0;
            case "unrated" -> s.unrated() > 0;
            default -> true; });
        if (!category.isEmpty()) stream = stream.filter(s -> s.category().equalsIgnoreCase(category));
        if (!region.isEmpty()) stream = stream.filter(s -> s.eventRegion().equalsIgnoreCase(region));
        if (!text.isEmpty()) stream = stream.filter(s -> (s.title() + " " + s.summary() + " "
                + s.articles().stream().map(a -> a.title() + " " + a.source().name()).collect(Collectors.joining(" ")))
                .toLowerCase(Locale.ROOT).contains(text));
        List<Models.Story> filtered = stream.toList();
        List<Object> items = filtered.stream().limit(limit).map(this::storySummary).collect(Collectors.toList());
        return Map.of("total", filtered.size(), "items", items);
    }

    Map<String, Object> storySummary(Models.Story s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("title", s.title());
        m.put("summary", s.summary());
        m.put("category", s.category());
        m.put("image", s.image());
        m.put("firstSeen", s.firstSeen());
        m.put("lastUpdated", s.lastUpdated());
        m.put("sourceCount", s.sourceCount());
        m.put("articleCount", s.articles().size());
        m.put("counts", Map.of("left", s.left(), "center", s.center(), "right", s.right(), "unrated", s.unrated()));
        m.put("eventRegion", s.eventRegion());
        Map<String, Integer> byRegion = new TreeMap<>();
        Set<String> regionSeen = new HashSet<>();
        for (Models.Article a : s.articles()) {
            if (regionSeen.add(a.source().id())) byRegion.merge(a.source().region(), 1, Integer::sum);
        }
        m.put("outletRegions", byRegion);
        m.put("pct", Map.of("left", s.pct(s.left()), "center", s.pct(s.center()), "right", s.pct(s.right())));
        m.put("blindspot", s.blindspot());
        m.put("polarized", s.polarized());
        m.put("hasSummary", snap.summaries().containsKey(s.id()));
        List<Object> outlets = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Models.Article a : s.articles()) {
            if (seen.add(a.source().id())) outlets.add(Map.of("name", a.source().name(), "bias", a.source().bias()));
        }
        m.put("outlets", outlets);
        return m;
    }

    Map<String, Object> storyDetail(Models.Story s) {
        Map<String, Object> m = storySummary(s);
        Map<String, Integer> fact = new LinkedHashMap<>();
        for (String f : List.of("High", "Mixed", "Low", "Unrated")) fact.put(f, 0);
        Map<String, Integer> own = new TreeMap<>();
        Map<String, Integer> five = new LinkedHashMap<>();
        for (String b : List.of("Left", "Lean Left", "Center", "Lean Right", "Right", "Unrated")) five.put(b, 0);
        Set<String> seen = new HashSet<>();
        List<Object> arts = new ArrayList<>();
        for (Models.Article a : s.articles()) {
            Models.Source src = a.source();
            if (seen.add(src.id())) {
                fact.merge(src.factuality(), 1, Integer::sum);
                own.merge(src.ownership(), 1, Integer::sum);
                five.merge(src.bias(), 1, Integer::sum);
            }
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("title", a.title());
            am.put("description", a.description());
            am.put("link", a.link());
            am.put("image", a.image());
            am.put("published", a.published());
            am.put("source", sourceMap(src, null));
            arts.add(am);
        }
        Models.Article first = s.articles().get(0);
        m.put("firstReportedBy", Map.of("name", first.source().name(), "bias", first.source().bias(),
                "at", first.published()));
        m.put("factuality", fact);
        m.put("ownership", own);
        m.put("biasFive", five);
        m.put("articles", arts);
        m.put("demo", s.id().startsWith("demo"));
        Summarizer.Summary sum = snap.summaries().get(s.id());
        if (sum != null) m.put("aiSummary", summaryView(sum));
        return m;
    }

    Map<String, Object> sources() {
                Map<String, Integer> storyCounts = new HashMap<>();
        for (Models.Story s : snap.stories()) {
            s.articles().stream().map(a -> a.source().id()).distinct()
                    .forEach(id -> storyCounts.merge(id, 1, Integer::sum));
        }
        List<Object> list = new ArrayList<>();
        for (Models.Source s : registry.all()) {
            Map<String, Object> m = sourceMap(s, snap.feeds().get(s.id()));
            m.put("storyCount", storyCounts.getOrDefault(s.id(), 0));
            list.add(m);
        }
        return Map.of("items", list, "provenance",
                "Bias and factuality ratings for US-scale outlets are provisional seeds approximated from public aggregate "
              + "rating charts (AllSides, Ad Fontes Media, Media Bias/Fact Check). International outlets are deliberately "
              + "Unrated: the US left/right scale does not transfer to other political systems. Verify before analytic or published use.");
    }

    static Map<String, Object> sourceMap(Models.Source s, Models.FeedStatus st) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("bias", s.bias());
        m.put("group", s.group());
        m.put("factuality", s.factuality());
        m.put("ownership", s.ownership());
        m.put("owner", s.owner());
        m.put("country", s.country());
        m.put("region", s.region());
        m.put("homepage", s.homepage());
        m.put("live", s.fetchable());
        if (st != null) {
            m.put("feedOk", st.ok());
            m.put("feedArticles", st.articles());
            m.put("feedError", st.error());
            m.put("feedCheckedAt", st.checkedAt());
            m.put("feedMillis", st.millis());
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    /** Resolves cited outlet ids to names and ratings for display. */
    private Map<String, Object> summaryView(Summarizer.Summary sum) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", sum.model());
        m.put("generatedAt", sum.generatedAt());
        List<Object> bullets = new ArrayList<>();
        for (Summarizer.Bullet b : sum.bullets()) {
            List<Object> srcs = new ArrayList<>();
            for (String id : b.sourceIds()) {
                Models.Source src = registry.byId(id);
                if (src != null) srcs.add(Map.of("id", id, "name", src.name(), "bias", src.bias()));
            }
            bullets.add(Map.of("text", b.text(), "sources", srcs));
        }
        m.put("bullets", bullets);
        m.put("framing", sum.framing());
        return m;
    }
}
