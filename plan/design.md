# Design — Contextual News Retrieval System

Detailed technical design for the system specified in
[`requirements.md`](./requirements.md), implementing **Approach A** from
[`../docs/architecture-approaches.md`](../docs/architecture-approaches.md):
Spring Boot + PostgreSQL/PostGIS + Redis + Anthropic Claude.

This document is the blueprint the implementation agent follows. Build order is in
[`tasks.md`](./tasks.md).

> **Scalability posture.** The 2000-row `news_data.json` is *seed data*, not the
> design target. Every component is designed so it scales to **millions of
> articles and high concurrent request volume without a rewrite** — through a
> stateless API tier, indexed database queries (never paging rows into memory),
> keyset pagination, layered caching, and **provider seams** (§1.1) that let the
> search / geo / event backends be swapped for heavier engines as load grows.
> Where a heavier component is overkill at seed size, the local implementation is
> kept behind an interface so the scale-out swap is a configuration/adapter change,
> not a redesign. Scaling specifics are in §10.

### 1.1 Design principles for scale

1. **Stateless API tier** — no session/local state; scales horizontally behind a
   load balancer. All caches are external (Redis) or rebuildable local caches.
2. **Push work into indexed queries** — filtering, ranking, and distance are done
   in Postgres/PostGIS with proper indexes; the app never full-scans or sorts the
   corpus in memory.
3. **Keyset (cursor) pagination**, not `OFFSET` — stable, O(1) deep pages at any
   corpus size.
4. **Provider seams** — `SearchProvider`, `GeoProvider`, and `EventStream` are
   interfaces. Seed uses Postgres FTS / PostGIS / in-process simulator; scale-out
   swaps in Elasticsearch / tiled geohash sharding / Kafka **without touching
   controllers or services**.
5. **Layered caching** — local (Caffeine) in front of distributed (Redis), with
   request coalescing / single-flight on hot keys.
6. **Bounded everything** — connection pools, thread pools, LLM concurrency, and
   rate limits are explicit so load sheds gracefully instead of collapsing.
7. **Async, decoupled enrichment** — LLM summary generation can move to a queue /
   precompute pipeline; the request path never blocks on unbounded LLM work.

---

## 1. System overview

```
                          ┌──────────────────────────────────────────────┐
                          │                Client (HTTP)                  │
                          └───────────────────────┬──────────────────────┘
                                                  │  GET /api/v1/news/*
                                                  ▼
┌───────────────────────────────────────────────────────────────────────────────┐
│                            Spring Boot Application                              │
│                                                                                 │
│  ┌───────────────┐   ┌──────────────────┐   ┌─────────────────────────────┐    │
│  │  Web layer    │   │  Service layer   │   │   Integration layer         │    │
│  │  Controllers  │──▶│  NewsService     │──▶│   Repositories (JPA)        │────┼──▶ PostgreSQL
│  │  + validation │   │  QueryService    │   │   LlmClient (Claude)        │────┼──▶ Claude API
│  │  + error hdlr │   │  TrendingService │   │   CacheService (Redis)      │────┼──▶ Redis
│  └───────────────┘   │  SummaryService  │   │   EventSimulator            │    │
│                      └──────────────────┘   └─────────────────────────────┘    │
│                                                                                 │
│  Cross-cutting: resilience (timeouts, retry, circuit breaker, bulkhead),        │
│  rate limiting, metrics, structured logging, global exception handler.          │
└───────────────────────────────────────────────────────────────────────────────┘
```

Three external dependencies:
- **PostgreSQL/PostGIS** — source of truth; relational + geo + full-text.
- **Redis** — trending cache + LLM summary/query caches (optional; degrades).
- **Claude** — query understanding + summaries (optional; degrades).

---

## 2. Component design

### 2.1 Package layout

