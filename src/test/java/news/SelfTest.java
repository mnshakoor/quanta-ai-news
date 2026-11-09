package news;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String name) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + name);
        if (!ok) failures++;
    }
}
