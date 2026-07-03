# Plan — Prompt Management & Logging/Observability

Status: **PLAN ONLY** (no code changes yet). Scope: make LLM prompts maintainable
and make runtime behaviour legible from logs. Traces to
[`requirements.md`](./requirements.md) §6 (observability) and
[`design.md`](./design.md) §5 (LLM) / §7 (observability).

---

## 1. Goals

1. **Prompts** are versioned, editable, and reviewable **without recompiling**, with
   a clean seam between prompt *text* and parsing *contract*.
2. **Logs** make it obvious, for any request, *what happened*: which strategy ran,
   what the LLM extracted, cache hit/miss, LLM call + outcome + latency — without
   leaking secrets/PII, and toggleable per-package at runtime.
3. No behavioural/API change; no new required infra. All additive and config-gated.

## 2. Current state (honest baseline)

**Prompts**
- Two prompts are inline `String`s in `ClaudeLlmClient`: `extractionSystemPrompt()`
  and the summarize `system` string. The tool/JSON schema is also inline.
- Consequence: any prompt tweak is a Java edit + recompile; no versioning; no
  golden tests; can't diff prompt-only changes cleanly.

**Logging**
- Good bones: `CorrelationIdFilter` (MDC `correlationId` + `X-Correlation-Id`
  header), log pattern includes the id; startup logs (ingest count, vocabulary,
  simulator).
- Gaps: on a *successful* `/query` nothing logs the extracted intent/entities/
  keywords, the routing decision, or the executed filter. LLM calls, cache
  hit/miss, and summary resolution are silent (only failures log, at DEBUG).
- Actuator exposes only `health,info,prometheus,metrics` — no `loggers`, so levels
  can't be flipped at runtime.
- Net: an evaluator "can't tell what's happening" from logs. Confirmed by the
  "why is /query empty" investigation (had to reverse-engineer via curl).

---

## PART A — Prompt management

### A1. Design

- **Externalize** the two prompts to classpath resources:
  - `src/main/resources/prompts/extraction-system.txt`
  - `src/main/resources/prompts/summary-system.txt`
- **`PromptService`** (`integration/llm/prompt/PromptService.java`):
  - Loads templates once at startup (fail-fast if missing) via `ResourceLoader`.
  - Exposes `String extractionSystem()` and `String summarySystem()` (+ a
    `render(name, Map<String,String> vars)` helper for placeholder substitution
    like `{title}` / `{description}` — used for the *user* message, not system).
  - Carries a **version** per prompt (see A3).
- **Config override** (in `NewsProperties.Llm.Prompts`, bound from
  `news.llm.prompts.*`): optional `extraction-path` / `summary-path` that, when
  set, load from an external file instead of the bundled classpath default — so a
  deployment swaps prompts without a rebuild. Defaults point at the bundled files.
- **Keep in code:** the tool/JSON `input_schema`, `tool_choice`, and response
  parsing stay in `ClaudeLlmClient` — they are the parser *contract*, not prose.
- **Injection hygiene (preserve):** system prompt = instructions only; the
  untrusted user query stays in the `user` message. `render()` must never
  interpolate user text into the system prompt. Add a unit test asserting this.

### A2. Templating
- Minimal placeholder substitution (`{name}` → value) — no heavyweight template
  engine. Summary user message becomes a template
  (`summary-user.txt`: `Title: {title}\nDescription: {description}`) for symmetry,
  or kept inline (decision: keep user-message assembly in code; only *system*
  prompts are externalized to start — smaller blast radius).

### A3. Versioning & observability hook
- Each prompt file gets a version, tracked as a constant/config
  (`extraction.v1`, `summary.v1`), or derived from a header comment/content hash.
- `PromptService` exposes `extractionVersion()` / `summaryVersion()`.
- `ClaudeLlmClient` logs the version at DEBUG with each call and tags the
  Micrometer timer (see Part B) so output quality can be correlated to a prompt
  version and rolled back.

### A4. Tests
- `PromptServiceTest` (pure unit): templates load, non-empty, placeholders render,
  system prompt contains no user-supplied text.
- Golden tests for extraction/summary remain LLM-independent: assert the *heuristic*
  extractor mapping and that `SummaryService` degrades correctly — do **not** call
  the real API in CI. (Real-LLM eval is a manual/optional harness, documented, not
  in the CI suite.)

### A5. Tasks (Part A)
- [ ] A5.1 Add `resources/prompts/extraction-system.txt` + `summary-system.txt`
      (verbatim current text) with a version header.