```
com.inshorts.news
├── NewsApplication.java              # @SpringBootApplication entry point
├── config/
│   ├── RedisConfig.java              # RedisTemplate, connection factory
│   ├── ResilienceConfig.java         # Resilience4j circuit breaker, bulkhead
│   ├── RateLimitConfig.java          # Bucket4j buckets
│   ├── AsyncConfig.java              # bounded summary executor pool
│   └── ObservabilityConfig.java      # Micrometer, correlation-id filter
├── web/
│   ├── NewsController.java           # R1–R6 endpoints
│   ├── QueryController.java          # R7 /query endpoint
│   ├── dto/                          # ArticleDto, NewsResponse, ErrorResponse
│   └── GlobalExceptionHandler.java   # @ControllerAdvice, uniform errors
├── service/
│   ├── NewsService.java              # retrieval orchestration + ranking
│   ├── QueryService.java             # LLM routing, multi-intent merge
│   ├── SummaryService.java           # llm_summary generation + cache
│   ├── TrendingService.java          # trending score + geohash cache
│   └── EventSimulator.java           # synthetic user-event generator
├── integration/
│   ├── llm/
│   │   ├── LlmClient.java            # interface
│   │   ├── ClaudeLlmClient.java      # Anthropic HTTP + tool-use extraction
│   │   ├── HeuristicIntentExtractor # keyword fallback (no LLM)
│   │   └── model/                    # QueryUnderstanding, ExtractionResult
│   ├── search/                       # ── SCALE SEAM ──
│   │   ├── SearchProvider.java       # interface: filter+rank → article ids
│   │   └── PostgresSearchProvider    # tsvector/ts_rank now; Elasticsearch later
│   ├── geo/                          # ── SCALE SEAM ──
│   │   ├── GeoProvider.java          # interface: nearby / distance
│   │   └── PostgisGeoProvider        # PostGIS now; tiled/sharded geo later
│   ├── events/                       # ── SCALE SEAM ──
│   │   ├── EventStream.java          # interface: publish/consume user events
│   │   └── InProcessEventStream      # simulator now; Kafka consumer later
│   └── cache/CacheService.java       # local(Caffeine)+Redis, single-flight
├── repository/
│   ├── ArticleRepository.java        # JPA + native geo/FTS queries; keyset paging
│   └── EventRepository.java          # user-event persistence
├── domain/
│   ├── Article.java                  # @Entity, geography column
│   └── UserEvent.java                # @Entity
└── ingest/
    ├── DataLoader.java               # ApplicationRunner, idempotent upsert
    └── NewsJsonRecord.java           # Jackson binding for source JSON
```

### 2.2 Responsibilities

- **Controllers** — parse/validate params, delegate, shape the envelope. No logic.
- **NewsService** — one method per strategy; builds the repository query and maps rows.
- **QueryService** — calls `LlmClient`, resolves location entities via the
  bundled gazetteer, merges multi-intent filters (AND), picks the ranking rule,
  delegates to `NewsService`.
- **SummaryService** — for the returned page, fetch/generate summaries with
  bounded parallelism, cache in Redis, lazily persist to the `articles` table.
- **TrendingService** — read events, compute scores, cache per geohash prefix.
- **LlmClient** — Claude tool-use for extraction + a summarize call; wrapped by
  circuit breaker + timeout; falls back to `HeuristicIntentExtractor`.

---

## 3. Data model

### 3.1 `articles` table

```sql
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE articles (
    id                UUID PRIMARY KEY,
    title             TEXT NOT NULL,
    description       TEXT NOT NULL,
    url               TEXT,
    publication_date  TIMESTAMP,
    source_name       TEXT,
    categories        TEXT[],                 -- original labels (returned in API)
    categories_norm   TEXT[],                 -- lowercased copy (used for filtering)
    relevance_score   DOUBLE PRECISION,
    latitude          DOUBLE PRECISION,
    longitude         DOUBLE PRECISION,
    geog              GEOGRAPHY(Point, 4326),  -- null when lat/lon null or (0,0)
    llm_summary       TEXT,                    -- lazily persisted
    search_tsv        TSVECTOR GENERATED ALWAYS AS
                        (to_tsvector('english',
                           coalesce(title,'') || ' ' || coalesce(description,''))) STORED
);

-- Indexes
CREATE INDEX idx_articles_geog        ON articles USING GIST (geog);
CREATE INDEX idx_articles_tsv         ON articles USING GIN (search_tsv);
CREATE INDEX idx_articles_categories  ON articles USING GIN (categories_norm);
CREATE INDEX idx_articles_source      ON articles (lower(source_name));
CREATE INDEX idx_articles_score       ON articles (relevance_score DESC, id DESC);   -- composite: serves keyset (rank_key, id)
CREATE INDEX idx_articles_pubdate     ON articles (publication_date DESC, id DESC);  -- composite: serves keyset (rank_key, id)
```

