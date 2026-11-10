package news;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ReliefWeb API v2 client (https://apidoc.reliefweb.int). Every request needs an app name
 * pre-approved by ReliefWeb, read from RELIEFWEB_APPNAME. The app name travels only in the
 * request URL and is never written to logs or error messages.
 *
 * Strategy: ask for reports flagged as ReliefWeb headlines (the editorially curated items),
 * and fall back to the latest reports if the API rejects the headline query.
 */
final class ReliefWebClient {
    static final String SCHEME = "reliefweb-api";
    private static final int LIMIT = 40;

    private final HttpClient http;
    private final String endpoint;
    private final String appname;

    ReliefWebClient(HttpClient http, String endpoint, String appname) {
        this.http = http;
        this.endpoint = endpoint;
        this.appname = appname;
    }

    static ReliefWebClient fromEnv(HttpClient http) {
        String app = System.getenv("RELIEFWEB_APPNAME");
        if (app == null || app.isBlank()) return null;
        String endpoint = System.getenv("RELIEFWEB_API_URL");
        if (endpoint == null || endpoint.isBlank()) endpoint = "https://api.reliefweb.int/v2/reports";
        return new ReliefWebClient(http, endpoint, app.trim());
    }

    List<Models.Article> fetch(Models.Source source) throws Exception {
        String common = "appname=" + enc(appname) + "&limit=" + LIMIT + "&sort%5B%5D=" + enc("date.created:desc")
                + include("title", "url_alias", "url", "date.created", "body", "primary_country.name", "source.shortname");
        String headlines = common + include("headline.title", "headline.summary") + "&filter%5Bfield%5D=headline";
        try {
            List<Models.Article> h = request(source, headlines);
            if (!h.isEmpty()) return h;
        } catch (IllegalStateException e) {
            if (e.getMessage().contains("HTTP 401") || e.getMessage().contains("HTTP 403")) throw e; // app name problem
        }
        return request(source, common);
    }

    private List<Models.Article> request(Models.Source source, String query) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .header("User-Agent", "QuantaAINewsReader/1.0")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("ReliefWeb API HTTP " + resp.statusCode()
                    + (resp.statusCode() == 403 ? " (app name not approved or not accepted)" : ""));
        }
        return parse(source, resp.body());
    }

    static List<Models.Article> parse(Models.Source source, String json) {
        List<Models.Article> out = new ArrayList<>();
        for (Object item : MiniJson.list(MiniJson.cast(MiniJson.parse(json)).get("data"))) {
            Map<String, Object> it = MiniJson.cast(item);
            Map<String, Object> f = MiniJson.cast(it.get("fields"));
            Map<String, Object> headline = MiniJson.cast(f.get("headline"));
            String title = FeedFetcher.clean(firstNonBlank(MiniJson.str(headline.get("title")), MiniJson.str(f.get("title"))));
            if (title.isEmpty()) continue;
            String link = firstNonBlank(MiniJson.str(f.get("url_alias")), MiniJson.str(f.get("url")));
            if (link.isEmpty() && it.get("id") != null) link = "https://reliefweb.int/node/" + String.valueOf(it.get("id")).replace(".0", "");
            if (!link.startsWith("http")) continue;
            String desc = FeedFetcher.clean(firstNonBlank(MiniJson.str(headline.get("summary")), MiniJson.str(f.get("body"))));
            if (desc.length() > 420) desc = desc.substring(0, 417).trim() + "...";
            Instant when = Instant.now();
            String created = MiniJson.str(MiniJson.cast(f.get("date")).get("created"));
            if (created != null) {
                try { when = java.time.OffsetDateTime.parse(created).toInstant(); } catch (Exception ignored) { /* keep now */ }
            }
            out.add(new Models.Article(source, title, desc, link, null, when));
        }
        return out;
    }

    private static String include(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) sb.append("&fields%5Binclude%5D%5B%5D=").append(enc(f));
        return sb.toString();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static String firstNonBlank(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return "";
    }
}