- [ ] A5.2 `NewsProperties.Llm.Prompts` (paths + version overrides); wire under
      `news.llm.prompts.*` in `application.yml`.
- [ ] A5.3 `PromptService` (load, cache, render, version, external-path override,
      fail-fast).
- [ ] A5.4 Refactor `ClaudeLlmClient` to inject `PromptService`; remove inline
      prompt strings; keep schema/parse in place.
- [ ] A5.5 `PromptServiceTest` + injection-hygiene test.
- **Done when:** prompts editable via file/config without recompiling; `mvn verify`
  green; `ClaudeLlmClient` no longer hardcodes prompt prose; behaviour unchanged.

---

## PART B — Logging & observability

### B1. Logging philosophy (levels policy)

Each level has a clear rule and concrete examples. **Exceptions:** unexpected ones
are logged at **ERROR with the stack trace** (server-side logs only — the
`GlobalExceptionHandler` already keeps stack traces out of API responses);
*expected/handled* failures that we recover from are **WARN without a stack trace**
(a one-line reason). Never log a stack trace for a normal degradation (LLM/Redis
fallback) — that creates false alarms.

| Level | Rule | Examples in this codebase |
|---|---|---|
| **ERROR** | Unexpected failure / bug / hard dependency down; needs attention. Include the exception + stack trace. | Unhandled exception in `GlobalExceptionHandler.handleUnexpected` (log `ex` with trace, return generic 500 body); `DataAccessException` → datastore down (log at ERROR, return 503); a `RuntimeException` escaping a non-degradable path. |
| **WARN** | Recoverable degradation or bad input we handled; one-line reason, **no** stack trace. | LLM call failed/breaker-open → heuristic fallback (`degraded=true`); Redis get/put failed → local-only; `Vocabulary` reload failed (will retry); malformed ingest row skipped; rate limit exceeded (429). |
| **INFO** | Lifecycle + **one access-log line per API request** (all endpoints). | Startup ingest ("2000 rows upserted"), simulator ("5000 events"); and for every request, on completion: `method=GET path=/api/v1/news/query status=200 results=0 degraded=false took=812ms` (plus `strategy`/`intent` for `/query`). Correlation id is already on every line. |
| **DEBUG** | Full request trace for troubleshooting. | Extracted `{intent, entities, keywords}` + source (LLM\|heuristic\|direct); resolved filters + chosen ranking; executed SQL; per-article summary path (cache\|db\|llm); prompt version. |
| **TRACE** | Lowest-level detail. | SQL bind parameters. |

Rules of thumb:
- An exception that becomes a **degraded 200** is WARN (expected), not ERROR.
- An exception that becomes a **5xx** is ERROR with stack trace.
- A **4xx** (validation/rate-limit) is DEBUG/WARN, never ERROR — it's client input,
  not a server fault (avoid alerting noise on bad requests).
- Log the exception object (so the trace is captured) only at ERROR; at WARN log
  `e.getMessage()` (or class + message), not the full trace.

### B2. Structured logging + MDC
- Add MDC fields around each request (in a small filter/interceptor or the
  existing `CorrelationIdFilter`): `path`, `method`; and around `/query`:
  `intent`, `strategy`, `degraded`. Cleared in `finally`.
- Decision: keep human-readable console pattern for local dev, but make the
  pattern include key MDC fields; optionally add a `logback-spring.xml` with a
  **JSON encoder** activated by a `logging.structured=true`/profile for prod
  (Spring Boot 3.4+ has native structured logging — `logging.structured.format.console=ecs`).
  Start with: enrich the existing pattern + MDC; JSON encoder as opt-in.

### B3. Where to add logs (concrete)
- **Access log (all endpoints), INFO:** a `RequestLoggingInterceptor` (or filter)
  emits one line per request on completion — `method`, `path`, `status`, latency,
  and, where available, `results` count + `degraded`. Applies to every
  `/api/v1/news/**` call (R1–R7 + trending), tied to the correlation id. This is
  the baseline "what was called and how it went" record a reviewer/operator sees.
- `QueryService.handle/route`: DEBUG detail (understanding, source, resolved
  filters, chosen ranking) — the per-request INFO summary fields (`intent`,
  `strategy`) are surfaced via MDC so they appear on the access-log line too.
- `QueryService.executeCombined` / providers: DEBUG the final SQL + filter set.
- `SummaryService.resolve`: DEBUG per-article resolution path (cache/db/llm) and
  enrich() summary counts (n summarized, n cached, n failed) at DEBUG; WARN only on
  systemic failure.