`search_tsv` is a **generated column** — the DB keeps it consistent and the app
never writes it (JPA maps it read-only or omits it). `categories_norm` holds each
label lowercased, populated at ingest: `@>` array containment is **exact-match**,
so case-insensitive category filtering needs a normalized copy (the data mixes
`General`, `DEFENCE`, `IPL_2025` with lowercase labels), while `categories`
preserves the original labels for responses. `geog` is set only when coordinates
are valid and not `(0,0)` — the seed file has no such rows (verified), but the
loader defends anyway.

### 3.2 `user_events` table (trending)

```sql
CREATE TABLE user_events (
    event_id    UUID PRIMARY KEY,
    user_id     UUID,
    article_id  UUID REFERENCES articles(id),
    event_type  TEXT,                 -- view | click | share | dwell
    latitude    DOUBLE PRECISION,
    longitude   DOUBLE PRECISION,
    geohash     TEXT,                 -- precision-7, prefix-queried
    created_at  TIMESTAMP
);
CREATE INDEX idx_events_geohash ON user_events (geohash text_pattern_ops);
CREATE INDEX idx_events_article ON user_events (article_id);
CREATE INDEX idx_events_created ON user_events (created_at DESC);
```

---

## 4. Request flows

### 4.1 Simple retrieval (e.g. `/nearby`)

```
Client ──▶ NewsController.nearby(lat,lon,radius,limit)
             │  validate lat∈[-90,90], lon∈[-180,180], radius≤500, clamp limit
             ▼
          NewsService.nearby(...)
             │  ArticleRepository.findNearby(point, radiusMeters, limit)
             ▼
          PostGIS:  WHERE ST_DWithin(geog, :point, :radius_m)
                    ORDER BY ST_Distance(geog, :point) ASC, id
             │  rows (+ computed distance_km)
             ▼
          SummaryService.enrich(articles)   ── Redis cache / Claude / persist
             ▼
          NewsResponse{articles, total, query, degraded}  ──▶ 200 Client
```

### 4.2 LLM-routed `/query`

```
Client ──▶ QueryController.query(q, lat, lon, limit, [intent], [entities])
             │
             ├── direct intent/entities provided? ──yes──▶ skip LLM (deterministic)
             │
             no
             ▼
          QueryService.understand(q)
             │  CacheService.get(hash(normalizedQuery))  ── hit ─▶ reuse
             │  miss ▼
             │  LlmClient.extract(q)  [circuit breaker + 3–5s timeout]
             │     success ─▶ {entities, intent[], keywords}
             │     failure ─▶ HeuristicIntentExtractor(q)  (degraded=true)
             ▼
          QueryService.route(understanding)
             │  for each intent → build filter; AND-combine filters
             │  pick ranking = most specific matched strategy's rule
             ▼
          NewsService.execute(mergedQuery)  ──▶ rows
             ▼
          SummaryService.enrich(...)  ──▶ NewsResponse  ──▶ 200 Client
```

**Intent → strategy mapping**

| Intent | Entity used | Filter |
|---|---|---|
| `category` | matched category label | `categories_norm @> ARRAY[lower(:cat)]` |
| `source` | matched source name | `lower(source_name) = lower(:src)` |
| `nearby` | request lat/lon; else location entity via bundled gazetteer | `ST_DWithin(...)` |
| `score` | — | `relevance_score >= :threshold` |
| `search` | keywords | `search_tsv @@ plainto_tsquery(:kw)` |

Multi-intent example: `["category","source"]` →
`WHERE categories_norm @> ARRAY['technology'] AND lower(source_name)=lower('nyt')`,
ranked by `publication_date DESC` (the shared rule of both).

**Location resolution (no external geocoder):** `nearby` routing prefers the
request's `lat`/`lon`. A location entity is resolved against a small **bundled
gazetteer of Indian cities** — the corpus coordinates all lie in lat `15.6–22.5`,
lon `72.6–80.9` (verified) — and an unresolvable location falls back to text
`search` on the term.

### 4.3 Summary enrichment (bounded, cached)

```
enrich(articles):
  for each article (bounded pool, e.g. 4 concurrent):
     key = "summary:" + sha256(title + "\n" + description)
     s = Redis.get(key)
     if s == null:
        s = article.llm_summary  (from DB, lazily-persisted)
        if s != null: Redis.set(key, s)       # warm cache from the DB hit
     if s == null:
        s = LlmClient.summarize(title, description)   [timeout, breaker]
        if s != null:
           Redis.set(key, s)             # no TTL — articles immutable
           persist article.llm_summary   # survive restart
        else:
           degraded = true               # leave llm_summary null
     article.llm_summary = s
```

