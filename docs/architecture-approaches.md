# Architecture Decision Document — Contextual News Retrieval System

This document records the architecture decision for the Contextual News
Retrieval System. It evaluates seven candidate designs against the project's
requirements, explains the trade-offs behind each, and states the chosen
approach and why. It also captures the ranking design, LLM integration strategy,
edge-case analysis, reliability model, and the path to scale.

The intent is that a reader can understand not just *what* was built, but *why
this design was chosen over the alternatives* — including the ones that were
deliberately not taken.

---

## 0. Requirements the design must satisfy

1. **Ingest** ~2000 articles from `news_data.json` into a datastore.
2. **Retrieve** via five strategies, each with its own ranking rule:

   | Strategy | Filter | Rank by |
   |---|---|---|
   | `category` | category match | publication_date desc |
   | `score` | relevance_score ≥ threshold | relevance_score desc |
   | `search` | text match in title/description | relevance_score + text-match score |
   | `source` | source_name match | publication_date desc |
   | `nearby` | within radius (km) of lat/lon | distance asc (Haversine) |

3. **Understand** a natural-language query with an **LLM** → `{entities, intent[], keywords}`, then route to the right strategy. Intent can be **multi-valued** (e.g. `["category","source"]`).
4. **Enrich** each returned article with an LLM-generated `llm_summary`.
5. **Trending (bonus)** — simulate a user-event stream, compute a location-aware trending score, and cache results by geographic cluster.
6. Return a **consistent JSON envelope** (`articles`, `total`, `query`) with the top results.

**Cross-cutting requirements:** RESTful design, `/api/v1` versioning, robust
error handling, response metadata, and caching.

### Data characteristics that constrain the design

Verified directly against `data/news_data.json`:

- **2000 rows**, **32 distinct categories** with inconsistent labelling (`national`, `General`, `IPL_2025`, `Health___Fitness`), and **152 distinct sources**.
- Each article contains **`title` + `description` only — no full body**. URLs are frequently YouTube or AMP links, so the article body cannot be reliably crawled. Summaries must therefore be generated from the title and description.
- **`category` is an array** in the source data but is shown as a **string** in the task's example output. This inconsistency has to be reconciled explicitly.

---

## 1. How the approaches are evaluated

Each candidate is assessed on the axes that actually matter for this system:

| Criterion | Why it matters |
|---|---|
| **Geospatial quality** | `nearby` and `trending` need correct, indexed distance queries rather than full-scan in-app math |
| **Search quality** | `search` must blend a text-match score with `relevance_score` |
| **Local setup** | How easily the system can be brought up and exercised on a fresh machine |
| **Production-readiness** | Indexing, caching, and LLM-integration maturity — does the design hold up as a real service? |
| **Scalability headroom** | Whether the design extends to millions of articles and higher request volume |
| **Implementation complexity** | Amount of moving parts and code required to reach a working, polished state |
| **LLM cost & latency control** | Synchronous LLM calls are slow and costly; caching and batching are essential |

---

## 2. Candidate approaches

### Approach A — Spring Boot + PostgreSQL/PostGIS + Redis  ⭐ (chosen)

**Stack:** Java 21, Spring Boot 3.5 (Web, Data JPA, Validation, Actuator),
PostgreSQL 16 + **PostGIS** (geography column + GiST index) for
`nearby`/`trending`, **Postgres full-text search (`tsvector`/`ts_rank`)** for
`search`, **Redis** for the trending and LLM-response caches, **Anthropic
Claude** for entity/intent extraction and summaries, and **Testcontainers** to
provision Postgres and Redis so no database has to be installed by hand.

**Rationale:** A single datastore handles relational filtering, real geospatial
queries (`ST_DWithin`, `ST_Distance`), *and* text ranking — so every required
ranking rule maps to a first-class database feature rather than a hand-rolled
in-memory loop. PostGIS is the standard, well-proven answer to "articles within
10 km." Redis directly satisfies the caching requirement. The stack also aligns
with the technology preferences stated in the brief.

