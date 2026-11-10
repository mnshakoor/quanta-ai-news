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
        check(new Summarizer("m", (a, b) -> "I cannot help with that.", 1, 1).generate(story) == null, "non-JSON reply discarded");
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
            } else {
                out = Json.write(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", model)))))
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
            check(hits[0] == 2, "429 retried once (hits " + hits[0] + ")");
            check("Bearer test-key".equals(auth[0]), "bearer auth header sent");
            Map<String, Object> req = MiniJson.cast(MiniJson.parse(sent[0]));
            check("mercury-3".equals(req.get("model")) && Double.valueOf(0.5).equals(req.get("temperature")), "request model and temperature");
        } finally {
            srv.stop(0);
        }
    }

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + name);
        if (!ok) failures++;
    }
}
