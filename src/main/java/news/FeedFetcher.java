package news;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches and parses RSS 2.0, RSS 1.0 (RDF) and Atom feeds concurrently. */
final class FeedFetcher {
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern IMG = Pattern.compile("<img[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_ZONED_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm zzz", Locale.ENGLISH));

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();

    record Result(List<Models.Article> articles, Map<String, Models.FeedStatus> status) {}

    Result fetchAll(Collection<Models.Source> sources, Duration maxAge) {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        Map<String, Models.FeedStatus> status = new ConcurrentHashMap<>();
        List<Models.Article> all = Collections.synchronizedList(new ArrayList<>());
        Instant cutoff = Instant.now().minus(maxAge);
        List<Future<?>> futures = new ArrayList<>();
        for (Models.Source s : sources) {
            if (!s.fetchable()) continue;
            futures.add(pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    List<Models.Article> items = fetch(s);
                    items.removeIf(a -> a.published().isBefore(cutoff));
                    all.addAll(items);
                    status.put(s.id(), new Models.FeedStatus(s.id(), true, items.size(), null,
                            Instant.now(), (System.nanoTime() - t0) / 1_000_000));
                } catch (Exception e) {
                    String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                    status.put(s.id(), new Models.FeedStatus(s.id(), false, 0,
                            msg.length() > 200 ? msg.substring(0, 200) : msg,
                            Instant.now(), (System.nanoTime() - t0) / 1_000_000));
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(40, TimeUnit.SECONDS); } catch (Exception ignored) { f.cancel(true); }
        }
        pool.shutdownNow();
        return new Result(new ArrayList<>(all), new HashMap<>(status));
    }

    /** HTTP/1.1-only client, used to retry after HTTP/2 stream resets (RST_STREAM, GOAWAY). */
    private final HttpClient http11 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxySelector.getDefault())
            .build();

    /**
     * Fetches a source's feed. feedUrl may list fallbacks separated by " | "; they are tried in
     * order and the first that parses wins. The User-Agent identifies this reader honestly; a
     * publisher that refuses it (HTTP 403) is reported as failing, not worked around.
     */
    List<Models.Article> fetch(Models.Source s) throws Exception {
        Exception last = null;
        for (String url : s.feedUrl().split("\\s*\\|\\s*")) {
            if (url.isBlank()) continue;
            try {
                return parse(s, download(url.trim()));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("no feed URL") : last;
    }

    private byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (compatible; QuantaAINewsReader/1.0; RSS aggregator)")
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8")
                .header("Accept-Encoding", "gzip, deflate")
                .GET().build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            resp = http11.send(req, HttpResponse.BodyHandlers.ofByteArray()); // HTTP/2 stream faults
        }
        if (resp.statusCode() >= 400) throw new IllegalStateException("HTTP " + resp.statusCode());
        byte[] body = decompress(resp.body(), resp.headers().firstValue("content-encoding").orElse(""));
        String ctype = resp.headers().firstValue("content-type").orElse("");
        return toUtf8IfNeeded(body, ctype);
    }

    /** Undoes gzip/deflate, detected from the header or from the gzip magic bytes. */
    static byte[] decompress(byte[] body, String encoding) throws java.io.IOException {
        boolean gzipMagic = body.length > 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
        String enc = encoding.toLowerCase(Locale.ROOT);
        if (gzipMagic || enc.contains("gzip")) {
            try (var in = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body))) { return in.readAllBytes(); }
        }
        if (enc.contains("deflate")) {
            try (var in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(body))) { return in.readAllBytes(); }
        }
        return body;
    }

    /**
     * Normalizes to UTF-8 when the XML declares no encoding but the HTTP header names a
     * non-UTF-8 charset, or when the bytes are not valid UTF-8 at all (read as windows-1252).
     */
    static byte[] toUtf8IfNeeded(byte[] body, String contentType) {
        String head = new String(body, 0, Math.min(body.length, 200), java.nio.charset.StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        boolean declared = head.contains("encoding=");
        Matcher m = Pattern.compile("charset=\"?([\\w-]+)").matcher(contentType.toLowerCase(Locale.ROOT));
        java.nio.charset.Charset target = null;
        if (!declared && m.find()) {
            try { target = java.nio.charset.Charset.forName(m.group(1)); } catch (Exception ignored) { /* unknown charset */ }
        }
        if (target == null && !declared && !validUtf8(body)) target = java.nio.charset.Charset.forName("windows-1252");
        if (target == null || target.equals(java.nio.charset.StandardCharsets.UTF_8)) return body;
        return new String(body, target).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean validUtf8(byte[] b) {
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(b));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    static List<Models.Article> parse(Models.Source s, byte[] body) throws Exception {
        int start = 0;
        while (start < body.length && body[start] != '<') start++; // skip BOM and leading junk
        DocumentBuilder db = secureFactory().newDocumentBuilder();
        db.setErrorHandler(null);
        Document doc = db.parse(new ByteArrayInputStream(body, start, body.length - start));

        List<Models.Article> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagNameNS("*", "item");
        boolean atom = items.getLength() == 0;
        if (atom) items = doc.getElementsByTagNameNS("*", "entry");

        for (int i = 0; i < items.getLength() && out.size() < 60; i++) {
            Element el = (Element) items.item(i);
            String title = clean(text(el, "title"));
            if (title.isEmpty()) continue;
            String link = atom ? atomLink(el) : text(el, "link");
            if (link.isEmpty()) link = text(el, "guid");
            link = link.trim();
            if (!link.startsWith("http")) continue;

            String rawDesc = firstNonEmpty(text(el, "description"), text(el, "summary"),
                    text(el, "encoded"), text(el, "content"));
            String desc = clean(rawDesc);
            if (desc.length() > 420) desc = desc.substring(0, 417).trim() + "...";
            if (desc.equalsIgnoreCase(title)) desc = "";

            String image = image(el, rawDesc);
            Instant when = date(firstNonEmpty(text(el, "pubDate"), text(el, "published"),
                    text(el, "updated"), text(el, "date")));
            if (when.isAfter(Instant.now().plusSeconds(3600))) when = Instant.now();
            out.add(new Models.Article(s, title, desc, link, image, when));
        }
        return out;
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f;
    }

    /** Text of the first direct child element with this local name. */
    private static String text(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(local(e))) return e.getTextContent();
        }
        return "";
    }

    private static String local(Element e) {
        return e.getLocalName() != null ? e.getLocalName() : e.getTagName();
    }

    private static String atomLink(Element entry) {
        String fallback = "";
        for (Node n = entry.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && "link".equals(local(e))) {
                String rel = e.getAttribute("rel");
                String href = e.getAttribute("href");
                if (href.isEmpty()) href = e.getTextContent();
                if (rel.isEmpty() || "alternate".equals(rel)) return href;
                if (fallback.isEmpty()) fallback = href;
            }
        }
        return fallback;
    }

    private static String image(Element el, String rawDesc) {
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element e)) continue;
            String name = local(e);
            if (name.equals("content") || name.equals("thumbnail") || name.equals("group")) {
                String url = e.getAttribute("url");
                if (url.startsWith("http") && !e.getAttribute("medium").equals("video")) return url;
                NodeList thumbs = e.getElementsByTagNameNS("*", "thumbnail");
                if (thumbs.getLength() > 0) {
                    String t = ((Element) thumbs.item(0)).getAttribute("url");
                    if (t.startsWith("http")) return t;
                }
                NodeList contents = e.getElementsByTagNameNS("*", "content");
                if (contents.getLength() > 0) {
                    String t = ((Element) contents.item(0)).getAttribute("url");
                    if (t.startsWith("http")) return t;
                }
            }
            if (name.equals("enclosure") && e.getAttribute("type").startsWith("image")) {
                return e.getAttribute("url");
            }
        }
        Matcher m = IMG.matcher(rawDesc == null ? "" : rawDesc);
        if (m.find() && m.group(1).startsWith("http")) return m.group(1);
        String encoded = text(el, "encoded");
        m = IMG.matcher(encoded);
        return m.find() && m.group(1).startsWith("http") ? m.group(1) : null;
    }

    private static Instant date(String s) {
        if (s == null || s.isBlank()) return Instant.now();
        String v = s.trim();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return ZonedDateTime.parse(v, f).toInstant(); } catch (Exception ignored) {}
        }
        try { return Instant.parse(v); } catch (Exception ignored) {}
        return Instant.now();
    }

    static String clean(String html) {
        if (html == null) return "";
        String s = TAG.matcher(html).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
             .replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'")
             .replace("&lt;", "<").replace("&gt;", ">").replace("&#8217;", "'")
             .replace("&#8216;", "'").replace("&#8220;", "\"").replace("&#8221;", "\"")
             .replace("&#8212;", "-").replace("&#8211;", "-").replace("&hellip;", "...")
             .replace("&#8230;", "...");
        s = s.replaceAll("&#\\d+;", "");
        s = WS.matcher(s).replaceAll(" ").trim();
        if (s.endsWith("[...]")) s = s.substring(0, s.length() - 5).trim() + "...";
        return s;
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.isBlank()) return s;
        return "";
    }
}
