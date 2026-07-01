# Implementation Tasks — Contextual News Retrieval System

Phased, ordered task breakdown for the implementation agent. Each task lists
concrete deliverables and a **Done when** check. Build strictly in phase order —
each phase leaves the app runnable. Traces to [`requirements.md`](./requirements.md)
(Rn) and [`design.md`](./design.md) (§n).

**Stack:** Java 21, Spring Boot 3.3, PostgreSQL 16 + PostGIS, Redis, Anthropic
Claude, Testcontainers. This repo is **public** — no secrets committed;
`ANTHROPIC_API_KEY` from env only.

**Scalability rule (applies to every phase):** the ~2000-row file is *seed data*.
Build for millions of articles + high request volume without a rewrite —
stateless API tier, indexed queries (never sort/scan the corpus in memory),
keyset pagination, layered caching, bounded pools, and **provider seams**
(`SearchProvider`, `GeoProvider`, `EventStream`) so heavier backends swap in as
adapters. Heavier engines are designed-for, not stood-up at seed size. See
`design.md` §1.1 and §10.

---

## Phase 0 — Project scaffold & infra

- [ ] **0.1 Maven project + `pom.xml`** — Spring Boot 3.3.x parent, Java 21. Deps:
      `spring-boot-starter-web`, `-validation`, `-actuator`, `-data-jpa`,
      `-data-redis`, `postgresql`, `hibernate-spatial`, `ch.hsr:geohash`,
      `resilience4j-spring-boot3`, `bucket4j-core`, `micrometer-registry-prometheus`,
      `lombok`; test: `spring-boot-starter-test`, `testcontainers` (postgresql,
      junit-jupiter), `com.redis:testcontainers-redis`.
- [ ] **0.2 `NewsApplication`** — `@SpringBootApplication` entry point.
- [ ] **0.3 `application.yml`** — config tree from design §8; env overrides;
      `ANTHROPIC_API_KEY` from env; `news.llm.enabled` flag.
- [ ] **0.4 `docker-compose.yml`** — `postgis/postgis:16` + `redis:7` with healthchecks.
- [ ] **0.5 `.env.example`** — documents `ANTHROPIC_API_KEY` (real `.env` gitignored).
- [ ] **0.6 `README.md`** — how to run (Docker + Maven), endpoints, env vars.

**Done when:** `mvn spring-boot:run` starts an empty app; Actuator `/health` is UP.

---

## Phase 1 — Domain, schema & ingestion (R-data, §3, §9.4)

- [ ] **1.1 Schema init** — `articles` + `user_events` tables, PostGIS extension,
      all indexes (GIST geog, GIN tsv, GIN categories, source/score/date). Via
      Flyway migration or `schema.sql`.
- [ ] **1.2 Entities** — `Article` (geography column via hibernate-spatial,
      `categories` as `text[]`, `search_tsv`), `UserEvent`.
- [ ] **1.3 `NewsJsonRecord`** — Jackson binding for source JSON shape.
- [ ] **1.4 `DataLoader`** (`ApplicationRunner`) — **stream-parse** the JSON
      (Jackson streaming, constant memory) and **batch upsert by `id`** (~500/batch,
      idempotent), populate `search_tsv`, set `geog` only when lat/lon valid and not
      `(0,0)`, skip+log malformed rows, skip load if already populated
      (`skip-if-populated`). Same path must work for a 2000-row and a 20M-row file.
- [ ] **1.5 `ArticleRepository` / `EventRepository`** — JPA base; list queries use
      **keyset (cursor) pagination** `ORDER BY (rank_key, id)` (no `OFFSET`).

**Done when:** startup loads exactly 2000 rows; re-running produces no duplicates;
rows with null/`(0,0)` geo have null `geog`; ingestion memory is bounded (streaming).

---

## Phase 2 — Retrieval endpoints & ranking (R1–R5, §4.1, §6)

- [ ] **2.1 DTOs** — `ArticleDto` (incl. `category` array, optional `distance_km`),
      `NewsResponse{articles,total,query,degraded}`, `ErrorResponse`.
