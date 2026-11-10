package news;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Offline checks for feed parsing, clustering and blindspot scoring. Run: java -cp out news.SelfTest */
public final class SelfTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        SourceRegistry reg = new SourceRegistry(Path.of("config/sources.tsv"));
        String now = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        String iso = Instant.now().toString();

        // RSS 2.0 with media image and HTML description.
        String rss = "﻿<?xml version=\"1.0\"?><rss xmlns:media=\"http://search.yahoo.com/mrss/\"><channel>"
                + "<item><title>Senate passes &amp; sends border funding bill to president</title>"
                + "<link>https://x.test/a</link><description><![CDATA[<p>The Senate voted 61-38 on the <b>border</b> bill.</p>]]></description>"
                + "<pubDate>" + now + "</pubDate><media:content url=\"https://img.test/a.jpg\" medium=\"image\"/></item>"
                + "</channel></rss>";
        List<Models.Article> a = FeedFetcher.parse(reg.byId("nyt"), rss.getBytes(StandardCharsets.UTF_8));
        check(a.size() == 1, "rss parses one item");
        check(a.get(0).title().contains("& sends"), "rss entity decoded");
        check(a.get(0).description().startsWith("The Senate voted"), "rss html stripped");
        check("https://img.test/a.jpg".equals(a.get(0).image()), "rss media image");

        // Atom.
        String atom = "<feed xmlns=\"http://www.w3.org/2005/Atom\"><entry><title>Senate passes border funding bill</title>"
                + "<link rel=\"alternate\" href=\"https://y.test/b\"/><updated>" + iso + "</updated>"
                + "<summary>Lawmakers approved border funding.</summary></entry></feed>";
        List<Models.Article> b = FeedFetcher.parse(reg.byId("foxnews"), atom.getBytes(StandardCharsets.UTF_8));
        check(b.size() == 1 && b.get(0).link().equals("https://y.test/b"), "atom parses link");

        // XXE must not resolve.
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<rss><channel><item><title>t &x;</title><link>https://z.test</link></item></channel></rss>";
        try {
            List<Models.Article> c = FeedFetcher.parse(reg.byId("bbc"), xxe.getBytes(StandardCharsets.UTF_8));
            check(c.isEmpty() || !c.get(0).title().contains("root:"), "xxe not expanded");
        } catch (Exception e) {
            check(true, "xxe rejected");
        }

        // Clustering: two events, each covered by several outlets, plus noise.
        List<Models.Article> arts = new ArrayList<>();
        Instant t = Instant.now();
        String[][] border = {
            {"nyt", "Senate passes border funding bill after long standoff"},
            {"cnn", "Border funding bill clears Senate in bipartisan vote"},
            {"vox", "What the Senate border funding bill does"},
            {"guardian", "US Senate passes border funding bill"},
            {"huffpost", "Senate approves border funding bill despite objections"}};
        String[][] quake = {
            {"bbc", "Powerful earthquake strikes off coast of Japan, tsunami warning issued"},
            {"foxnews", "Japan earthquake triggers tsunami warning along coast"},
            {"nypost", "Massive earthquake hits Japan, tsunami warning in effect"},
            {"thehill", "Tsunami warning issued after earthquake off Japan coast"}};
        for (String[] r : border) arts.add(new Models.Article(reg.byId(r[0]), r[1], "", "https://t.test/" + r[0] + "/border", null, t));
        for (String[] r : quake) arts.add(new Models.Article(reg.byId(r[0]), r[1], "", "https://t.test/" + r[0] + "/quake", null, t));
        arts.add(new Models.Article(reg.byId("cbs"), "Local bakery wins national pastry award", "", "https://t.test/cbs/bakery", null, t));

        List<Models.Story> stories = new Clusterer().cluster(arts);
        if (args.length > 0) stories.forEach(s -> System.out.println("    " + s.sourceCount() + " " + s.articles().stream().map(x -> x.source().id()).toList()));
        check(stories.size() == 2, "two stories clustered (got " + stories.size() + ")");
        Models.Story borderStory = stories.stream().filter(s -> s.title().toLowerCase().contains("border")).findFirst().orElse(null);
        Models.Story quakeStory = stories.stream().filter(s -> s.title().toLowerCase().contains("earthquake")).findFirst().orElse(null);
        check(borderStory != null && borderStory.sourceCount() == 5, "border story has 5 outlets");
        check(borderStory != null && "right".equals(borderStory.blindspot()), "border story is blindspot for right");
        check(quakeStory != null && quakeStory.sourceCount() == 4 && quakeStory.blindspot() == null, "quake story balanced, no blindspot");
        check(quakeStory != null && quakeStory.category().equals("Environment"), "quake categorized Environment (got " + (quakeStory == null ? "-" : quakeStory.category()) + ")");

        // JSON escaping.
        check(Json.write(java.util.Map.of("k", "a\"<b>\n")).equals("{\"k\":\"a\\\"\\u003cb>\\n\"}"), "json escaping");

        summarizerTests(reg);
        regionTests(reg);
        categoryRegressionTests(reg);
        feedRobustnessTests();

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void summarizerTests(SourceRegistry reg) throws Exception {
        System.out.println("  -- summarizer");
        // JSON parser
        Object j = MiniJson.parse("{\"a\":[1,2.5,\"x\\n\\u0041\"],\"b\":{\"c\":true,\"d\":null}}");
        check(MiniJson.list(MiniJson.cast(j).get("a")).get(2).equals("x\nA"), "minijson parses escapes");
        check(MiniJson.firstObject("Sure:\n```json\n{\"k\":1}\n```").get("k").equals(1.0), "minijson extracts fenced object");
        boolean threw = false;
        try { MiniJson.parse("{\"a\":}"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "minijson rejects malformed input");

        // Story fixture: casualty figures appear only in some sources.
        Instant t = Instant.now();
        List<Models.Article> arts = List.of(
            new Models.Article(reg.byId("bbc"), "Airstrike near Sanaa kills 44, Houthi officials say",
                "Houthi health officials said 44 people were killed and 40 wounded in al-Shariya.", "https://t.test/bbc/y", null, t),
            new Models.Article(reg.byId("foxnews"), "Houthis blame Saudi Arabia for deadly strike near Yemeni capital",
                "A Houthi spokesperson blamed Saudi Arabia for the attack.", "https://t.test/fox/y", null, t),
            new Models.Article(reg.byId("guardian"), "Children among dead in Yemen airstrike",
                "Rescue teams searched for missing people, raising fears the toll could rise.", "https://t.test/gdn/y", null, t));
        Models.Story story = StoryBuilder.build(arts, null, null);
        List<Models.Article> prompt = Summarizer.promptArticles(story);
        check(prompt.get(0).source().id().equals("bbc"), "center source leads the prompt");
        String sys = Summarizer.systemPrompt();
        check(sys.contains("ONLY facts stated") && sys.contains("strictly as data"), "prompt carries grounding and injection rules");

        int bbc = prompt.indexOf(arts.get(0)) + 1, fox = prompt.indexOf(arts.get(1)) + 1, gdn = prompt.indexOf(arts.get(2)) + 1;
        String model = "{\"bullets\":["
            + "{\"text\":\"Houthi health officials said 44 people were killed and 40 wounded.\",\"sources\":[\"S" + bbc + "\"]},"
            + "{\"text\":\"A Houthi spokesperson blamed Saudi Arabia for the attack.\",\"sources\":[\"S" + fox + "\"]},"
            + "{\"text\":\"The war has lasted 11 years.\",\"sources\":[\"S" + bbc + "\"]},"
            + "{\"text\":\"Rescue teams kept searching, raising fears the toll could rise.\",\"sources\":[\"S" + gdn + "\"]},"
            + "{\"text\":\"Unsourced claim.\",\"sources\":[\"S9\"]},"
            + "{\"text\":\"The strike killed 44 people.\",\"sources\":[\"S" + fox + "\"]}],"
            + "\"framing\":{\"left\":\"Focused on the search for survivors.\",\"center\":\"Reported the toll of 44 citing Houthi officials.\",\"right\":\"Emphasized 300 Saudi strikes.\"}}";
        Summarizer sz = new Summarizer("mercury-3", (a, b) -> model, 10, 1);
        Summarizer.Summary sum = sz.generate(story);
        check(sum != null && sum.bullets().size() == 3, "3 grounded bullets kept (got " + (sum == null ? 0 : sum.bullets().size()) + ")");
        check(sum != null && sum.bullets().stream().noneMatch(b -> b.text().contains("11 years")), "number absent from sources rejected");
        check(sum != null && sum.bullets().stream().noneMatch(b -> b.text().contains("Unsourced")), "invalid source label rejected");
        check(sum != null && sum.bullets().stream().noneMatch(b -> b.text().equals("The strike killed 44 people.")),
              "number cited to a source that lacks it rejected");
        check(sum != null && sum.framing().containsKey("left") && sum.framing().containsKey("center")
              && !sum.framing().containsKey("right"), "framing kept only where grounded");
        check(new Summarizer("m", (a, b) -> "{\"bullets\":[{\"text\":\"Only one.\",\"sources\":[\"S1\"]}]}", 1, 1).generate(story) == null,
              "summary with fewer than 2 bullets discarded");
        Summarizer noJson = new Summarizer("m", (a, b) -> "I cannot help with that.", 1, 1);
        check(noJson.generate(story) == null, "non-JSON reply discarded");
        check(noJson.rejectionCounts().getOrDefault("no_json_object", 0) == 1, "rejection reason recorded");
        check(sz.rejectionCounts().getOrDefault("bullet_number_not_in_sources", 0) == 2
              && sz.rejectionCounts().getOrDefault("bullet_without_valid_source", 0) == 1, "bullet rejection reasons counted " + sz.rejectionCounts());
        check(Summarizer.numbersSupported("About 1,200 displaced", List.of(new Models.Article(reg.byId("bbc"), "1200 displaced", "", "https://t.test/n", null, t))),
              "thousands separators normalized");

        // Cache: second run makes no API calls; changed article set invalidates the key.
        int[] calls = {0};
        Summarizer counting = new Summarizer("mercury-3", (a, b) -> { calls[0]++; return model; }, 10, 2);
        Map<String, Summarizer.Summary> first = counting.summarize(List.of(story), new java.util.HashMap<>());
        Map<String, Summarizer.Summary> cache = new java.util.HashMap<>();
        first.values().forEach(x -> cache.put(x.key(), x));
        counting.summarize(List.of(story), cache);
        check(calls[0] == 1, "unchanged story served from cache (api calls " + calls[0] + ")");
        List<Models.Article> more = new ArrayList<>(arts);
        more.add(new Models.Article(reg.byId("nypost"), "Yemen strike toll 44", "", "https://t.test/nyp/y", null, t));
        check(!Summarizer.cacheKey(StoryBuilder.build(more, null, null)).equals(Summarizer.cacheKey(story)), "new article changes cache key");

        // Serialization round trip.
        String js = Json.write(Map.of("items", List.of(Summarizer.toJson(sum))));
        Map<String, Summarizer.Summary> back = Summarizer.fromJsonList(MiniJson.parse(js), "mercury-3");
        check(back.size() == 1 && back.values().iterator().next().bullets().size() == 3, "summary cache round trip");
        check(Summarizer.fromJsonList(MiniJson.parse(js), "other-model").isEmpty(), "cache ignores entries from another model");

        // HTTP transport against a local mock of the chat completions API (429 then 200).
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        int[] hits = {0};
        String[] auth = {null}, sent = {null};
        srv.createContext("/v1/chat/completions", ex -> {
            hits[0]++;
            auth[0] = ex.getRequestHeaders().getFirst("Authorization");
            sent[0] = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] out;
            if (hits[0] == 1) {
                ex.getResponseHeaders().set("Retry-After", "1");
                out = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(429, out.length);
            } else if (hits[0] == 2) {
                // Reasoning consumed the budget: no content, finish_reason "length".
                Map<String, Object> msg = new java.util.HashMap<>();
                msg.put("role", "assistant");
                msg.put("content", null);
                msg.put("reasoning_content", "thinking...");
                out = Json.write(Map.of("choices", List.of(Map.of("message", msg, "finish_reason", "length"))))
                        .getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
            } else {
                // Content returned as an array of parts.
                out = Json.write(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("role", "assistant",
                        "content", List.of(Map.of("type", "text", "text", model.substring(0, 40)), Map.of("type", "text", "text", model.substring(40))))))))
                        .getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, out.length);
            }
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/chat/completions";
            Summarizer live = new Summarizer("mercury-3", new Summarizer.HttpTransport(url, "test-key", "mercury-3"), 5, 1);
            Summarizer.Summary viaHttp = live.generate(story);
            check(viaHttp != null && viaHttp.bullets().size() == 3, "http transport end to end");
            check(hits[0] == 3, "429 retried, then empty length-limited reply retried (hits " + hits[0] + ")");
            check(Double.valueOf(4000).equals(MiniJson.cast(MiniJson.parse(sent[0])).get("max_tokens")), "token budget doubled after length cut-off");
            check("Bearer test-key".equals(auth[0]), "bearer auth header sent");
            Map<String, Object> req = MiniJson.cast(MiniJson.parse(sent[0]));
            check("mercury-3".equals(req.get("model")) && Double.valueOf(0.5).equals(req.get("temperature")), "request model and temperature");
        } finally {
            srv.stop(0);
        }
    }

    private static void regionTests(SourceRegistry reg) throws Exception {
        System.out.println("  -- regions and international outlets");
        java.util.Set<String> ids = new java.util.HashSet<>();
        boolean allValid = true;
        for (Models.Source s : reg.all()) {
            allValid &= ids.add(s.id()) && Regions.ALL.contains(s.region())
                && (s.feedUrl().isEmpty() || s.feedUrl().startsWith("http"));
        }
        check(reg.all().size() >= 85 && allValid, "registry loads " + reg.all().size() + " outlets with unique ids and valid regions");
        check(reg.byId("reliefweb") != null && reg.byId("unnews") != null, "ReliefWeb and UN News registered");
        check("unrated".equals(reg.byId("dawn").group()) && "Global".equals(reg.byId("reliefweb").region()), "international outlets unrated, UN feeds global");

        check(Regions.eventRegion("Houthi rebels say 44 killed in airstrike near Yemeni capital Sanaa").equals(Regions.MENA), "region: Yemen -> MENA");
        check(Regions.eventRegion("Russia launches drones at Kyiv overnight, Ukrainian officials say").equals(Regions.EURASIA), "region: Ukraine war -> Russia & Eurasia");
        check(Regions.eventRegion("RSF fighters advance in Darfur as Sudan famine deepens").equals(Regions.SSA), "region: Sudan -> Sub-Saharan Africa");
        check(Regions.eventRegion("Senate passes spending bill").equals(Regions.NORTH_AMERICA), "region: US domestic -> North America");
        check(Regions.eventRegion("Local bakery wins national pastry award").equals(Regions.UNSPECIFIED), "region: no place names -> Unspecified");
        check(Regions.eventRegion("Leaders from China, Brazil, India and France meet; Chinese, Brazilian, Indian and French envoys sign accord").equals(Regions.GLOBAL),
              "region: many regions -> Global");
        check(Regions.eventRegion("Niger junta expels envoy").equals(Regions.SSA) && Regions.eventRegion("Nigeria votes").equals(Regions.SSA),
              "whole-word matching (Niger vs Nigeria)");

        Instant t = Instant.now();
        List<Models.Article> arts = List.of(
            new Models.Article(reg.byId("nyt"), "Airstrike near Sanaa kills 44", "", "https://t.test/a", null, t),
            new Models.Article(reg.byId("vox"), "Yemen airstrike kills dozens", "", "https://t.test/b", null, t),
            new Models.Article(reg.byId("dawn"), "Yemen airstrike toll rises", "", "https://t.test/c", null, t),
            new Models.Article(reg.byId("arabnews"), "Houthis report airstrike deaths", "", "https://t.test/d", null, t),
            new Models.Article(reg.byId("reliefweb"), "Yemen: airstrike displaces families", "", "https://t.test/e", null, t));
        Models.Story st = StoryBuilder.build(arts, null, null);
        check(st.unrated() == 3 && st.rated() == 2 && st.sourceCount() == 5, "unrated counted separately (" + st.unrated() + " unrated, " + st.rated() + " rated)");
        check(st.pct(st.left()) == 100, "L/C/R percentages use rated outlets only");
        check(st.blindspot() == null, "no blindspot from fewer than 3 rated outlets");
        check(st.eventRegion().equals(Regions.MENA), "story event region inferred (got " + st.eventRegion() + ")");
        check(st.category().equals("Security & Conflict"), "airstrike story categorized Security & Conflict (got " + st.category() + ")");

        String reply = "{\"bullets\":[{\"text\":\"An airstrike near Sanaa killed 44 people.\",\"sources\":[\"S1\",\"S2\"]},"
            + "{\"text\":\"The Yemen airstrike toll rose.\",\"sources\":[\"S3\"]}],"
            + "\"framing\":{\"left\":\"Reported the toll.\",\"international\":\"Regional outlets emphasized displacement.\",\"right\":\"Should be dropped.\"}}";
        List<Models.Article> prompt = Summarizer.promptArticles(st);
        String user = Summarizer.userPrompt(st, prompt);
        check(user.contains("side: international") && user.contains("based in: Sub-Saharan Africa") == false && user.contains("based in: South Asia"),
              "prompt labels international sources with their region");
        // Order sources so S1/S2 are the NYT/Vox items for a deterministic check.
        Summarizer sz = new Summarizer("mercury-3", (a, b) -> reply, 5, 1);
        Summarizer.Summary sum = sz.validate(st, arts, reply);
        check(sum != null && "Regional outlets emphasized displacement.".equals(sum.framing().get("international"))
              && !sum.framing().containsKey("right"), "international framing kept, absent side dropped");
    }

    private static void categoryRegressionTests(SourceRegistry reg) {
        Instant t = Instant.now();
        java.util.function.Function<String[], String> cat = titles -> {
            List<Models.Article> a = new ArrayList<>();
            int i = 0;
            for (String h : titles) a.add(new Models.Article(reg.byId(i++ % 2 == 0 ? "bbc" : "dawn"), h, h, "https://t.test/c" + i, null, t));
            return StoryBuilder.build(a, null, null).category();
        };
        check(cat.apply(new String[]{"Yemen strike near Sanaa kills 44 as tensions rise", "Houthis blame Saudi Arabia for deadly strike"}).equals("Security & Conflict"),
              "military strike not filed as Business");
        check(cat.apply(new String[]{"China and Philippines trade accusations over South China Sea collision", "Philippines, China clash at sea"}).equals("Security & Conflict"),
              "sea clash with 'trade accusations' filed as Security & Conflict");
        check(cat.apply(new String[]{"Auto workers' labor strike enters third week", "Union walkout hits car plants"}).equals("Business"),
              "labor strike still filed as Business");
    }

    private static void feedRobustnessTests() throws Exception {
        System.out.println("  -- feed robustness");
        String now = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.now().atOffset(ZoneOffset.UTC));
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><rss><channel><item><title>UN warns of famine in Sudan</title>"
                + "<link>https://t.test/un/1</link><pubDate>" + now + "</pubDate></item></channel></rss>";
        java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
        try (var out = new java.util.zip.GZIPOutputStream(gz)) { out.write(xml.getBytes(StandardCharsets.UTF_8)); }
        byte[] cp1252 = ("<rss><channel><item><title>Caf\u00e9 talks \u2019collapse\u2019</title><link>https://t.test/w/1</link>"
                + "<pubDate>" + now + "</pubDate></item></channel></rss>").getBytes("windows-1252");

        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] out; int code = 200;
            switch (path) {
                case "/gzip-header" -> { ex.getResponseHeaders().set("Content-Encoding", "gzip"); out = gz.toByteArray(); }
                case "/gzip-silent" -> out = gz.toByteArray();
                case "/cp1252" -> { ex.getResponseHeaders().set("Content-Type", "application/rss+xml"); out = cp1252; }
                case "/ok" -> out = xml.getBytes(StandardCharsets.UTF_8);
                default -> { code = 404; out = "not found".getBytes(StandardCharsets.UTF_8); }
            }
            ex.sendResponseHeaders(code, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            FeedFetcher ff = new FeedFetcher();
            java.util.function.Function<String, Models.Source> src = url ->
                new Models.Source("t", "Test", "Unrated", "Unrated", "Private", "o", "INT", base, url, "Global");
            check(ff.fetch(src.apply(base + "/gzip-header")).get(0).title().contains("famine"), "gzip body with Content-Encoding header parsed");
            check(ff.fetch(src.apply(base + "/gzip-silent")).get(0).title().contains("famine"), "gzip body without header detected by magic bytes");
            String t = ff.fetch(src.apply(base + "/cp1252")).get(0).title();
            check(t.equals("Caf\u00e9 talks \u2019collapse\u2019"), "windows-1252 feed without declaration decoded (" + t + ")");
            check(ff.fetch(src.apply(base + "/missing | " + base + "/ok")).size() == 1, "fallback feed URL used after 404");
            boolean threw = false;
            try { ff.fetch(src.apply(base + "/missing")); } catch (IllegalStateException e) { threw = e.getMessage().contains("404"); }
            check(threw, "single failing URL still reports HTTP status");
        } finally {
            srv.stop(0);
        }
    }

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + name);
        if (!ok) failures++;
    }
}
