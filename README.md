# Quanta AI News

A live, full-stack news comparison app. It pulls RSS feeds from about 36 outlets across the political spectrum, groups articles about the same event into stories, and shows how each story is covered from left, center, and right. It also flags blindspots, compares headlines side by side, and reports outlet factuality and ownership.

- **Backend:** Java 21, no external dependencies (JDK `HttpServer`, `HttpClient`, DOM XML parser).
- **Frontend:** HTML, CSS, and vanilla JavaScript, served by the backend.

## Hosting on GitHub Pages

GitHub Pages serves static files only, so the Java pipeline runs in GitHub Actions instead of on a server. `.github/workflows/pages.yml` runs every 20 minutes. It compiles the code, runs the self-tests, fetches and clusters the feeds (`news.Export`), writes the result as JSON under `data/`, and deploys `web/` plus `data/` to Pages. The frontend detects that no API is present and reads the JSON snapshot directly.

One-time setup:

1. Push this project to the root of a repository's `main` branch.
2. In the repository, open **Settings > Pages > Build and deployment** and set **Source** to **GitHub Actions**.
3. The push triggers the first deploy. To run it manually: **Actions > Publish to GitHub Pages > Run workflow**.

Behaviour to know:

- **Fail-safe:** if a scheduled run gets fewer than 5 working feeds or no stories, it deploys nothing and the previous snapshot stays live. A push-triggered run, or a manual run with *allow_demo* ticked, publishes the labelled demo dataset instead, so the first deploy always produces a site.
- **Freshness:** GitHub can delay scheduled runs by several minutes, and the Pages CDN caches files for up to about 10 minutes. Expect snapshots to be roughly 20 to 40 minutes old.
- **Scheduled runs pause after 60 days without repository activity** (GitHub policy for public repositories). Re-enable them from the Actions tab.
- **Runner network:** some publishers block cloud IP ranges. Feed health for each outlet appears on the Sources page.
- **Public republication:** the site republishes headlines and short feed summaries with links to the originals, which is standard aggregator practice. Review each publisher's feed terms if the site is used commercially.

## AI summaries (Inception Mercury 3)

Each story can carry 3 to 5 AI-generated bullets plus a one-line note on how left, center and right outlets framed it. They are generated during the build, never in the visitor's browser, so the API key is never exposed.

**Setup:** add your Inception API key as a repository secret named `INCEPTION_API_KEY` (**Settings > Secrets and variables > Actions > New repository secret**). Without the secret, the build runs normally and simply publishes no summaries.

**Grounding rules,** enforced in `Summarizer.java` and covered by `SelfTest`:

1. The model receives only the story's headlines and feed excerpts, labelled S1..Sn, and must cite the labels behind every bullet.
2. A bullet that cites no valid source is dropped.
3. A bullet containing a number absent from its cited sources is dropped. Framing lines get the same check.
4. If fewer than 2 bullets survive, no summary is shown.
5. Contested claims must be attributed to whoever made them, and instructions embedded in feed text are to be ignored.

**Cost control:** summaries are cached by a hash of the prompt version and the story's article links. Each run loads the previous deployment's `data/summaries.json` and re-summarizes only new or changed stories, at most `SUMMARY_MAX_NEW` per run (default 30).

| Variable | Default | Purpose |
|---|---|---|
| `INCEPTION_API_KEY` | (unset) | Enables summaries |
| `SUMMARY_MODEL` | `mercury-3` | Model ID |
| `SUMMARY_ENDPOINT` | `https://api.inceptionlabs.ai/v1/chat/completions` | Any OpenAI-compatible endpoint |
| `SUMMARY_MAX_NEW` | `30` | New summaries per run |
| `SUMMARY_THREADS` | `3` | Concurrent API calls |

The same variables work for the local server: `INCEPTION_API_KEY=... ./run.sh`.

**Limits:** summaries restate what outlets reported and are not fact-checks. The number check catches invented figures but not wrong framing or numbers written as words.

## Run locally

Requires JDK 21 or later.

```bash
./run.sh            # macOS / Linux
run.bat             # Windows
```

Open http://localhost:8080. The first live refresh starts immediately and takes about 10 to 30 seconds. If no feed is reachable, the app serves a clearly labelled fictional demo dataset and keeps retrying on schedule.

Docker:

```bash
docker build -t quanta-ai-news . && docker run -p 8080:8080 quanta-ai-news
```

### Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `REFRESH_MINUTES` | `15` | Feed polling interval (minimum 5) |
| `MAX_AGE_HOURS` | `48` | Drop articles older than this |
| `DEMO_MODE` | `false` | Force the offline demo dataset |
| `APP_HOME` | `.` | Directory holding `config/` and `web/` |

## Features

- **Home:** ranked top stories with coverage bars, topic chips, sort (Top, Latest, Polarized), and "covered by" filters.
- **Story page:** L/C/R and five-point bias distribution, side-by-side headline comparison, full article list by side, factuality mix, ownership breakdown, and first-reporting outlet.
- **Blindspot:** stories covered heavily by one side and barely by the other.
- **Sources:** registry table with ratings, ownership, and live feed health per outlet.
- **My News Bias:** a private reading profile kept in browser storage, never sent to the server.
- **Saved stories, search, light and dark themes, and live update notices.**

## API

| Method | Path | Description |
|---|---|---|
| GET | `/api/meta` | Mode (live or demo), refresh times, totals, categories, method parameters |
| GET | `/api/stories?view=top\|latest\|polarized\|blindspot&bias=&category=&q=&ids=&limit=` | Story list |
| GET | `/api/stories/{id}` | Story detail with all articles |
| GET | `/api/sources` | Outlet registry with feed status |
| POST | `/api/refresh` | Trigger a refresh (rate limited to once per minute) |
| GET | `/api/health` | Liveness |

## Method summary

1. **Clustering:** TF-IDF vectors (headline terms double-weighted). Greedy centroid assignment at cosine of 0.30 or higher within a 36-hour window. At least 2 distinct outlets are required.
2. **Distribution:** each outlet counts once per story.
3. **Blindspot:** at least 3 outlets, one side at 60% or more, the other at 10% or less.
4. **Polarized:** at least 4 outlets, center below 25%, and at least 2 outlets per side.

## Evidence caveats

- The bias, factuality, and ownership values in `config/sources.tsv` are **provisional seeds** approximated from public aggregate charts (AllSides, Ad Fontes Media, Media Bias/Fact Check). Calibrate them against a cited methodology before any analytic or published use.
- Feed availability changes coverage counts. An outlet whose feed fails is invisible to the scorer, which can produce a blindspot that does not reflect actual coverage. Check feed health on the Sources page.
- Keyword clustering can split one event or merge two adjacent ones. Categories come from a keyword lexicon, not from human review.

Build the static site locally (same as the workflow):

```bash
javac -d out $(find src -name '*.java') && mkdir -p site && cp -r web/. site/ && java -cp out news.Export site
```

## Tests

```bash
javac -d out $(find src -name '*.java') && java -cp out news.SelfTest
```

These offline tests cover RSS, Atom, and XXE parsing, clustering, blindspot scoring, categorization, and JSON escaping.

## Layout

```
config/sources.tsv        outlet registry (edit to add or re-rate outlets)
src/main/java/news/       Main (live server + API), Export (static snapshot for Pages), Views (JSON shapes),
                          Summarizer (grounded AI summaries), MiniJson (JSON parser),
                          FeedFetcher, Clusterer, StoryBuilder, StoryStore, DemoData
.github/workflows/        pages.yml (scheduled build and deploy)
src/test/java/news/       SelfTest
web/                      index.html, css/app.css, js/app.js
```