**Pros**
- Real spatial index → correct and fast `nearby`/`trending` that scale with the corpus.
- `search` ranking expressed as `ts_rank * w1 + relevance_score * w2` in one SQL query.
- One well-understood, ACID datastore; straightforward to reason about and operate.
- Testcontainers means the app comes up with only Docker present — no manual schema or DB setup.

**Cons**
- PostGIS plus hibernate-spatial adds some configuration overhead.
- Full-text search is solid but not Elasticsearch-grade (no built-in fuzzy/typo tolerance).
- A running Docker daemon is required for the container-provisioned database.

---

### Approach B — Spring Boot + MongoDB (2dsphere) + Redis

**Stack:** Java 21, Spring Boot 3.5, **MongoDB** with a `2dsphere` geo index and
a text index, Redis, Claude.

**Rationale:** The article shape is document-like. Mongo stores the `category`
array natively (no join table or array-column mapping), `$near`/`$geoNear`
handle `nearby`/`trending`, and `$text` handles search. The flexible document
model also suits the trending event stream.

**Pros**
- Native array/document model — no impedance mismatch with the source JSON.
- `$geoNear` returns distance directly and composes into aggregation pipelines (useful for trending).
- Flexible event documents; a well-trodden horizontal-sharding path.

**Cons**
- Mongo `$text` relevance (`textScore`) is coarse; blending it with `relevance_score` needs an aggregation stage and is fiddlier than `ts_rank`.
- Geo (`2dsphere`) and text indexes don't combine within a single query as cleanly as PostGIS + `tsvector`.
- Weaker fit for the specific "blended text + relevance" ranking the brief asks for.

---

### Approach C — Spring Boot + Elasticsearch (unified retrieval engine)

**Stack:** Java 21, Spring Boot 3.5, **Elasticsearch/OpenSearch** as the single
retrieval backend, Redis optional, Claude.

**Rationale:** Every endpoint is fundamentally "filter + rank," which maps
directly onto Elasticsearch `bool`/`function_score` queries: `geo_distance` for
nearby, BM25 `match` for search, `function_score` to blend BM25 with
`relevance_score`, and aggregations to compute trending from indexed events.

**Pros**
- Best-in-class search: BM25, fuzzy/typo tolerance, synonyms, highlighting.
- Geo, text, and numeric ranking combine in a single `function_score` query.
- Trending via terms/date-histogram aggregations is elegant and fast.
- Scales to very large corpora — this is what a production news feed typically uses.

**Cons**
- Heaviest component to operate; a JVM-hungry Elasticsearch node is significant infrastructure for a 2000-row dataset.
- Near-real-time (refresh-interval) write→read semantics add operational subtlety.
- Considerable capability is unused at this data size.

---

### Approach D — Spring Boot + PostgreSQL + **pgvector** (semantic search)

**Stack:** Approach A plus **pgvector** and an embedding model; the `search`
strategy becomes semantic (embed the query, cosine-KNN over article embeddings)
while PostGIS still serves geo.

**Rationale:** "Understand the nuances of a user's query" invites semantic
retrieval. Embeddings can surface *Elon Musk / Twitter* articles even without
exact term overlap. The LLM still extracts intent; only the search step upgrades
from lexical to vector similarity.

**Pros**
- Genuinely contextual retrieval — the strongest interpretation of the brief's intent.
- Still one datastore (Postgres) for vectors, geo, and relational data.
- Tolerant of the messy category/source labels, since semantic matching forgives label noise.

**Cons**
- Requires precomputing and storing ~2000 embeddings at ingest (extra cost and a batch step).
- Diverges from the literal "text-match score" the brief specifies (semantic ≠ lexical).
- More moving parts, and it depends on embedding-API availability.
- Best treated as an **optional upgrade to `search`** layered onto Approach A, not a base design.

---

### Approach E — Lightweight in-memory / embedded (H2 or plain collections)

**Stack:** Java 21, Spring Boot 3.5, JSON loaded into in-memory indexes (or
embedded H2), Haversine computed in Java, Caffeine for caching, Claude.

**Rationale:** 2000 rows fit comfortably in memory. Category/source hash maps,
sorted lists, and direct Haversine calculations cover the functional
requirements with zero external infrastructure.

**Pros**
- Simplest to build and to bring up — no Docker or external database, only a JDK.
- Fully deterministic and trivially testable.
- Entirely adequate for this dataset size.

