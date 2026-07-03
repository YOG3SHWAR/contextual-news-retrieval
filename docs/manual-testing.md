# Manual Testing Guide

A step-by-step walkthrough of every requirement and exactly how to test it by hand
against the running service. Requirements trace to
[`../plan/requirements.md`](../plan/requirements.md); design to
[`../plan/design.md`](../plan/design.md).

## Postman collection to use

Import these two files (Postman → **Import**):

| File | What it is |
|---|---|
| [`../postman/contextual-news.postman_collection.json`](../postman/contextual-news.postman_collection.json) | **The collection to run** — 19 requests in 6 folders (Retrieval R1–R5, Trending R6, LLM Query R7, Enrichment R8, Validation & Errors, Actuator), each with built-in test assertions. |
| [`../postman/local.postman_environment.json`](../postman/local.postman_environment.json) | The environment — sets `baseUrl` (http://localhost:8080) and `lat`/`lon` (Mumbai 19.07, 72.88). |

How to run:

1. Import both files.
2. Select the **"Contextual News - Local"** environment (top-right dropdown).
3. Start the service (see §0 below).
4. Run a single request, or use the **Collection Runner** on the whole
   "Contextual News Retrieval System" collection for pass/fail on every check.

Notes:
- Works with **no API key** (heuristic mode) — expect `degraded:true` and
  `llm_summary:null`. The one request labelled "needs API key" and the non-null
  summary assertions only fully pass once `ANTHROPIC_API_KEY` is set.
- If your service runs elsewhere, change the `baseUrl` environment variable.

---

## 0. Start the service (one-time setup)

**Step 1 — start infra and the app:**

```bash
cd contextual-news-retrieval
cp .env.example .env            # leave ANTHROPIC_API_KEY blank = heuristic mode
docker compose up -d            # Postgres/PostGIS + Redis
set -a && source .env && set +a
mvn spring-boot:run             # loads 2000 articles + seeds ~5000 trending events
```

**Step 2 — wait for readiness, then sanity-check:**

```bash
curl -s localhost:8080/actuator/health   # {"status":"UP",...}
```

**Step 3 — choose how to test:**

- **Postman (recommended):** import the two files from `postman/`, select the
  **"Contextual News - Local"** environment, then open the
  **"Contextual News Retrieval System"** collection and either run a single
  request or click **Run** (Collection Runner) to execute all 19 requests with
  their assertions. See [Postman collection to use](#postman-collection-to-use)
  above for the exact files and steps.
- **curl:** use the per-requirement commands below.

Base path for everything below: `localhost:8080/api/v1/news`. Tip: pipe to `| jq`
for readable JSON.

> **Coordinates matter.** The seed data is all in western/central India
> (lat 15.6–22.5, lon 72.6–80.9), so geo tests must use Indian coordinates —
> e.g. Mumbai `19.07,72.88`.

---

## The requirements, one by one

### Goal (requirements §1)

Load ~2000 articles, retrieve via 5 ranked strategies + trending, use an LLM to
route natural-language queries, and enrich every result with a summary — all
behind a consistent JSON envelope.

Test that ingestion worked:

```bash
docker exec news-postgres psql -U news -d news -c "SELECT count(*) FROM articles;"
# expect: 2000
```

### R1 — `/category` (filter by category, newest first)

Category match is case-insensitive; ranked by `publication_date` desc.

```bash
curl -s "localhost:8080/api/v1/news/category?category=world&limit=3" | jq
```

Expect: 200; up to 3 articles, each `category` contains "world"; `publication_date`
descending; envelope has `articles/total/query/degraded`. Try `category=DEFENCE` or
`category=IPL_2025` to prove case-insensitivity on the noisy labels.

### R2 — `/score` (relevance threshold)

```bash
curl -s "localhost:8080/api/v1/news/score?threshold=0.8&limit=5" | jq '.articles[].relevance_score'
```

Expect: 200; every `relevance_score >= 0.8`, sorted descending.

### R3 — `/search` (full-text, blended ranking)

Blends Postgres `ts_rank` (0.6) + `relevance_score` (0.4), with an ILIKE fallback.

```bash
curl -s "localhost:8080/api/v1/news/search?query=government%20policy&limit=5" | jq '.articles[].title'
```

Expect: 200; results whose title/description match the terms.

### R4 — `/source` (filter by source, case-insensitive)

```bash
curl -s "localhost:8080/api/v1/news/source?source=news18&limit=5" | jq '.articles[].source_name'
```

Expect: 200; all `source_name == "News18"` even though you queried lowercase `news18`.

### R5 — `/nearby` (geospatial radius, nearest first)

PostGIS `ST_DWithin`/`ST_Distance`; returns `distance_km`.

```bash
curl -s "localhost:8080/api/v1/news/nearby?lat=19.07&lon=72.88&radius=50&limit=5" | jq '.articles[] | {title, distance_km}'
```

Expect: 200; each article has `distance_km`, all `<= 50`, ascending. Shrink
`radius=1` → fewer/zero results; that proves the radius filter.

### R6 — `/trending` (location-aware, cached)

Score = Σ `typeWeight · e^(-λ·Δt) · geoFactor` (6h half-life), cached per geohash
prefix (~60s TTL).

```bash
curl -s "localhost:8080/api/v1/news/trending?lat=19.07&lon=72.88&limit=5" | jq '.total'
```

Expect: 200 with results near seeded cities (Mumbai/Pune/Hyderabad/Nagpur/Vijayawada).
A far-away point returns empty:

```bash
curl -s "localhost:8080/api/v1/news/trending?lat=0&lon=-40" | jq '.total'   # 0
```

Cache check: call the Mumbai one twice quickly — identical ordering (2nd served
from cache).

### R7 — `/query` (LLM-routed natural language)

Sends the query to the LLM → `{entities, intent[], keywords}` → routes to the
matching strategy/strategies (multi-intent AND-combined), ranked by the most
specific match.

Deterministic test with no API key (direct-input escape hatch):

```bash
curl -s "localhost:8080/api/v1/news/query?query=world%20news&intent=category&entities=world" | jq '.total'
curl -s "localhost:8080/api/v1/news/query?query=x&intent=source&entities=News18" | jq '.articles[].source_name'
```

Expect: 200; routes exactly like `/category` / `/source` — works with no LLM.

Heuristic routing (no key):

```bash
curl -s "localhost:8080/api/v1/news/query?query=top%20tech%20news%20from%20News18" | jq '.degraded'   # true
```

Real LLM (needs key): set `ANTHROPIC_API_KEY`, restart, then:

```bash
curl -s "localhost:8080/api/v1/news/query?query=Latest%20Elon%20Musk%20Twitter%20news%20near%20Mumbai&lat=19.07&lon=72.88" | jq
```

Expect: `degraded:false` and a populated `llm_summary` per article.

### R8 — LLM enrichment (every response)

Every article carries an `llm_summary` (or `null` + `degraded:true` when the LLM is
unavailable).

```bash
# Heuristic mode (no key): summaries are null, degraded true, still 200
curl -s "localhost:8080/api/v1/news/category?category=sports&limit=2" | jq '.degraded, .articles[].llm_summary'
# expect: true, null, null
```

With a real key you'll see non-null summaries and `degraded:false`. This is the
direct proof of graceful degradation (requirement §3.3 / acceptance #8).

### R9–R11 — Trending internals (simulated events)

- R9 simulated event stream, R10 the decay/geo scoring, R11 per-geohash Redis cache.

```bash
docker exec news-postgres psql -U news -d news -c "SELECT event_type, count(*) FROM user_events GROUP BY event_type;"
# ~5000 events across view/click/share/dwell

docker exec news-redis redis-cli KEYS 'trending:*'   # cache keys appear after a /trending call
```

---

## Response contract & validation (requirements §4–§5)

Uniform success envelope: every 200 has `articles[]`, `total`, `query`, `degraded`.
`distance_km` appears only on `/nearby`.

Error envelope + validation (all return the uniform error body):

```bash
curl -i "localhost:8080/api/v1/news/category"                 # 400 missing required param
curl -i "localhost:8080/api/v1/news/score?threshold=2"        # 400 threshold out of [0,1]
curl -i "localhost:8080/api/v1/news/nearby?lat=200&lon=72"    # 400 lat out of range
curl -s "localhost:8080/api/v1/news/category?category=zzzzz" | jq '.total'          # 200, total:0 (NOT 404)
curl -s "localhost:8080/api/v1/news/score?threshold=0.0&limit=999" | jq '.total'    # <= 50 (limit clamped)
```

Expect the 400s to have `{error, message, status, timestamp, path}` and never a
stack trace. Empty results are always 200, never 404.

---

## Reliability / degradation (requirements §6, acceptance #8)

- **No LLM key** → retrieval still 200, `degraded:true`, `llm_summary:null`
  (already shown above).
- **Redis down** → still 200 (cache is optional):

  ```bash
  docker stop news-redis
  curl -s "localhost:8080/api/v1/news/trending?lat=19.07&lon=72.88" | jq '.total'   # still 200
  docker start news-redis
  ```

- **Postgres down** → 503 with `Retry-After` (core datastore is required):

  ```bash
  docker stop news-postgres
  curl -i "localhost:8080/api/v1/news/category?category=world"   # 503
  docker start news-postgres
  ```

- **Observability:** `curl -s localhost:8080/actuator/prometheus | grep news_cache`
  (cache hit/miss, redis errors); every response echoes an `X-Correlation-Id`
  header (`curl -i …`).

---

## Watching what happens (logs & metrics)

Every request logs one INFO access line (logger `com.inshorts.news.access`) with
method, path, status, latency, and — for result-producing calls — `results`,
`degraded`, and (for `/query`) `intent`/`strategy`, all tagged with the request's
correlation id (also returned in the `X-Correlation-Id` header). To see *why* a
query routed a certain way (extracted intent/entities, resolved filters, executed
SQL, summary source), raise the app log level — no restart needed:

```bash
curl -X POST localhost:8080/actuator/loggers/com.inshorts.news \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
```

Metrics: `curl -s localhost:8080/actuator/prometheus | grep -E 'news_|resilience4j'`.
See the README "Observability & troubleshooting" section for the full reference.

---

## Automated verification (the whole thing at once)

If you'd rather have it all checked automatically against real containers:

```bash
mvn verify   # 45 tests: ingestion, R1–R7 contracts, PostGIS distance, search ranking,
             # LLM fault-injection→degraded, trending score/cache, prompt loading,
             # validation, load smoke
```

Expect `BUILD SUCCESS`, `Tests run: 45, Failures: 0, Errors: 0`.

When you're done: `docker compose down`.
