package news;

import java.time.Instant;
import java.util.List;

/** Immutable domain records shared across the backend. */
final class Models {
    private Models() {}

    /** A news outlet and its (provisional) ratings. */
    record Source(String id, String name, String bias, String factuality,
                  String ownership, String owner, String country,
                  String homepage, String feedUrl) {

        /** Collapses the five-point scale into left, center, right. */
        String group() {
            return switch (bias) {
                case "Left", "Lean Left" -> "left";
                case "Right", "Lean Right" -> "right";
                default -> "center";
            };
        }

        boolean fetchable() { return feedUrl != null && !feedUrl.isBlank(); }
    }

    /** One article pulled from one outlet's feed. */
    record Article(Source source, String title, String description,
                   String link, String image, Instant published) {}

    /** Live health of a single feed. */
    record FeedStatus(String sourceId, boolean ok, int articles,
                      String error, Instant checkedAt, long millis) {}

    /** A cluster of articles from several outlets about the same event. */
    record Story(String id, String title, String summary, String category,
                 String image, Instant firstSeen, Instant lastUpdated,
                 List<Article> articles, int left, int center, int right,
                 int sourceCount, String blindspot, boolean polarized,
                 double score) {

        int pct(int n) {
            int total = left + center + right;
            return total == 0 ? 0 : (int) Math.round(100.0 * n / total);
        }
    }
}
