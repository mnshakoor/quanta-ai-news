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
        int n = 0;
        for (Models.Story s : snap.stories()) {
            write(detail.resolve(s.id() + ".json"), v.storyDetail(s));
            if (++n >= 200) break;
        }
        System.out.printf("[export] wrote %d stories (mode=%s, %d articles, %d feeds ok) to %s%n",
                n, snap.mode(), snap.articleCount(), feedsOk, data.toAbsolutePath());
    }

    private static void write(Path p, Object o) throws IOException {
        Files.writeString(p, Json.write(o), StandardCharsets.UTF_8);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }
}
