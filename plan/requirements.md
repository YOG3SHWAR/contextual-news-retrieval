# Requirements — Contextual News Retrieval System

This document breaks the task down into clear, testable requirements. It is the
source of truth for *what* must be built. The *how* is in [`design.md`](./design.md),
and the *build order* is in [`tasks.md`](./tasks.md).

---

## 1. Goal

Build a REST API that:

1. Loads ~2000 news articles from `data/news_data.json` into a datastore.
2. Retrieves articles through five ranked strategies plus a bonus trending feed.
3. Uses an LLM to understand a natural-language query (extract entities + intent)
   and route it to the right strategy.
4. Enriches every returned article with an LLM-generated `llm_summary`.
5. Returns a consistent JSON envelope with rich metadata.

**Chosen stack (from the architecture decision):** Java 21, Spring Boot 3.5,
PostgreSQL 16 + PostGIS, Redis, Anthropic Claude, Testcontainers for local infra.

---

## 2. Data

Source file: `data/news_data.json` — an array of 2000 article objects.

### 2.1 Article schema (source)

| Field | Type | Notes |
|---|---|---|
| `id` | uuid string | Primary key. |
| `title` | string | Present on all rows. |
| `description` | string | Present on all rows. Together with `title`, the only text available. |
| `url` | string | Often YouTube / AMP; body is **not** crawlable. |
| `publication_date` | ISO-8601 local datetime | e.g. `2025-03-26T04:46:55`. |
| `source_name` | string | 152 distinct sources. |
| `category` | array of strings | 32 distinct labels; inconsistent casing (`national`, `General`, `IPL_2025`, `Health___Fitness`). |
| `relevance_score` | float `0.0–1.0` | |
| `latitude` | float | Always present and valid in the seed file (verified); the loader still defends against null/`(0,0)`. |
| `longitude` | float | Always present and valid in the seed file (verified); see above. |

### 2.2 Data facts that constrain behavior (verified against the file)

- **2000 rows**, **32 categories**, **152 sources**; all `id`s unique.
- Only `title` + `description` carry text — **no article body**. Summaries must be
  generated from these two fields only.
- `category` is an **array** in the data but a **string** in the task's example
  output. **Decision: return the array** (the honest superset) and document it.
- **All 2000 rows have valid coordinates** — lat ∈ `[15.60, 22.50]`, lon ∈
  `[72.60, 80.88]` (western/central India). Demo queries and the event simulator
  must use Indian coordinates, or `nearby`/`trending` return nothing.
- The loader still **defends** against null/`(0,0)` coordinates (stores null
  `geog`; excluded from `nearby`/`trending` since `(0,0)` is in the ocean) —
  future feeds may contain them. The defense is verified with test fixtures,
  not the seed file.
- `publication_date` spans only **4 days** (`2025-03-22` → `2025-03-26`), so
  date-ranked results have many near-ties — the deterministic `id` tie-break is
  load-bearing, not cosmetic.
- Labels are noisy → match **case-insensitively**. Verified: mixed-case category
  labels (`General`, `DEFENCE`, `IPL_2025`) and true source-name case variants
  (`Mid-day`/`Mid-Day`, `Latestly`/`LatestLY`, `X (Formerly Twitter)`/`X (formerly
  Twitter)`). Category filtering uses a **lowercased copy of the array**
  (`categories_norm`), because Postgres `@>` containment is exact-match.

---

## 3. Functional requirements

### 3.1 Retrieval endpoints

Base path: `/api/v1/news`. Every endpoint accepts `limit` (default **5**, clamped
to `[1, 50]`) and returns the standard envelope (§4).

| # | Endpoint | Method | Params | Filter | Ranking |
|---|---|---|---|---|---|
| R1 | `/category` | GET | `category` (req), `limit` | category contains value (case-insensitive) | `publication_date` desc |
| R2 | `/score` | GET | `threshold` (default 0.7), `limit` | `relevance_score >= threshold` | `relevance_score` desc |
| R3 | `/search` | GET | `query` (req), `limit` | text match in title/description | blended `ts_rank` + `relevance_score` |
| R4 | `/source` | GET | `source` (req), `limit` | `source_name` matches (case-insensitive) | `publication_date` desc |
| R5 | `/nearby` | GET | `lat` (req), `lon` (req), `radius` km (default 10), `limit` | within radius via PostGIS `ST_DWithin` | distance asc (`ST_Distance`) |
| R6 | `/trending` | GET | `lat` (req), `lon` (req), `limit` | events near location | computed trending score desc (bonus) |

All ranking ties are broken deterministically by `id` for stable ordering.

### 3.2 LLM-backed query endpoint

| # | Endpoint | Method | Params | Behavior |
|---|---|---|---|---|
| R7 | `/query` | GET | `query` (req), `lat`, `lon`, `limit` | Send `query` to the LLM → `{entities, intent[], keywords}`; route to the matching strategy/strategies; return enriched results. |

- **Intent may be multi-valued** (e.g. `["category","source"]`). Multiple
  filters are **AND-combined**; results are ranked by the most specific matched
  strategy's rule.
- **Direct-input escape hatch:** callers may pass `intent` and/or `entities`
  directly as query params to bypass the LLM (deterministic, works with no API
  key). This is both a feature and a reliability lever.
- **Location resolution:** `nearby` routing prefers the request's `lat`/`lon`.
  A location entity (e.g. "Mumbai") is resolved via a **bundled static
  city-coordinate gazetteer** — no external geocoding API. If neither resolves,
  the location term falls back to text `search`.

### 3.3 LLM enrichment