### 4.4 Trending

```
TrendingService.trending(lat, lon, limit):
   prefix = geohash(lat, lon, precision=5)
   cached = Redis.get("trending:" + prefix)
   if cached: return cached                      # short TTL ~60s
   events = EventRepository.findByGeohashPrefix(prefix, sinceWindow)
   score[article] = Σ over events of
        typeWeight(e) * exp(-λ * ageHours(e)) * geoFactor(e, lat, lon)
        # view=1, dwell=2, click=3, share=5;  λ from 6h half-life;  geoFactor by distance
   top = topN(score, limit)  →  load articles  →  enrich summaries
   Redis.setex("trending:" + prefix, 60s, top)   # single-flight on miss
   return top
```

`EventSimulator` (an `ApplicationRunner`) seeds synthetic events at startup:
biased toward a few "hot" articles, clustered around a handful of **Indian city
coordinates inside the corpus bounding box** (all article coords lie in lat
`15.6–22.5`, lon `72.6–80.9` — e.g. Mumbai, Pune, Hyderabad, Nagpur), timestamps
skewed recent — so `/trending` returns meaningful data without real traffic and
demo requests near those cities hit populated buckets.

---

## 5. LLM integration

### 5.1 Query understanding (structured tool use)

Claude is called with a **tool/JSON-schema** constraint so it must return a typed
object rather than prose:

```json
{
  "entities":  ["Elon Musk", "Twitter", "Palo Alto"],
  "intent":    ["nearby"],
  "keywords":  ["elon musk", "twitter"]
}
```

- `intent` is validated against the enum `{category, score, search, source, nearby}`;
  unknown values default to `search`.
- Few-shot examples from the brief pin the taxonomy (e.g. *Elon Musk / Twitter /
  Palo Alto → nearby*; *tech news from NYT → [category, source]*).
- Result cached by `hash(normalizedQuery)` — extraction depends only on the query
  text; the requester's lat/lon is applied later, at routing time (keying on raw
  coordinates would fragment the cache into near-unique entries).

### 5.2 Summaries

- Model: a cheap/fast tier (e.g. Haiku) with small `max_tokens`.
- Instruction: summarize **only** the provided title/description; introduce no new
  facts (guards against hallucination).

### 5.3 Fallback / degradation

```
                ┌─────────────┐   ok    ┌────────────────────┐
   query ──────▶│ LlmClient   │────────▶│ structured result  │
                │ (breaker +  │         └────────────────────┘
                │  timeout)   │  fail / open / no key
                └─────┬───────┘────────▶┌────────────────────┐
                      │                 │ HeuristicIntent    │  degraded=true
                      │                 │ (keyword matching  │
                      │                 │  against categories│
                      │                 │  & source names)   │
                      ▼                 └────────────────────┘
```

`HeuristicIntentExtractor` maps query tokens to known categories/sources and
detects location words → gives a usable intent with no external call. This is
also what runs when `ANTHROPIC_API_KEY` is unset.

---

## 6. Ranking design

| Strategy | SQL ranking |
|---|---|
| category / source | `ORDER BY publication_date DESC, relevance_score DESC, id` |
| score | `WHERE relevance_score >= :threshold ORDER BY relevance_score DESC, id` |
| search | `ORDER BY (0.6 * ts_rank(search_tsv, plainto_tsquery(:q), 32) + 0.4 * relevance_score) DESC, id` |
| nearby | `WHERE ST_DWithin(geog,:p,:r) ORDER BY ST_Distance(geog,:p) ASC, id` |

- Search weights (`w_text=0.6`, `w_rel=0.4`) are configurable. Raw `ts_rank` is
  **not** bounded to `[0,1]` — normalization flag `32` rescales it to
  `rank/(rank+1)`, so both terms share the `[0,1)` scale before blending. Falls
  back to `ILIKE` scoring if `plainto_tsquery` yields nothing.
- All strategies append `id` as the final deterministic tie-break, sorted in the
  **same direction as the rank key**, so the row-comparison keyset predicate in
  §10.2 stays valid.

---

## 7. Reliability & resilience (design-level)

| Dependency | Failure | Behavior |
|---|---|---|
| Claude | timeout/429/5xx/bad JSON/no key | heuristic intent + `llm_summary:null`, `degraded:true`, never 500 |
| Redis | down/timeout | compute directly from Postgres; metric + log; no failure |
| Postgres | down/slow | 503 + `Retry-After`; statement timeout + bounded pool prevent hangs |

