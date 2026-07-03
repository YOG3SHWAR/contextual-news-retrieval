# Contextual News Retrieval System

A REST API that ingests ~2000 news articles, retrieves them through five ranked
strategies plus a trending feed, uses an LLM (Anthropic Claude) to understand
natural-language queries and route them, and enriches every result with an
LLM-generated summary.

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 + PostGIS · Redis · Anthropic
Claude · Testcontainers.

> The `data/news_data.json` seed (2000 rows) is *seed data*, not the design
> target. The system is built to scale to millions of articles via indexed
> queries, keyset-shaped pagination, layered caching, bounded pools, and provider
> seams (`SearchProvider`, `GeoProvider`, `EventStream`). See
> [`plan/design.md`](plan/design.md) §10.

---

## Contents

- [Prerequisites](#prerequisites)
- [Run](#run)
- [Endpoints](#endpoints)
- [API examples](#api-examples)
- [Configuration reference](#configuration-reference)
- [Architecture](#architecture)
- [Tests](#tests)
- [Security](#security)

---

## Prerequisites

- Java 21 (`java -version` → 21.x)
- Maven 3.9+
- Docker (for `docker-compose` infra and the Testcontainers test suite)

## Run

1. Copy the env template and (optionally) add your Claude key:

   ```bash
   cp .env.example .env
   # edit .env → set ANTHROPIC_API_KEY (leave blank to run in heuristic mode)
   ```

2. Start Postgres/PostGIS + Redis:

   ```bash
   docker compose up -d
   ```

3. Run the app (loads the seed data on first startup and simulates trending events):

   ```bash
   set -a && source .env && set +a   # export env vars
   mvn spring-boot:run
   ```

4. Verify it is up:

   ```bash
   curl localhost:8080/actuator/health
   # {"status":"UP","groups":["liveness","readiness"]}
   ```

Running **without** an `ANTHROPIC_API_KEY` is fully supported — the app falls
back to a deterministic heuristic intent extractor and returns
`"llm_summary": null` with `"degraded": true`. Retrieval still returns 200.

## Endpoints

Base path: `/api/v1/news`. All endpoints accept `limit` (default 5, clamped `[1,50]`).

| Endpoint | Params | Filter | Ranking |
|---|---|---|---|
| `GET /category` | `category` (req) | category contains value (case-insensitive) | publication_date desc |
| `GET /score` | `threshold` (default 0.7) | `relevance_score >= threshold` | relevance_score desc |
| `GET /search` | `query` (req) | full-text match in title/description | blended `ts_rank` + relevance_score |
| `GET /source` | `source` (req) | `source_name` match (case-insensitive) | publication_date desc |
| `GET /nearby` | `lat`,`lon` (req), `radius` km (default 10) | within radius (PostGIS `ST_DWithin`) | distance asc |
| `GET /trending` | `lat`,`lon` (req) | events near location | trending score desc |
| `GET /query` | `query` (req), `lat`,`lon`, `intent`, `entities` | LLM-routed (multi-intent AND) | most-specific matched strategy |

> The seed corpus is entirely in western/central India (lat 15.6–22.5, lon
> 72.6–80.9). Use Indian coordinates for `/nearby` and `/trending`, e.g. Mumbai
> `lat=19.07&lon=72.88`.

## API examples

### R1 — by category
```bash
curl "localhost:8080/api/v1/news/category?category=world&limit=3"
```
```json
{
  "articles": [
    {
      "id": "19aaddc0-7508-4659-9c32-2216107f8604",
      "title": "Attempts to mislead people: B'desh leader Yunus on coup rumours",
      "description": "Bangladesh's interim government leader ...",
      "url": "https://www.news18.com/amp/world/...",
      "publication_date": "2025-03-26T04:46:55",
      "source_name": "News18",
      "category": ["world"],
      "relevance_score": 0.4,
      "llm_summary": "Bangladesh's interim leader dismissed coup rumours as attempts to mislead.",
      "latitude": 17.900636,
      "longitude": 77.465262
    }
  ],
  "total": 1,
  "query": "category=world",
  "degraded": false
}
```

### R2 — by score threshold
```bash
curl "localhost:8080/api/v1/news/score?threshold=0.8&limit=5"
```

### R3 — full-text search (blended ranking)
```bash
curl "localhost:8080/api/v1/news/search?query=government%20policy&limit=5"
```

### R4 — by source (case-insensitive)
```bash
curl "localhost:8080/api/v1/news/source?source=news18&limit=5"
```

### R5 — nearby (distance_km populated, nearest first)
```bash
curl "localhost:8080/api/v1/news/nearby?lat=19.07&lon=72.88&radius=50&limit=5"
```
```json
{
  "articles": [
    { "id": "…", "title": "…", "category": ["national"], "distance_km": 3.42, "llm_summary": "…" }
  ],
  "total": 5,
  "query": "nearby(19.0700,72.8800,50.0km)",
  "degraded": false
}
```

### R6 — trending (location-aware, geohash-cached)
```bash
curl "localhost:8080/api/v1/news/trending?lat=19.07&lon=72.88&limit=5"
```

### R7 — LLM-routed query
```bash
# LLM understanding (needs ANTHROPIC_API_KEY):
curl "localhost:8080/api/v1/news/query?query=Latest%20Elon%20Musk%20Twitter%20news%20near%20Palo%20Alto&lat=19.07&lon=72.88"

# Multi-intent, e.g. "top tech news from a source":
curl "localhost:8080/api/v1/news/query?query=top%20tech%20news%20from%20News18"

# Direct-input escape hatch (no LLM / no API key required — deterministic):
curl "localhost:8080/api/v1/news/query?query=world%20news&intent=category&entities=world"
curl "localhost:8080/api/v1/news/query?query=x&intent=source&entities=News18"
```

`intent` and `entities` may be repeated or comma-separated. Multiple intents are
AND-combined; results are ranked by the most specific matched strategy
(nearby > search > {source, category} > score; source and category share the
publication-date rule).

### Error envelope
```bash
curl -i "localhost:8080/api/v1/news/category"        # missing required param → 400
curl -i "localhost:8080/api/v1/news/score?threshold=2"  # out of range → 400
```
```json
{ "error": "Bad Request", "message": "Missing required parameter: category",
  "status": 400, "timestamp": "2026-07-03T16:00:00.000+05:30", "path": "/api/v1/news/category" }
```
An empty result set is a **200** with `{"articles": [], "total": 0, …}`, never 404.

## Configuration reference

All tunables live under `news.*` in `src/main/resources/application.yml` and are
overridable via environment variables (see `.env.example`).

| Property | Default | Meaning |
|---|---|---|
| `news.ingest.file` | `data/news_data.json` | Seed file path (filesystem then classpath). |
| `news.ingest.skip-if-populated` | `true` | Skip load if `articles` already has rows. |
| `news.ingest.batch-size` | `500` | Upsert batch size (streaming ingest). |
| `news.search.weight-text` | `0.6` | Weight of `ts_rank` in blended search. |
| `news.search.weight-relevance` | `0.4` | Weight of `relevance_score` in blended search. |
| `news.nearby.default-radius-km` | `10` | Default `/nearby` radius. |
| `news.nearby.max-radius-km` | `500` | Radius cap. |
| `news.trending.geohash-precision` | `5` | Geohash prefix precision for the trending cache (~5 km). |
| `news.trending.event-geohash-precision` | `7` | Geohash precision stored on events. |
| `news.trending.cache-ttl-seconds` | `60` | Trending cache TTL. |
| `news.trending.half-life-hours` | `6` | Recency decay half-life. |
| `news.trending.window-hours` | `48` | Event look-back window. |
| `news.trending.type-weights` | view=1,dwell=2,click=3,share=5 | Event type weights. |
| `news.trending.simulator.enabled` | `true` | Seed synthetic events at startup. |
| `news.trending.simulator.event-count` | `5000` | Number of simulated events. |
| `news.llm.enabled` | `true` | Master switch; `false` → always heuristic. |
| `news.llm.api-key` | `${ANTHROPIC_API_KEY}` | Claude API key (env only). |
| `news.llm.base-url` | `https://api.anthropic.com/v1/messages` | Anthropic Messages API endpoint. |
| `news.llm.anthropic-version` | `2023-06-01` | Anthropic API version header. |
| `news.llm.model-extract` | `claude-sonnet-4-6` | Model for query understanding. |
| `news.llm.model-summary` | `claude-haiku-4-5-20251001` | Cheap model for summaries. |
| `news.llm.timeout-ms` | `5000` | Per-call LLM timeout. |
| `news.llm.max-summary-tokens` | `120` | Summary length cap. |
| `news.llm.summary-concurrency` | `4` | Bulkhead pool size for enrichment. |
| `news.llm.prompts.extraction-path` | `classpath:prompts/extraction-system.txt` | Query-understanding system prompt (override with a `file:`/`classpath:` path — no rebuild). |
| `news.llm.prompts.summary-path` | `classpath:prompts/summary-system.txt` | Summarization system prompt (overridable). |
| `news.llm.prompts.extraction-version` / `summary-version` | `extraction.v1` / `summary.v1` | Prompt version tags (logged + metered for correlation/rollback). |
| `news.ratelimit.query-capacity` | `30` | Token-bucket capacity on `/query`. |
| `news.ratelimit.query-refill-per-minute` | `30` | `/query` bucket refill per minute. |

Infra/env overrides (see `.env.example`): `ANTHROPIC_API_KEY`, `NEWS_LLM_ENABLED`,
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `SERVER_PORT`.

## Architecture

- **Retrieval** is pushed entirely into indexed SQL (GiST `geog`, GIN `search_tsv`
  + `categories_norm`, composite btree score/date). The corpus is never sorted in
  app memory; queries are keyset-shaped (a `cursor` param is an additive extension).
- **Scale seams:** `SearchProvider` (Postgres FTS now / Elasticsearch later),
  `GeoProvider` (PostGIS now / tiled geo later), `EventStream` (in-process now /
  Kafka later). Services depend on the interfaces, so scale-out is an adapter swap.
- **LLM:** tool-use extraction + cheap-model summaries, wrapped by a Resilience4j
  circuit breaker + retry, with a deterministic heuristic fallback.
- **Caching:** Caffeine (local) → Redis (distributed) with single-flight; summaries
  cached indefinitely (articles are immutable), trending per geohash prefix (TTL).
- **Reliability:** any single dependency (LLM, Redis) can be fully down and core
  retrieval still returns 200 `degraded:true`; Postgres down → 503. Bounded Hikari
  pool + `statement_timeout`, bulkhead summary executor, Bucket4j rate limit.
- **Observability:** Actuator liveness/readiness (readiness gates on Postgres),
  correlation-id filter (MDC + `X-Correlation-Id`), Micrometer/Prometheus metrics
  at `/actuator/prometheus` (incl. cache hit/miss, Redis errors, breaker state).

See [`plan/design.md`](plan/design.md) for the full blueprint and
[`plan/requirements.md`](plan/requirements.md) for the traceable requirements.

## Observability & troubleshooting

Everything below is aimed at someone debugging the running service for the first time.

**Correlation id.** Every request is tagged with a correlation id — honored from an
inbound `X-Correlation-Id` header or generated — echoed back in the response header
and printed on every log line (`... [contextual-news-retrieval,<id>] ...`). Grep the
logs for one id to see a single request end-to-end.

**Access log (INFO, always on).** Every `/api/v1/news/**` call logs one line on
completion from logger `com.inshorts.news.access`:

```
INFO  [contextual-news-retrieval,3f9c…] method=GET path=/api/v1/news/query status=200 tookMs=812 intent=[NEARBY, SEARCH] strategy=multi:nearby results=0 degraded=false
```

That single line answers "what was called, how long it took, what it routed to, and
whether it degraded" — e.g. the `results=0 degraded=false` above is how you'd see a
valid empty match at a glance.

**Full request trace (DEBUG).** To see *why* a query routed the way it did — the
extracted `intent`/`entities`/`keywords`, whether it came from the LLM/heuristic/
direct params, the resolved filters, the executed SQL, and the per-article summary
source (cache/db/llm) — raise the app logger to DEBUG. Three ways:

```bash
# a) at startup, via env (no rebuild)
LOGGING_LEVEL_COM_INSHORTS_NEWS=DEBUG mvn spring-boot:run
# b) in application.yml: logging.level.com.inshorts.news: DEBUG
# c) at runtime, no restart (Actuator loggers endpoint is exposed):
curl -X POST localhost:8080/actuator/loggers/com.inshorts.news \
     -H 'Content-Type: application/json' -d '{"configuredLevel":"DEBUG"}'
# revert with {"configuredLevel":"INFO"}
```
> The `loggers` endpoint is mutating — fine locally; put it behind auth/network
> policy in production.

**Log levels at a glance:** ERROR = unexpected/5xx (with stack trace, server-side
only — never in the API body); WARN = handled degradations (LLM/Redis fell back,
rate limited); INFO = lifecycle + the per-request access line; DEBUG = full request
trace; TRACE = SQL bind params. A `4xx` (validation) is DEBUG, not ERROR — client
input isn't a server fault. Secrets (the API key) and article bodies are never logged.

**Metrics** at `/actuator/prometheus`:

```bash
curl -s localhost:8080/actuator/prometheus | grep -E 'news_cache|news_llm_call|news_query_intent|news_summary_resolution|resilience4j'
```
- `news_cache_hits/misses/redis_errors` — cache effectiveness + Redis health.
- `news_llm_call_seconds{operation,outcome}` — LLM extract/summarize latency + success rate.
- `news_query_intent_total{intent}` — routing distribution across `/query` calls.
- `news_summary_resolution_total{source}` — how summaries were served (cache/db/llm/failed).
- `resilience4j_circuitbreaker_state` — LLM breaker state.

**Prompts** live in versioned files under
[`src/main/resources/prompts/`](src/main/resources/prompts/) and are loaded by
`PromptService`; edit or override them (`news.llm.prompts.*-path`) without touching
code. The prompt version is logged at DEBUG and tagged on LLM metrics so output can
be correlated to a revision.

## Tests

```bash
mvn verify   # requires Docker (Testcontainers provisions Postgres/PostGIS + Redis)
```

The suite (45 tests) covers ingestion idempotency + geo normalization, R1–R7
contract + validation envelopes, PostGIS distance correctness, blended search
ranking, LLM fault-injection (→ 200 `degraded`), trending score/cache, a
concurrency smoke test, prompt loading, and pure validation/parsing units. Tests run
offline (LLM disabled) against real containers via the singleton-container pattern.

The build pins the Docker Engine API version (`api.version=1.43`, in
`pom.xml`) so Testcontainers works flag-free against modern Docker (Engine 29
requires API ≥ 1.40). On **Docker Desktop**, plain `mvn verify` just works.

> **Colima users:** point Testcontainers at the Colima socket (the API version
> is already handled by the build):
> ```bash
> DOCKER_HOST="unix://$HOME/.colima/default/docker.sock" TESTCONTAINERS_RYUK_DISABLED=true mvn verify
> ```

## Security

- `ANTHROPIC_API_KEY` is read from the environment only; `.env` is gitignored and
  this repo is public — no secrets are committed.
- All database access is parameterized (no string-built SQL with user input).
- Correlation ids and metrics never log secret values.