- [ ] **2.2 `GlobalExceptionHandler`** (`@ControllerAdvice`) — uniform error
      envelope; 400/404/429/503 mapping; no stack traces; empty result → 200.
- [ ] **2.3 Validation** — `limit` clamp `[1,50]` default 5; `threshold` `[0,1]`
      default 0.7; `lat`/`lon`/`radius` bounds; non-blank `category`/`source`/`query`.
- [ ] **2.4 `/category`** (R1) — `categories @> ARRAY[lower(:cat)]`; order pubdate desc.
- [ ] **2.5 `/score`** (R2) — `relevance_score >= :threshold`; order score desc.
- [ ] **2.6 `/source`** (R4) — `lower(source_name)=lower(:src)`; order pubdate desc.
- [ ] **2.7 `SearchProvider` seam + `/search`** (R3, §1.1) — define
      `SearchProvider` interface; implement `PostgresSearchProvider` blending
      `0.6*ts_rank(search_tsv, plainto_tsquery(:q)) + 0.4*relevance_score`; `ILIKE`
      fallback when tsquery empty. (Elasticsearch impl is a future adapter.)
- [ ] **2.8 `GeoProvider` seam + `/nearby`** (R5, §1.1) — define `GeoProvider`
      interface; implement `PostgisGeoProvider` (`ST_DWithin` + `ST_Distance`);
      return `distance_km`; exclude null-geo rows; km→m at the edge.
- [ ] **2.9** All strategies append `id` tie-break and use **keyset pagination**;
      wire controllers to `NewsService` (services depend on seams, not on Postgres).

**Done when:** R1–R5 return correctly ranked, correctly limited envelopes;
validation rules enforced; distance correct against a known city pair.

---

## Phase 3 — LLM integration & enrichment (R7, R8, §4.2, §4.3, §5)

- [ ] **3.1 `LlmClient` interface** + models (`QueryUnderstanding`, extraction result).
- [ ] **3.2 `ClaudeLlmClient`** — Anthropic HTTP; **tool-use/JSON-schema** extraction
      → `{entities,intent[],keywords}`; separate `summarize(title,description)` call
      (cheap model, small max_tokens, "no new facts" instruction). Explicit timeout.
- [ ] **3.3 `HeuristicIntentExtractor`** — keyword→category/source matching + location
      detection; used when LLM disabled/failed/no key.
- [ ] **3.4 `CacheService`** — layered **local (Caffeine) → Redis** (§10.3), null-safe
      get/put with single-flight on hot keys; used for query-understanding
      (`hash(normQuery+lat/lon)`) and summaries (`sha256(title+description)`, no TTL).
- [ ] **3.5 `SummaryService`** — bounded-parallel enrich; Redis → DB → LLM; lazily
      persist `llm_summary`; set `degraded:true` when a summary can't be produced.
- [ ] **3.6 `QueryService` + `/query`** (R7) — call extractor, **AND-combine**
      multi-intent filters, choose most-specific ranking rule, delegate to
      `NewsService`; honor direct `intent`/`entities` params (skip LLM).
- [ ] **3.7 Wire enrichment** into all endpoints so every article has `llm_summary`.

**Done when:** `/query` routes single + multi-intent correctly; every article has a
summary or `null`+`degraded`; with key unset, everything still returns 200.

---

## Phase 4 — Trending (R6, R9–R11, §4.4)

- [ ] **4.1 `EventStream` seam + persistence** (§1.1, §10.4) — define `EventStream`
      interface (publish/consume); implement `InProcessEventStream`;
      `EventRepository.findByGeohashPrefix(prefix, since)`. (Kafka impl is a future
      adapter — `TrendingService` must not depend on the concrete stream.)
- [ ] **4.2 `EventSimulator`** (`ApplicationRunner`) — synthetic events via
      `EventStream`: hot-article bias, city clusters, recent-skewed timestamps;
      dedupe by `event_id`; geohash-7.