Patterns: explicit **timeouts** on every LLM/DB call; **retry** w/ backoff+jitter
on idempotent transient errors; **circuit breaker** (Resilience4j) around the LLM;
**bulkhead** bounded summary pool separate from web threads; **single-flight** on
hot geohash keys; **rate limiting** (Bucket4j) on the LLM-backed endpoint;
**idempotent** ingestion (upsert by `id`) and event dedupe by `event_id`.

Observability: Actuator liveness + **readiness** (readiness gates on Postgres;
LLM/Redis loss = *degraded*, not *down*); structured logs w/ correlation id;
Micrometer metrics (latency p50/p95/p99, error rate, LLM cache hit-rate, breaker
state, Redis availability, trending cache hit-rate).

---

## 8. Configuration

```yaml
# application.yml (values overridable by env)
news:
  ingest:
    file: data/news_data.json
    skip-if-populated: true
  search:
    weight-text: 0.6
    weight-relevance: 0.4
  nearby:
    default-radius-km: 10
    max-radius-km: 500
  trending:
    geohash-precision: 5
    cache-ttl-seconds: 60
    half-life-hours: 6
    type-weights: { view: 1, dwell: 2, click: 3, share: 5 }
  llm:
    enabled: true                  # false → always heuristic
    model-extract: claude-sonnet-4-6
    model-summary: claude-haiku-4-5-20251001
    timeout-ms: 5000
    max-summary-tokens: 120
    summary-concurrency: 4
# ANTHROPIC_API_KEY comes from env only — never committed.
```

Local infra via Testcontainers in tests; `docker-compose.yml` (Postgres/PostGIS +
Redis) for a persistent run.

---

## 9. Testing strategy

- **Integration (Testcontainers)** — real Postgres/PostGIS + Redis; verify geo,
  FTS ranking, ingestion idempotency.
- **Contract tests** — each endpoint's success + error envelope shape.
- **LLM fault injection** — stub `LlmClient` to throw/timeout/return bad JSON;
  assert 200 + `degraded:true`.
- **Geo unit tests** — known city-pair distances against PostGIS output.
- **Validation tests** — every §5 rule in `requirements.md`.
- **Load smoke test** — bounded pools + caching hold tail latency under concurrency.

---

## 10. Scalability — from 2000 rows to millions

The system is built so the **same codebase** serves seed data and a production-
scale corpus. Each concern below has a seed implementation and a documented
scale-out that swaps in behind a seam (§1.1) — no controller/service rewrite.

### 10.1 Scaling dimensions

| Dimension | Seed (this build) | Scale-out (same interfaces) |
|---|---|---|
| **Corpus size** | Single Postgres, all rows | Partition `articles` by `publication_date` (monthly); read replicas; archive cold partitions |
| **Search quality/volume** | `PostgresSearchProvider` (tsvector/ts_rank) | Swap to `ElasticsearchSearchProvider` (BM25, fuzzy, function_score) — index kept in sync via outbox/CDC |
| **Geo queries** | `PostgisGeoProvider` (GiST) | Geohash/H3 tiling + per-tile shards; CDN-cached regional feeds |
| **Trending events** | `InProcessEventStream` simulator + Postgres | `KafkaEventStream`: producers → topic → windowed stream aggregation (Kafka Streams/Flink) → materialized per-geohash tops |
| **LLM enrichment** | Sync, bounded pool, Redis cache | Precompute summaries offline at ingest; async enrichment via queue; provider batch API |
| **Read throughput** | Single stateless node | N nodes behind LB; Redis cluster; local Caffeine tier; HTTP/CDN response caching |
| **Hot keys** | Geohash TTL cache + single-flight | Consistent hashing, layered local+distributed cache, coalescing |
| **Write/ingest** | Streaming JSON parse + batched upsert | `COPY`/bulk loader, partition-aware writes, backpressure |
| **Availability** | docker-compose single DB | Multi-AZ Postgres (replica failover), Redis replication, graceful LLM/cache degradation |

### 10.2 Data & query scaling details

- **Ingestion streams the JSON** (Jackson streaming / batched inserts of ~500),
  so memory is constant regardless of file size — a 2000-row file and a 20M-row
  export use the same path.
- **Keyset pagination**: all list queries order by `(rank_key, id)` (uniform sort
  direction) and page via `WHERE (rank_key, id) < (:lastRank, :lastId)`, avoiding
  `OFFSET` scans that degrade with corpus size. The public API in this deliverable
  exposes only the top-N page (`limit`); because the queries are already
  keyset-shaped, adding a `cursor` param + `next_cursor` response field later is
  additive, not a rewrite.
