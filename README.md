# Contextual News Retrieval System

A REST API that ingests ~2000 news articles, retrieves them through five ranked
strategies plus a trending feed, uses an LLM (Anthropic Claude) to understand
natural-language queries and route them, and enriches every result with an
LLM-generated summary.

**Stack:** Java 21 · Spring Boot 3.5 · PostgreSQL 16 + PostGIS · Redis · Anthropic
Claude · Testcontainers.

> The `data/news_data.json` seed (2000 rows) is *seed data*, not the design
> target. The system is built to scale to millions of articles via indexed
> queries, keyset pagination, layered caching, bounded pools, and provider seams.
> See [`plan/design.md`](plan/design.md) §10.

---

## Prerequisites

- Java 21 (`java -version` → 21.x)
- Maven 3.9+
- Docker (for `docker-compose` infra and the Testcontainers test suite)

## Run

1. Copy env template and (optionally) add your Claude key:

   ```bash
   cp .env.example .env
   # edit .env → set ANTHROPIC_API_KEY (leave blank to run in heuristic mode)
   ```

2. Start Postgres/PostGIS + Redis:

   ```bash
   docker compose up -d
   ```

3. Run the app (loads the seed data on first startup):

   ```bash
   set -a && source .env && set +a   # export env vars
   mvn spring-boot:run
   ```

4. Verify it is up:

   ```bash
   curl localhost:8080/actuator/health
   ```

Running **without** an `ANTHROPIC_API_KEY` is fully supported — the app falls
back to a deterministic heuristic intent extractor and returns `llm_summary:null`
with `degraded:true`. Retrieval still returns 200.

## Endpoints

Base path: `/api/v1/news`. All accept `limit` (default 5, clamped `[1,50]`).

| Endpoint | Params | Ranking |
|---|---|---|
| `GET /category` | `category` (req) | publication_date desc |
| `GET /score` | `threshold` (default 0.7) | relevance_score desc |
| `GET /search` | `query` (req) | blended ts_rank + relevance_score |
| `GET /source` | `source` (req) | publication_date desc |
| `GET /nearby` | `lat`, `lon` (req), `radius` km (default 10) | distance asc |
| `GET /trending` | `lat`, `lon` (req) | trending score desc |
| `GET /query` | `query` (req), `lat`, `lon`, `intent`, `entities` | LLM-routed |

> The seed corpus is entirely in western/central India (lat 15.6–22.5, lon
> 72.6–80.9). Use Indian coordinates for `/nearby` and `/trending`, e.g. Mumbai
> `lat=19.07&lon=72.88`.

More detailed request/response examples are added in Phase 7.

## Configuration

All tunables live under `news.*` in `src/main/resources/application.yml` and are
overridable by environment variables (see `.env.example`).

## Tests

```bash
mvn verify   # requires Docker (Testcontainers provisions Postgres/PostGIS + Redis)
```