- [ ] **4.3 `TrendingService`** — score `Σ typeWeight·e^(-λ·Δt)·geoFactor`
      (view=1/click=3/share=5, 6h half-life); top-N; enrich.
- [ ] **4.4 Geohash cache** — key `trending:{prefix(precision 5)}`, TTL ~60s,
      single-flight on miss.
- [ ] **4.5 `/trending`** (R6) — controller + validation (lat/lon required).

**Done when:** `/trending` returns location-aware ranked results served from the
geohash cache on repeat calls.

---

## Phase 5 — Reliability, resilience & observability (§7, NFRs)

- [ ] **5.1 Resilience4j** — circuit breaker + retry (backoff+jitter) around `LlmClient`;
      opens to heuristic fallback.
- [ ] **5.2 Bulkhead** — bounded summary executor (`AsyncConfig`), separate from web threads.
- [ ] **5.3 Timeouts** — LLM HTTP (3–5s) + DB `statement_timeout` + bounded connection pool.
- [ ] **5.4 Rate limiting** (Bucket4j) — per-client token bucket on `/query` (LLM-backed).
- [ ] **5.5 Redis-down handling** — cache optional everywhere; degrade + metric, no failure.
- [ ] **5.6 Health probes** — Actuator liveness + **readiness** (readiness gates on
      Postgres; LLM/Redis loss = degraded).
- [ ] **5.7 Observability** — correlation-id filter, structured logs, Micrometer/Prometheus
      metrics (latency p50/p95/p99, error rate, LLM cache hit-rate, breaker state,
      Redis availability, trending cache hit-rate).

**Done when:** with Redis stopped and the LLM stubbed to fail, all retrieval
endpoints return 200 `degraded:true`; Postgres down returns 503; metrics exposed.

---

## Phase 6 — Testing (§9)

- [ ] **6.1 Testcontainers base** — real Postgres/PostGIS + Redis via `@ServiceConnection`.
- [ ] **6.2 Ingestion tests** — 2000 rows; idempotent re-run; geo/null handling.
- [ ] **6.3 Endpoint contract tests** — R1–R7 success + error envelopes; validation rules.
- [ ] **6.4 Geo unit tests** — known city-pair distances vs PostGIS.
- [ ] **6.5 Search ranking tests** — blended score ordering.
- [ ] **6.6 LLM fault-injection tests** — throw/timeout/bad-JSON → 200 + `degraded`.
- [ ] **6.7 Trending tests** — score math, geohash cache hit/miss, single-flight.
- [ ] **6.8 Load smoke test** — bounded pools + caching hold tail latency.

**Done when:** `mvn verify` is green with all suites against real containers.

---

## Phase 7 — Docs & polish

- [ ] **7.1 README** — final run instructions, sample requests/responses for every
      endpoint (incl. multi-intent `/query` and direct-input escape hatch).
- [ ] **7.2 API examples** — curl snippets / a small collection.
- [ ] **7.3 Config reference** — document every `news.*` property + env vars.
- [ ] **7.4 Sanity pass** — confirm no secrets committed; `.env` gitignored;
      `docker-compose up` + `mvn spring-boot:run` works from a clean clone.

**Done when:** a fresh clone runs end-to-end following the README, with and without
an `ANTHROPIC_API_KEY`.

---

## Dependency graph

```
P0 ─▶ P1 ─▶ P2 ─┬─▶ P3 ─┬─▶ P5 ─▶ P6 ─▶ P7
                └─▶ P4 ─┘
```

P3 and P4 both depend on P2 and can proceed in parallel; P5 depends on P3+P4; P6
validates everything; P7 closes out.

## Definition of done (whole system)

All acceptance criteria in `requirements.md` §7 met: R1–R7 correctly ranked and
enriched; `/nearby` and `/trending` geospatially correct; validation + uniform
errors; idempotent ingestion; graceful degradation with any single dependency
down; Testcontainers suite green; clean-clone runnable per README; no secrets in
the public repo.