- R8: Every article in **every** endpoint's response carries an `llm_summary`
  string generated from its `title` + `description`.
- Summaries are cached (Redis, keyed by `hash(title+description)`) and lazily
  persisted; articles are immutable so the cache never expires.
- If the LLM is unavailable, `llm_summary` is `null` and the response is flagged
  `degraded: true` — never a failure.

### 3.4 Trending (bonus)

- R9: Simulate a user-event stream: `{event_id, user_id, article_id,
  event_type(view|click|share|dwell), lat, lon, geohash, ts}`.
- R10: Compute `trending = Σ(type_weight · e^(-λ·Δt)) · geo_factor` with type
  weights `view=1, dwell=2, click=3, share=5`, recency decay (~6h half-life),
  and a geo factor favoring events near the requester.
- R11: Cache top-N per **geohash prefix** (precision ~5 ≈ 5 km) in Redis under
  `trending:{prefix}` with a short TTL (~60 s).

---

## 4. Response contract

### 4.1 Success envelope (HTTP 200)

```json
{
  "articles": [
    {
      "id": "…",
      "title": "…",
      "description": "…",
      "url": "…",
      "publication_date": "2025-03-26T04:46:55",
      "source_name": "…",
      "category": ["technology"],
      "relevance_score": 0.92,
      "llm_summary": "…",
      "latitude": 37.4220,
      "longitude": -122.0840,
      "distance_km": 3.4
    }
  ],
  "total": 5,
  "query": "…",
  "degraded": false
}
```

- `distance_km` is present only for `/nearby` (and `/trending` where relevant).
- `total` is the number of articles returned (top-N), not the full match count.
- `query` echoes the caller's input (the raw query or a description of the filter).
- `degraded` is `true` when the LLM/cache fell back.

### 4.2 Error envelope

Uniform body for all errors, with correct HTTP status:

```json
{ "error": "…", "message": "…", "status": 400, "timestamp": "…", "path": "…" }
```

- `400` — validation (missing/blank required param, out-of-range lat/lon/threshold).
- `404` — unknown route.
- `429` — rate limited.
- `503` — datastore down (`Retry-After` set).
- Empty result set → **200** with `{"articles": [], "total": 0, …}`, never 404.
- Stack traces are never leaked.

---

## 5. Validation rules

| Param | Rule |
|---|---|
| `limit` | integer, clamp to `[1, 50]`, default 5. |
| `threshold` | float in `[0,1]`, default 0.7; out-of-range → 400 (consistent with `lat`/`lon` handling). |
| `lat` | float in `[-90, 90]`; else 400. |
| `lon` | float in `[-180, 180]`; else 400. |
| `radius` | > 0, cap at 500 km; default 10. |
| `category` / `source` / `query` | non-blank; else 400. Matched case-insensitively. |

---

## 6. Non-functional requirements

- **RESTful**, `/api/v1` versioned, consistent JSON, robust error handling.
- **Scalability (first-class):** the ~2000-row file is **seed data**, not the
  design target. The system must scale to **millions of articles and high
  concurrent request volume without a rewrite** — via a stateless API tier,
  indexed database queries (never sorting/scanning the corpus in memory), keyset
  (cursor) pagination, layered local+distributed caching, bounded pools, and
  **provider seams** (`SearchProvider`, `GeoProvider`, `EventStream`) that let the
  search/geo/event backends be swapped for Elasticsearch / tiled-geo sharding /
  Kafka as an adapter change. Heavier engines are **designed for** but not stood
  up at seed size. See `design.md` §10.
- **Caching:** Redis for trending and LLM summaries/query-understanding, fronted
  by a local (Caffeine) tier with single-flight on hot keys.
- **Reliability (first-class):** any single external dependency (LLM, Redis) can
  be fully down and core retrieval still works; every response is a well-formed
  envelope; ingestion is idempotent; ranking is deterministic; failures are
  observable and bounded. See `design.md` §7.
- **Observability:** Actuator health (liveness + readiness), structured logging
  with correlation id, Micrometer metrics (latency p50/p95/p99, error rate, LLM
  cache hit-rate, circuit-breaker state, Redis availability).
- **Security:** parameterized queries only; `ANTHROPIC_API_KEY` from env, never
  committed (this repo is public); no secrets in code or logs.
- **Local setup:** comes up with only Docker present (Testcontainers provisions
  Postgres/PostGIS + Redis); `docker-compose` provided for a persistent run.

---

## 7. Acceptance criteria

1. `GET` on each of R1–R7 returns the correct envelope with correctly ranked,
   correctly limited results.
2. Every returned article has an `llm_summary` (or `null` + `degraded:true`).
3. `/nearby` returns only articles within `radius`, ordered by real spheroidal
   distance, with `distance_km` populated; the null/`(0,0)`-geo defense is
   verified via test fixtures (the seed file contains no such rows).
4. `/query` routes single- and multi-intent queries correctly and honors the
   direct `intent`/`entities` escape hatch with no API key set.
5. `/trending` returns location-aware trending results served from the geohash cache.
6. All validation rules (§5) enforced; all errors use the uniform envelope.
7. Ingestion is idempotent (safe to re-run; no duplicates).
8. With the LLM key unset or the provider stubbed to fail, all retrieval
   endpoints still return 200 with `degraded:true`.
9. Integration tests pass against real Postgres/PostGIS + Redis via Testcontainers.

---

## 8. Out of scope

- Authentication / user accounts (beyond simulated event `user_id`).
- A frontend UI.
- Real user-event ingestion (events are simulated in-process).
- pgvector semantic search and Kafka streaming — **documented** as the upgrade
  and scale paths, not built in this deliverable.
