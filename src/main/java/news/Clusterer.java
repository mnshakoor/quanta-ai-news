package news;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Groups articles from different outlets into stories.
 *
 * Method: each article becomes a TF-IDF vector over normalized title tokens (weight 2)
 * and description tokens (weight 1). Articles are processed newest first and attached
 * to the existing cluster whose centroid has the highest cosine similarity, if that
 * similarity clears THRESHOLD and the two items fall inside a 36-hour window. Otherwise
 * the article seeds a new cluster. Only clusters reported by MIN_SOURCES distinct
 * outlets are published as stories.
 */
final class Clusterer {
    static final double THRESHOLD = 0.30;
    static final int MIN_SOURCES = 2;
    private static final Duration WINDOW = Duration.ofHours(36);

    private static final Set<String> STOP = new HashSet<>(Arrays.asList((
            "the a an and or but of to in on for with at by from as is are was were be been being it its this that these those "
          + "has have had will would could should may might can not no new says said say after over into about than more most "
          + "up out amid what who why how when where which while just also his her their them they he she we you our your "
          + "us u.s live update updates news video watch report reports first year years week day days today time "
          + "here there all some any one two three get gets got make makes made back off down now still being does did "
          + "against before under between during per via vs").split(" ")));

    private static final class Cluster {
        final List<Models.Article> items = new ArrayList<>();
        final Map<String, Double> centroid = new HashMap<>();
        double norm;
        Instant newest;
        Instant oldest;

        void add(Models.Article a, Map<String, Double> vec) {
            items.add(a);
            vec.forEach((k, v) -> centroid.merge(k, v, Double::sum));
            norm = Math.sqrt(centroid.values().stream().mapToDouble(x -> x * x).sum());
            if (newest == null || a.published().isAfter(newest)) newest = a.published();
            if (oldest == null || a.published().isBefore(oldest)) oldest = a.published();
        }
    }

    List<Models.Story> cluster(List<Models.Article> articles) {
        List<Models.Article> sorted = dedupe(articles);
        sorted.sort(Comparator.comparing(Models.Article::published).reversed());

        // Document frequency for IDF.
        Map<Models.Article, Map<String, Integer>> tf = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        for (Models.Article a : sorted) {
            Map<String, Integer> counts = new HashMap<>();
            for (String t : tokens(a.title())) counts.merge(t, 2, Integer::sum);
            for (String t : tokens(a.description())) counts.merge(t, 1, Integer::sum);
            tf.put(a, counts);
            counts.keySet().forEach(t -> df.merge(t, 1, Integer::sum));
        }
        int n = Math.max(1, sorted.size());

        List<Cluster> clusters = new ArrayList<>();
        for (Models.Article a : sorted) {
            Map<String, Double> vec = new HashMap<>();
            tf.get(a).forEach((t, c) -> {
                int d = df.get(t);
                if (d < 2) return;   // a token seen in one document cannot link two, it only dilutes the norm
                vec.put(t, c * Math.log(1.0 + (double) n / d));
            });
            double vn = Math.sqrt(vec.values().stream().mapToDouble(x -> x * x).sum());
            if (vn == 0) continue;

            Cluster best = null;
            double bestSim = 0;
            for (Cluster c : clusters) {
                if (Duration.between(a.published(), c.newest).abs().compareTo(WINDOW) > 0) continue;
                double dot = 0;
                for (Map.Entry<String, Double> e : vec.entrySet()) {
                    Double w = c.centroid.get(e.getKey());
                    if (w != null) dot += w * e.getValue();
                }
                double sim = dot / (vn * c.norm);
                if (sim > bestSim) { bestSim = sim; best = c; }
            }
            if (best != null && bestSim >= THRESHOLD) {
                best.add(a, vec);
            } else {
                Cluster c = new Cluster();
                c.add(a, vec);
                clusters.add(c);
            }
        }

        List<Models.Story> stories = new ArrayList<>();
        for (Cluster c : clusters) {
            long distinct = c.items.stream().map(x -> x.source().id()).distinct().count();
            if (distinct < MIN_SOURCES) continue;
            stories.add(StoryBuilder.build(c.items, null, null));
        }
        stories.sort(Comparator.comparingDouble(Models.Story::score).reversed());
        return stories;
    }

    /** Drops duplicate links and keeps one article per outlet per identical headline. */
    private static List<Models.Article> dedupe(List<Models.Article> in) {
        Map<String, Models.Article> seen = new LinkedHashMap<>();
        for (Models.Article a : in) {
            String key = a.link().replaceAll("[?#].*$", "");
            seen.putIfAbsent(key, a);
        }
        Map<String, Models.Article> byHeadline = new LinkedHashMap<>();
        for (Models.Article a : seen.values()) {
            byHeadline.putIfAbsent(a.source().id() + "|" + a.title().toLowerCase(Locale.ROOT), a);
        }
        return new ArrayList<>(byHeadline.values());
    }

    static List<String> tokens(String text) {
        if (text == null || text.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : text.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
            String t = raw.replace("'s", "").replace("'", "");
            if (t.length() < 3 || STOP.contains(t) || t.chars().allMatch(Character::isDigit)) continue;
            out.add(stem(t));
        }
        return out;
    }

    /** Light suffix stripping, enough to merge plural and tense variants. */
    private static String stem(String t) {
        if (t.length() > 5 && t.endsWith("ies")) return t.substring(0, t.length() - 3) + "y";
        if (t.length() > 5 && t.endsWith("ing")) return t.substring(0, t.length() - 3);
        if (t.length() > 4 && t.endsWith("ed")) return t.substring(0, t.length() - 2);
        if (t.length() > 3 && t.endsWith("s") && !t.endsWith("ss")) return t.substring(0, t.length() - 1);
        return t;
    }

    static String topTerms(List<Models.Article> items, int k) {
        Map<String, Integer> c = new HashMap<>();
        items.forEach(a -> tokens(a.title()).forEach(t -> c.merge(t, 1, Integer::sum)));
        return c.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(k).map(Map.Entry::getKey).collect(Collectors.joining(" "));
    }
}