- **Partitioning**: `articles` partitioned by `publication_date` range keeps
  indexes small and lets recency-biased queries hit only hot partitions.
- **Connection pool** (HikariCP) sized to DB capacity; `statement_timeout` bounds
  any single query so one heavy request can't starve the pool.

### 10.3 Caching layers

```
request ─▶ Caffeine (local, ms, per-node)
             │ miss
             ▼
           Redis (distributed, shared)  ──▶ single-flight recompute ──▶ DB/LLM
```

- **Summaries** cached indefinitely (immutable articles), so at scale nearly all
  reads are cache hits; a precompute-at-ingest job warms them.
- **Trending** cached per geohash prefix with short TTL + jitter; single-flight
  prevents thundering herd on a hot city at expiry.
- **Query understanding** cached by `hash(normalizedQuery)` (extraction is
  location-independent; see §5.1).

### 10.4 Trending at scale (the seam that matters most)

The `EventStream` interface is the key scale seam. At seed size an in-process
simulator writes events to Postgres and `TrendingService` aggregates on cache
miss. At scale, `KafkaEventStream` consumes a real view/click/share topic, a
windowed job maintains per-geohash top-N in Redis/a materialized store, and
`/trending` becomes a pure cache read. **Controllers and `TrendingService`'s
public contract don't change** — only the adapter behind the interface does.

### 10.5 What is actually built vs. seam vs. documented

To avoid overstating what this deliverable ships, the scalability work falls into
three honest tiers. A reviewer should read §10.1's "scale-out" column as the
*target state*, **not** as running infrastructure.

**Tier 1 — Built now (real code, works at scale).** Cheap at 2000 rows and
genuinely carries to millions:

- Indexed queries (GiST `geog`, GIN `search_tsv` + `categories_norm`, composite btree score/date, btree source).
- All filtering/ranking/distance pushed into SQL — the corpus is never sorted in app memory.
- **Keyset-shaped queries** — no `OFFSET` anywhere; a public `cursor` param is an additive extension (§10.2).
- Stateless API tier — horizontal scale with no code change.
- Layered Caffeine → Redis cache with single-flight on hot keys.
- Bounded pools + timeouts + rate limiting (Hikari, bulkhead summary executor, LLM timeout, Bucket4j) — load sheds instead of collapsing.
- Streaming + batched ingestion (constant memory; same path for a 20M-row file).
- Geohash-bucketed trending cache with TTL.

**Tier 2 — Seam only (interface + one implementation).** The interface exists and
services depend on it, but only the seed adapter is written; the heavier adapter
is **not** implemented:

| Seam | Built adapter | Not built (future adapter) |
|---|---|---|
| `SearchProvider` | `PostgresSearchProvider` (tsvector/ts_rank) | `ElasticsearchSearchProvider` |
| `GeoProvider` | `PostgisGeoProvider` (GiST) | tiled/sharded geo (H3/geohash) |
| `EventStream` | `InProcessEventStream` (simulator) | `KafkaEventStream` |

The value delivered here is that swapping later is an **adapter change, not a
controller/service rewrite** — not that the swap is done.

**Tier 3 — Documented only (zero code in this deliverable).** Described as the
path in §10.1 but deliberately **not** stood up, because running it for a
2000-row corpus would be disproportionate infrastructure:

- Elasticsearch / pgvector semantic search
- Kafka streaming for trending
- `articles` partitioning by date, read replicas, multi-AZ
- H3/geohash geo-sharding, CDN/HTTP response caching

This is the balance the architecture decision
(`../docs/architecture-approaches.md`) settled on: **scale-ready by discipline and
seams, without over-provisioning today.**

---

## 11. Traceability

| Requirement | Design section |
|---|---|
| R1–R5 retrieval + ranking | §3, §4.1, §6 |
| R6 trending | §3.2, §4.4 |
| R7 LLM routing + multi-intent | §4.2, §5.1 |
| R8 summaries | §4.3, §5.2 |
| R9–R11 events/score/cache | §3.2, §4.4 |
| Response contract | §4, DTOs in §2.1 |
| Validation | §4.1 (controllers) |
| Reliability/NFRs | §7 |
| Scalability | §1.1, §10 |
| Caching | §4.3, §4.4, §5.1, §10.3 |
