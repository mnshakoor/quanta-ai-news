package news;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Entry point: HTTP server exposing the JSON API and the static web client. */
public final class Main {
    private static final Map<String, String> MIME = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "application/javascript; charset=utf-8", "svg", "image/svg+xml",
            "png", "image/png", "ico", "image/x-icon", "json", "application/json");

    private final SourceRegistry registry;
    private final StoryStore store;
    private final Path webRoot;
    private final int refreshMinutes;
    private volatile Instant lastManualRefresh = Instant.EPOCH;

    private Main(SourceRegistry registry, StoryStore store, Path webRoot, int refreshMinutes) {
        this.registry = registry;
        this.store = store;
        this.webRoot = webRoot.toAbsolutePath().normalize();
        this.refreshMinutes = refreshMinutes;
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        int refresh = Math.max(5, Integer.parseInt(env("REFRESH_MINUTES", "15")));
        int maxAgeHours = Integer.parseInt(env("MAX_AGE_HOURS", "48"));
        boolean demo = Boolean.parseBoolean(env("DEMO_MODE", "false"));
        Path base = Path.of(env("APP_HOME", "."));

        SourceRegistry registry = new SourceRegistry(base.resolve("config/sources.tsv"));
        StoryStore store = new StoryStore(registry, Duration.ofHours(maxAgeHours), demo);
        Main app = new Main(registry, store, base.resolve("web"), refresh);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 64);
        server.createContext("/api/", app::api);
        server.createContext("/", app::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.printf("Quanta AI News running at http://localhost:%d  (%d sources, refresh every %d min%s)%n",
                port, registry.all().size(), refresh, demo ? ", DEMO_MODE" : "");

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();
        sched.scheduleWithFixedDelay(() -> {
            store.refresh();
            store.setNextRefresh(Instant.now().plus(Duration.ofMinutes(refresh)));
        }, 0, refresh, TimeUnit.MINUTES);
    }

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    // ------------------------------------------------------------------ API

    private void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) { send(ex, 204, "", "text/plain"); return; }

            if (path.equals("/api/health")) {
                json(ex, 200, Map.of("status", "ok", "time", Instant.now()));
            } else if (path.equals("/api/meta") && method.equals("GET")) {
                json(ex, 200, views().meta());
            } else if (path.equals("/api/stories") && method.equals("GET")) {
                json(ex, 200, views().listStories(q));
            } else if (path.startsWith("/api/stories/") && method.equals("GET")) {
                String id = path.substring("/api/stories/".length());
                Models.Story s = store.current().byId().get(id);
                if (s == null) json(ex, 404, Map.of("error", "Story not found. It may have expired in a refresh."));
                else json(ex, 200, views().storyDetail(s));
            } else if (path.equals("/api/sources") && method.equals("GET")) {
                json(ex, 200, views().sources());
            } else if (path.equals("/api/refresh") && method.equals("POST")) {
                if (Duration.between(lastManualRefresh, Instant.now()).toSeconds() < 60 || store.isRefreshing()) {
                    json(ex, 429, Map.of("error", "A refresh ran less than a minute ago or is in progress."));
                } else {
                    lastManualRefresh = Instant.now();
                    Thread.startVirtualThread(store::refresh);
                    json(ex, 202, Map.of("status", "refreshing"));
                }
            } else {
                json(ex, 404, Map.of("error", "Unknown endpoint"));
            }
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, Map.of("error", "Internal error"));
        }
    }

    private Views views() {
        return new Views(registry, store.current(), store.nextRefresh(), store.isRefreshing(), refreshMinutes);
    }

    // ------------------------------------------------------------------ static files

    private void staticFile(HttpExchange ex) throws IOException {
        String raw = URLDecoder.decode(ex.getRequestURI().getPath(), StandardCharsets.UTF_8);
        if (raw.equals("/") || raw.isEmpty()) raw = "/index.html";
        Path file = webRoot.resolve(raw.substring(1)).normalize();
        if (!file.startsWith(webRoot) || !Files.isRegularFile(file)) {
            file = webRoot.resolve("index.html"); // client-side routes fall back to the shell
        }
        String name = file.getFileName().toString();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Cache-Control", ext.equals("html") ? "no-cache" : "public, max-age=300");
        send(ex, 200, body, MIME.getOrDefault(ext, "application/octet-stream"));
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i <= 0) continue;
            m.put(URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8),
                  URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return s == null ? def : Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private static void json(HttpExchange ex, int code, Object body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, code, Json.write(body), "application/json; charset=utf-8");
    }

    private static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        send(ex, code, body.getBytes(StandardCharsets.UTF_8), type);
    }

    private static void send(HttpExchange ex, int code, byte[] body, String type) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (code == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        }
        ex.close();
    }
}
