package news;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Static snapshot exporter for GitHub Pages.
 *
 * Usage: java -cp out news.Export <outputDir>
 * Writes <outputDir>/data/{meta,stories,sources}.json and data/stories/<id>.json.
 *
 * Fail-safe: when live collection is degraded (no stories, or fewer than MIN_FEEDS feeds
 * online) it exits with code 2 and writes nothing, so the workflow skips deployment and
 * the last good snapshot stays live. Set ALLOW_DEMO=true to publish the labelled demo
 * dataset instead (useful for a first deploy), or DEMO_MODE=true to force it.
 */
public final class Export {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "site");
        Path base = Path.of(env("APP_HOME", "."));
        int refresh = Integer.parseInt(env("REFRESH_MINUTES", "20"));
        int minFeeds = Integer.parseInt(env("MIN_FEEDS", "5"));
        boolean demo = Boolean.parseBoolean(env("DEMO_MODE", "false"));
        boolean allowDemo = Boolean.parseBoolean(env("ALLOW_DEMO", "false"));

        SourceRegistry registry = new SourceRegistry(base.resolve("config/sources.tsv"));
        StoryStore store = new StoryStore(registry, Duration.ofHours(Integer.parseInt(env("MAX_AGE_HOURS", "48"))), demo);
        seedPreviousSummaries(store);
        store.refresh();
        StoryStore.Snapshot snap = store.current();
        long feedsOk = snap.feeds().values().stream().filter(Models.FeedStatus::ok).count();

        boolean degraded = snap.mode().equals("demo") || feedsOk < minFeeds;
        if (degraded && !demo && !allowDemo) {
            System.err.printf("[export] degraded run (mode=%s, %d feeds ok, minimum %d). Nothing written; "
                    + "the previous deployment stays live.%n", snap.mode(), feedsOk, minFeeds);
            System.exit(2);
        }

        Views v = new Views(registry, snap, Instant.now().plus(Duration.ofMinutes(refresh)), false, refresh);
        Path data = out.resolve("data");
        Path detail = data.resolve("stories");
        Files.createDirectories(detail);

        Map<String, Object> meta = v.meta();
        meta.put("static", true);
        write(data.resolve("meta.json"), meta);
        write(data.resolve("stories.json"), v.listStories(Map.of("view", "top", "limit", "200")));
        write(data.resolve("sources.json"), v.sources());
        java.util.List<Object> sums = new java.util.ArrayList<>();
        snap.summaries().values().forEach(x -> sums.add(Summarizer.toJson(x)));
        write(data.resolve("summaries.json"), Map.of("promptVersion", Summarizer.PROMPT_VERSION, "items", sums));
        int n = 0;
        for (Models.Story s : snap.stories()) {
            write(detail.resolve(s.id() + ".json"), v.storyDetail(s));
            if (++n >= 200) break;
        }
        System.out.printf("[export] wrote %d stories (mode=%s, %d articles, %d feeds ok) to %s%n",
                n, snap.mode(), snap.articleCount(), feedsOk, data.toAbsolutePath());
    }

    /**
     * Seeds the summary cache from the previous deployment (PREVIOUS_SUMMARIES_URL) or a local
     * file (PREVIOUS_SUMMARIES_FILE), so unchanged stories are not re-summarized every run.
     */
    private static void seedPreviousSummaries(StoryStore store) {
        Summarizer sz = store.summarizer();
        if (sz == null) {
            System.out.println("[summary] INCEPTION_API_KEY not set; AI summaries disabled for this run");
            return;
        }
        String url = env("PREVIOUS_SUMMARIES_URL", "");
        String file = env("PREVIOUS_SUMMARIES_FILE", "");
        try {
            String body = null;
            if (!file.isEmpty() && Files.isRegularFile(Path.of(file))) {
                body = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            } else if (url.startsWith("https://")) {
                var http = java.net.http.HttpClient.newBuilder()
                        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(10))
                        .proxy(java.net.ProxySelector.getDefault()).build();
                var resp = http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "?t=" + System.currentTimeMillis()))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) body = resp.body();
            }
            if (body == null) {
                System.out.println("[summary] no previous summaries found; starting with an empty cache");
                return;
            }
            Map<String, Summarizer.Summary> prev = Summarizer.fromJsonList(MiniJson.parse(body), sz.model());
            store.seedSummaries(prev);
            System.out.println("[summary] seeded " + prev.size() + " cached summaries from previous deployment");
        } catch (Exception e) {
            System.out.println("[summary] could not load previous summaries (" + e.getClass().getSimpleName() + "); continuing");
        }
    }

    private static void write(Path p, Object o) throws IOException {
        Files.writeString(p, Json.write(o), StandardCharsets.UTF_8);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