**Cons**
- No real indexing, geospatial, or distributed-caching story — the weakest production model.
- Does not scale beyond a single node or a small dataset.
- Trending caching collapses to an in-process map rather than a shared cache.
- Best positioned as a **core-only fallback**, not the primary design.

---

### Approach F — Event-driven services (Kafka + stream processing) for trending

**Stack:** An ingestion service, a query/API service, and a **trending service**
consuming a **Kafka** user-event stream (or Redpanda), aggregated with Kafka
Streams / a windowed consumer, over Postgres/PostGIS + Redis, with Claude.

**Rationale:** The trending requirement is literally "simulate a stream of user
events." A production system streams views/clicks through Kafka and maintains
windowed, geo-bucketed trending scores. This is the most realistic trending
architecture.

**Pros**
- The most production-realistic trending: true streaming, windowing, and backpressure.
- Clear service boundaries, each independently scalable.
- Strong distributed-systems design.

**Cons**
- Substantial infrastructure and operational overhead relative to the dataset.
- Kafka for a 2000-row dataset is heavier than the problem warrants on its own.
- Best captured as the **documented scale-out design**, with trending simulated in-process for the deliverable.

---

### Approach G — Serverless / cloud-native (Lambda + API Gateway + DynamoDB + OpenSearch)

**Stack:** AWS Lambda (Java/Quarkus native to mitigate cold starts), API Gateway
for `/api/v1/news/*`, DynamoDB (with geo-hash keys) or OpenSearch for geo/text,
ElastiCache/DAX for caching, Bedrock/Claude for the LLM.

**Rationale:** Pay-per-use, auto-scaling, no servers to manage; Bedrock provides
managed Claude access.

**Pros**
- Elastic scale with no capacity planning; a clean cloud-native operational model.
- Managed LLM via Bedrock and a tidy IAM story.

**Cons**
- Not runnable locally without heavy emulation (LocalStack), which complicates development and review.
- DynamoDB has no native `ST_DWithin`; geo needs manual geo-hash sharding and more code.
- Cold starts and vendor lock-in; the slowest path to a demonstrable result.
- Disproportionate infrastructure for the problem at hand.

---

## 3. Comparison

Scores are 1 (poor) – 5 (excellent). "Simplicity" is rated so that 5 = simplest.

| Approach | Geo | Search | Local setup | Prod-readiness | Scale | Simplicity | Verdict |
|---|---|---|---|---|---|---|---|
| **A. Postgres/PostGIS + Redis** | 5 | 4 | 4 | 5 | 4 | 3 | ✅ **Chosen** |
| B. MongoDB | 4 | 3 | 4 | 4 | 4 | 3 | Strong alternative |
| C. Elasticsearch | 5 | 5 | 2 | 4 | 5 | 2 | Excellent search, heavy infra |
| D. pgvector semantic | 5 | 5 | 3 | 5 | 4 | 2 | ⭐ Optional upgrade on A |
| E. In-memory / H2 | 2 | 2 | 5 | 2 | 1 | 5 | Core-only fallback |
| F. Kafka services | 5 | 4 | 2 | 5 | 5 | 1 | Document as scale path |
| G. Serverless | 4 | 4 | 1 | 4 | 5 | 1 | Disproportionate here |

**Direction:** **Approach A** as the foundation, structured so that **D**
(pgvector) can be added to the `search` endpoint as a later enhancement, and
**F**'s streaming model is documented as the scale-out path while trending is
simulated in-process. This balances production-readiness against a simple local
setup.

---

## 4. LLM integration strategy

The LLM layer is a design axis independent of the datastore. There are two
distinct LLM jobs.

**(a) Query understanding → `{entities, intent[], keywords}`**
- **Structured output via tool use / JSON schema** (chosen) — Claude is constrained to return a typed object, avoiding brittle regex parsing and prose drift.
- Plain prompt + JSON parse — simpler but needs defensive parsing and repair.
- Few-shot prompting using the brief's own examples (e.g. *Elon Musk / Twitter / Palo Alto → `nearby`*) to pin down the intent taxonomy.

