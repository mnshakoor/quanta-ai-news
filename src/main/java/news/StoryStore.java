package news;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Holds the current snapshot of stories and refreshes it from live feeds. */
final class StoryStore {
    record Snapshot(List<Models.Story> stories, Map<String, Models.Story> byId,
                    Map<String, Models.FeedStatus> feeds, int articleCount,
                    String mode, Instant refreshedAt,
                    Map<String, Summarizer.Summary> summaries) {}

    private final SourceRegistry registry;
    private final FeedFetcher fetcher = new FeedFetcher();
    private final Clusterer clusterer = new Clusterer();
    private final Duration maxAge;
    private final boolean forceDemo;
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile Snapshot snapshot;
    private volatile Instant nextRefresh = Instant.now();
    private final Summarizer summarizer = Summarizer.fromEnv();
    /** Summary cache keyed by Summarizer.cacheKey; seeded from the previous deployment when available. */
    private final Map<String, Summarizer.Summary> summaryCache = new ConcurrentHashMap<>();

    StoryStore(SourceRegistry registry, Duration maxAge, boolean forceDemo) {
        this.registry = registry;
        this.maxAge = maxAge;
        this.forceDemo = forceDemo;
        List<Models.Story> demo = DemoData.stories(registry);
        this.snapshot = snapshot(demo, Map.of(), 0, "demo", Map.of());
    }

    Snapshot current() { return snapshot; }

    Summarizer summarizer() { return summarizer; }

    void seedSummaries(Map<String, Summarizer.Summary> byKey) { summaryCache.putAll(byKey); }

    Instant nextRefresh() { return nextRefresh; }

    boolean isRefreshing() { return refreshing.get(); }

    void setNextRefresh(Instant t) { nextRefresh = t; }

    /** Pulls every feed and rebuilds clusters. Falls back to demo data if nothing arrives. */
    void refresh() {
        if (!refreshing.compareAndSet(false, true)) return;
        try {
            if (forceDemo) {
                snapshot = snapshot(DemoData.stories(registry), Map.of(), 0, "demo", Map.of());
                return;
            }
            long t0 = System.currentTimeMillis();
            FeedFetcher.Result r = fetcher.fetchAll(registry.all(), maxAge);
            long ok = r.status().values().stream().filter(Models.FeedStatus::ok).count();
            List<Models.Story> stories = clusterer.cluster(r.articles());
            if (stories.isEmpty()) {
                System.out.printf("[refresh] no live stories (%d/%d feeds ok, %d articles); serving demo data%n",
                        ok, r.status().size(), r.articles().size());
                snapshot = snapshot(DemoData.stories(registry), r.status(), r.articles().size(), "demo", Map.of());
            } else {
                Map<String, Summarizer.Summary> sums = Map.of();
                if (summarizer != null) {
                    sums = summarizer.summarize(stories, summaryCache);
                    summaryCache.clear();
                    sums.values().forEach(x -> summaryCache.put(x.key(), x));
                }
                snapshot = snapshot(stories, r.status(), r.articles().size(), "live", sums);
                System.out.printf("[refresh] %d stories from %d articles, %d/%d feeds ok, %d ms%n",
                        stories.size(), r.articles().size(), ok, r.status().size(),
                        System.currentTimeMillis() - t0);
            }
        } catch (Exception e) {
            System.err.println("[refresh] failed: " + e);
        } finally {
            refreshing.set(false);
        }
    }

    private static Snapshot snapshot(List<Models.Story> stories, Map<String, Models.FeedStatus> feeds,
                                     int articles, String mode, Map<String, Summarizer.Summary> sums) {
        Map<String, Models.Story> byId = new HashMap<>();
        stories.forEach(s -> byId.put(s.id(), s));
        return new Snapshot(List.copyOf(stories), byId, feeds, articles, mode, Instant.now(), Map.copyOf(sums));
    }
}