- `ClaudeLlmClient`: DEBUG request (model, prompt version, sizes — never the key),
  outcome (ok/'fallback'), latency; WARN on breaker-open/timeout.
- `CacheService`: keep Redis-failure WARN (currently DEBUG — promote to WARN once,
  rate-limited, so Redis outage is visible without spamming).
- Ingest/simulator: keep INFO summaries (already present).

### B4. Runtime toggling
- Expose Actuator **`loggers`** endpoint (`management.endpoints.web.exposure.include`
  += `loggers`). Then:
  `curl -X POST /actuator/loggers/com.inshorts.news -d '{"configuredLevel":"DEBUG"}'`
  flips detail on/off with no restart. (Guard: note in README that `loggers` is a
  mutating endpoint — fine locally; in prod put behind auth/network policy.)

### B5. Metrics (Micrometer) additions
- Timer `news.llm.extract` / `news.llm.summarize` (tags: outcome, prompt_version).
- Counter `news.query.intent` (tag: intent) to see routing distribution.
- Counter `news.summary.resolution` (tag: source=cache|db|llm).
- Existing cache hit/miss + breaker state already exposed; document them in README.

### B6. Secrets / PII policy
- Never log the API key (already only sent as header), full article bodies, or raw
  user PII. Log query text only at DEBUG. Add a checklist note + a test asserting
  the key never appears in a formatted log line.

### B7. Config
```yaml
logging:
  level:
    com.inshorts.news: INFO     # flip to DEBUG to see full request traces
  # optional structured JSON for prod:
  # structured: { format: { console: ecs } }
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics,loggers
```
- README "Troubleshooting/Observability" section: how to flip levels (env or
  `loggers`), how to trace one request by `X-Correlation-Id`, what metrics exist.

### B8. Tasks (Part B)
- [ ] B8.1 Level policy + enrich log pattern with MDC; add `path`/`method`/`intent`/
      `strategy`/`degraded` MDC (filter/interceptor).
- [ ] B8.1b `RequestLoggingInterceptor` — one INFO access-log line per API request
      (method, path, status, latency, results, degraded) for all `/api/v1/news/**`.
- [ ] B8.2 Add the DEBUG/INFO logs in QueryService, SummaryService, ClaudeLlmClient,
      providers (per B3).
- [ ] B8.3 Promote Redis-outage log to WARN (rate-limited/once).
- [ ] B8.4 Expose `loggers`; document runtime toggle + auth caveat.
- [ ] B8.5 Add Micrometer timers/counters (B5).
- [ ] B8.6 Optional `logback-spring.xml` JSON encoder gated by profile/property.
- [ ] B8.7 README "Observability & troubleshooting" section; secret-in-log test.
      **Docs are reviewer-facing:** written for an evaluator reading cold — explain
      *why* each level/field exists, give copy-paste commands to see a full request
      trace by correlation id, and show a sample annotated log line. Same tone for
      any docs touched (README, `docs/manual-testing.md`).
- [ ] B8.8 Audit `GlobalExceptionHandler` against the level policy: 5xx/unexpected
      → ERROR **with** stack trace; datastore-down 503 → ERROR; validation 400 &
      rate-limit 429 → DEBUG/WARN (no ERROR, no trace) so bad client input doesn't
      trigger error-rate alerts. (Currently 400/404/429 are not logged and 503/500
      log at ERROR — mostly aligned; add a DEBUG line for 4xx and confirm no trace
      on WARN-level degradations.)
- **Done when:** with `com.inshorts.news=DEBUG`, a single `/query` produces a
  coherent trace (understanding → routing → SQL → summaries → result) tied by
  correlation id; levels flip at runtime via `loggers`; no secrets in logs;
  `mvn verify` green.

---

## 3. Rollout order & risk

1. Part B first (logging) — it's what makes everything else debuggable, low risk,
   additive. Ship B1–B4 (levels, MDC, key logs, loggers) then B5–B7.
2. Part A (prompts) — externalize + PromptService + tests.
3. Both are additive, config-gated, and covered by `mvn verify`. No API/behaviour
   change. Non-goals: prompt A/B platform, log aggregation stack, DB-backed prompt
   registry (documented as scale-ups, not built) — same "seam-not-stack" philosophy
   as `design.md` §10.5.

## 4. Estimate
- Part B: ~1–1.5 hrs (mostly log statements + MDC + config + README).
- Part A: ~1 hr (files + PromptService + refactor + 2 tests).
- Verification: `mvn verify` (41 tests) stays green; add ~3 new unit tests.