**(b) `llm_summary` per article**
- **On-demand + Redis cache keyed by `hash(title+description)`** (chosen) — only the returned articles are summarized, and results are cached indefinitely because articles are immutable. The first request pays the latency; repeats are instant.
- **Precompute at ingest (batch)** — summarize all 2000 up front so every response is fast, at the cost of a slow one-time job and wasted spend on articles that are never returned.
- **Hybrid** — summarize lazily and persist to the `articles` table so the cache survives restarts. This is the intended end state.

**Reliability patterns for the LLM layer**
- **Timeouts + fallback**: if the LLM is slow or unavailable, degrade gracefully — return results with `llm_summary: null` rather than failing the request.
- **Bounded parallelism** for summarizing the returned articles, to keep tail latency down.
- **Cache query-understanding** by `hash(normalized query)` (extraction is
  location-independent; the requester's lat/lon is applied at routing time).
- **Cost controls**: a cheaper model (e.g. Haiku) and small `max_tokens` for summaries.
- **Direct-input path**: `entities`/`intent` may be passed as query parameters to bypass the LLM entirely — the brief explicitly permits this, and it makes the system deterministic and usable without an API key.

---

## 5. Ranking design

- **category / source** — `ORDER BY publication_date DESC`, tie-broken on `relevance_score DESC`.
- **score** — `WHERE relevance_score >= :threshold ORDER BY relevance_score DESC`.
- **search** — combined score: `w_text * ts_rank(tsv, plainto_tsquery(:q)) + w_rel * relevance_score`. Both terms normalized to `[0,1]`; starting weights `w_text = 0.6`, `w_rel = 0.4`, configurable. Falls back to `ILIKE`-based scoring when the text vector is empty.
- **nearby** — `WHERE ST_DWithin(geog, :point, :radius_m) ORDER BY ST_Distance(geog, :point) ASC`; the computed `distance_km` is returned in the payload.
- **multi-intent** (e.g. `["category","source"]`) — filters are **AND-combined**, and results are ranked by the most specific matched strategy's rule.

---

## 6. Trending design (bonus)

- **Event model:** `{event_id, user_id, article_id, event_type(view|click|share|dwell), lat, lon, geohash, ts}`, with type weights `view=1`, `dwell=2`, `click=3`, `share=5`.
- **Simulator:** a component emits synthetic events biased toward a handful of "hot" articles, clustered around a few city coordinates, with timestamps skewed toward recent.
- **Score:** `trending = Σ(type_weight · e^(-λ·Δt)) · geo_factor`, where `Δt` is event age (recency decay, e.g. 6-hour half-life) and `geo_factor` rewards events near the requesting location (or restricts to a radius / matching geohash prefix).
- **Geo-clustering + cache:** bucket by **geohash prefix** (precision ~5 ≈ 5 km). Cache top-N per bucket in Redis under `trending:{geohashPrefix}` with a short TTL (e.g. 60 s) so hot locations serve from cache.
- **Refresh:** recompute per bucket on cache miss, or via a background job each TTL. The Kafka streaming variant (Approach F) is the documented scale path.

---

## 7. Edge cases & failure modes

**Input / validation**
- Missing or blank `query`, `category`, or `source` → 400 with a clear message.
- `lat`/`lon` out of range (`|lat|>90`, `|lon|>180`) or non-numeric → 400.
- Negative, zero, or very large `radius` → cap at a maximum (e.g. 500 km) to bound scans.
- `limit` ≤ 0, non-integer, or excessive → clamp to `[1, 50]`, default 5.
- `threshold` outside `[0,1]` → clamp or reject.
- Category/source label noise (`Technology` vs `technology`, `Health___Fitness`, `IPL_2025`, `General`) → normalize case and match case-insensitively; optionally maintain a canonical-category map.
- Injection in `search` → fully parameterized queries; `plainto_tsquery` sanitizes input.

**Data quirks**
- `category` is an array in the data but a string in the sample output → **return the array** (the honest superset) and document the deviation.
- Articles with null or `(0,0)` coordinates → excluded from `nearby`/`trending`, since `(0,0)` is in the ocean and would distort distance ranking.
- Duplicate or near-duplicate titles (same story across sources) → optional de-duplication of results.
- Future-dated or malformed `publication_date` → parsed defensively; unparseable values dropped or floored.
- Ranking ties (equal date/score) → deterministic tie-break by `id` so ordering and pagination are stable.

**LLM**
- No API key / provider down / rate-limited → fall back to keyword heuristics for intent and omit summaries; never fail the whole request.
- Invalid JSON or an unknown intent → validated against the allowed intent enum, defaulting to `search`.
- Prompt injection in the user query → the LLM only classifies; its output is never executed, and all database access is parameterized.
- Latency spikes → per-call timeout, bounded parallelism, and caching; degrade to a null summary.
- Hallucinated summaries → constrained by instruction to summarize only the provided title/description and introduce no new facts.

**Concurrency / caching**
- Cache stampede on a hot geohash → single-flight coalescing plus short TTL with jitter.
- Stale trending after an event burst → bounded TTL; eventual consistency is acceptable and documented.
- Redis down → cache treated as optional; values computed directly.

**Correctness**
- Distance uses PostGIS geography (spheroidal) for accuracy, unit-tested against known city-pair distances.
- Radius is km at the API boundary and meters inside PostGIS — converted at the edge and tested.
- Empty result sets → `{"articles": [], "total": 0, "query": ...}` with HTTP 200, not 404.

---

## 8. Scalability

| Concern | Current design | Scale-out path |
|---|---|---|
| **Corpus size** | 2000 rows in one Postgres | Partition by date; read replicas; move search to Elasticsearch/OpenSearch |
| **Geo queries** | PostGIS GiST index | Same, plus geohash sharding/tiling and CDN-cached regional feeds |
| **Trending events** | In-process simulator + Redis | Kafka ingest → windowed stream aggregation → materialized per-geohash tops (Approach F) |
| **LLM throughput** | Synchronous + Redis cache | Precompute summaries offline; async enrichment via a queue; batch API |
| **Read throughput** | Single node | Stateless API behind a load balancer; horizontal scale; Redis cluster; response caching |
| **Hot keys** | Geohash TTL cache | Consistent hashing, layered local + distributed caches, request coalescing |
| **Cost** | Cheaper model + caching | Cache hit-rate targets, cheaper summary models, precomputation |
| **Availability** | Single DB | Multi-AZ Postgres, Redis replication, graceful LLM/cache degradation |

**Principles held throughout:** keep the API tier stateless so it scales
horizontally; push filtering and ranking into indexed database queries rather
than paging rows into application memory; cache what is both expensive and
cacheable (LLM output, trending); and degrade gracefully whenever the LLM or
cache is unavailable.

---

## 9. Reliability & resilience

Reliability is treated as a first-class requirement because the system has **two
external dependencies that will eventually fail** (the LLM API and Redis) and one
that must never silently corrupt results (the datastore). The guiding principle:
**a degraded answer is better than an error, and no single dependency should be
able to take the API down.**

### 9.1 Dependency isolation & graceful degradation

| Dependency | Failure mode | Fail-soft behavior |
|---|---|---|
| **LLM (Claude)** | timeout, 429, 5xx, invalid JSON, no API key | Fall back to keyword-heuristic intent extraction; return articles with `llm_summary: null` and a `"degraded": true` flag in metadata. Never a 500. |
| **Redis** | down, timeout, eviction | Treat the cache as optional and compute directly from Postgres. Log and record a metric; do not fail the request. |
| **Postgres** | down, slow query | The source of truth — a hard failure returns 503 with a clear message and `Retry-After`. A connection-pool limit and statement timeout prevent one bad query from hanging the pool. |

The direct-input path doubles as a reliability feature: callers can pass
`intent`/`entities` directly to bypass the LLM entirely, which stays available
even with no external services running.

### 9.2 Resilience patterns

- **Timeouts everywhere** — every LLM HTTP call and database statement has an explicit timeout (LLM ~3–5 s, DB `statement_timeout`). No unbounded waits.
- **Retries with backoff + jitter** — transient LLM/Redis errors (429/503/timeouts) are retried a bounded number of times with exponential backoff, only on idempotent operations.
- **Circuit breaker** (Resilience4j) around the LLM client — after a failure threshold it opens and routes straight to the heuristic fallback for a cool-down window, avoiding retry storms against a struggling provider.
- **Bulkhead / bounded concurrency** — the summary executor is a bounded pool with a queue limit, separate from the web-server threads, so a slow LLM cannot exhaust request-handling capacity.
- **Single-flight / request coalescing** on hot cache keys (trending geohash) to prevent a thundering herd on expiry.
- **Rate limiting** — a per-client token bucket (e.g. Bucket4j) on the LLM-backed endpoint protects both the LLM budget and the provider.
- **Idempotency** — all retrieval endpoints are GET and side-effect-free; trending event ingestion de-duplicates on `event_id`.

### 9.3 Health, readiness & observability

- **Health checks** via Actuator, with distinct liveness and **readiness** probes; readiness gates on Postgres connectivity, while LLM/Redis loss is reported as *degraded*, not *down*.
- **Structured logging** with a per-request correlation id propagated into LLM calls.
- **Metrics** (Micrometer/Prometheus): request latency (p50/p95/p99), error rate, LLM cache hit-rate, LLM latency and failure counts, circuit-breaker state, Redis availability, and trending cache hit-rate — the core SLIs to alert on.
- **Consistent error contract** — a global handler maps exceptions to a uniform JSON body (`{error, message, status, timestamp, path}`) with correct HTTP codes (400 validation, 404 unknown route, 429 rate-limited, 503 datastore down). Stack traces are never leaked.

### 9.4 Data & startup reliability

- **Idempotent, resumable ingestion** — the loader upserts by `id`, so re-running never duplicates and a partial load can be safely retried; a count check skips ingestion on warm restarts.
- **Startup ordering** — the PostGIS extension and schema are created before ingestion, and the app does not report ready until ingestion completes.
- **Defensive parsing** — malformed rows (bad dates, null geo) are skipped and logged rather than aborting the whole load.
- **Deterministic tie-breaks** (by `id`) keep ranking and pagination stable and reproducible.

### 9.5 Testing for reliability

- **Testcontainers integration tests** against real Postgres/PostGIS + Redis, so geo and text ranking are verified against real engines rather than mocks.
- **Contract tests** for each endpoint's JSON envelope and error shapes.
- **LLM fault-injection tests** — the client is stubbed to throw, time out, or return malformed JSON, asserting the API still responds 200 with degraded metadata.
- **Geo unit tests** against known city-pair distances.
- **Load smoke test** — confirming bounded pools and caching hold tail latency under concurrency.

### 9.6 Reliability guarantees for this system

1. Any single external dependency (LLM, Redis) can be fully down and **core retrieval still works**.
2. Every endpoint returns a **consistent, well-formed** success or error envelope — never a raw 500 or a stack trace.
3. Ingestion is **idempotent and safe to re-run**.
4. All ranking is **deterministic and reproducible**.
5. Failures are **observable** (health, logs, metrics) and **bounded** (timeouts, breakers, bulkheads), so they cannot cascade.

---

## 10. Decision

**Chosen: Approach A** — Spring Boot + PostgreSQL/PostGIS + Redis + Claude,
with Postgres and Redis provisioned via Testcontainers. Specifically:

- `search` is implemented with `tsvector`/`ts_rank` now, with **pgvector (D) documented as the semantic upgrade**.
- Trending is simulated in-process with a geohash + Redis cache, with **Kafka streaming (F) documented as the scale-out path**.
- The LLM layer uses structured tool-use extraction and Redis-cached, lazily-persisted summaries, plus a **direct entity/intent input path** and graceful degradation.

**Why:** Approach A gives the best balance of a production-ready design (a real
spatial index, real text ranking, a real distributed cache, and a thoughtful LLM
integration) against a simple local setup (bring the app up with only Docker
present and exercise every endpoint), while matching the technology preferences
in the brief. The alternatives are either heavier infrastructure for little gain
at this data size (C, F, G), a weaker fit for the blended ranking the brief asks
for (B), or a weaker production model (E) — each remains a legitimate option if
priorities change, and the two most valuable (D and F) are folded in as a
documented upgrade and scale path.
