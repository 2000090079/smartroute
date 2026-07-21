# SmartRoute backend (Spring Boot)

The API server for SmartRoute: GPT-4o intent/sentiment classification,
confidence-gated keyword fallback, and a rule-based routing engine, built
with Spring Boot (WebFlux) on Java 21.

## Architecture

```
                POST /api/chat
Browser  ─────────────────────────▶  Spring WebFlux (SmartRouteController)
(React)                                   │
   ▲                                      ▼
   │                              IntentService: GPT-4o classifies
   │                              {intent, sentiment, confidence}
   │                              via a raw WebClient call to OpenAI's
   │                              chat/completions endpoint (JSON mode).
   │                              Falls back to keyword matching if no
   │                              API key, the call fails, or the
   │                              response doesn't parse/validate.
   │                                      │
   │                                      ▼
   │                              RoutingEngine: confidence-gate, then
   │                              rule-based decision (see below)
   │                                      │
   │                     ┌────────────────┼────────────────┐
   │                     ▼                ▼                ▼
   │              AuditService      MetricsService    IntentService
   │              writes the        increments        generates a direct
   │              decision to       Micrometer/        answer if decision
   │              Mongo (or an      Prometheus          == AUTO_RESOLVE
   │              in-memory list    counters/
   │              if Mongo isn't    histogram
   │              reachable)
   │
   └── GET /api/stats, /api/audit-log  ◀── Dashboard.jsx / AuditLog.jsx
                                            poll every 5s + refetch
                                            immediately after a send
```

**Routing rules** (`routing/RoutingEngine.java`):
1. If the LLM's confidence is below `CONFIDENCE_THRESHOLD` (default `0.7`),
   discard its intent/sentiment and re-derive them with keyword matching
   instead (`IntentService::keywordFallback`).
2. `intent == urgent` → **ESCALATE** (regardless of sentiment — this is an
   explicit branch, not a fallthrough).
3. `intent == billing` → **BILLING_QUEUE**
4. `intent == technical` → **TECH_QUEUE**
5. `intent == general` → **AUTO_RESOLVE** (GPT-4o answers the customer
   directly; falls back to a canned message if no API key is set or the
   call fails)

`decide()` switches exhaustively over the `Intent` enum, so there's no
"unrecognized intent" branch to maintain — the compiler guarantees every
case is handled.

Every decision — input, intent, sentiment, confidence, decision,
timestamp, resolution — is written to the audit store and counted in
Micrometer/Prometheus metrics before the response is returned.

**Data store**: MongoDB (via the reactive driver) is used as the
stand-in for what would be a DynamoDB table in production (same access
pattern: append-only writes, read back sorted by time). If no Mongo is
reachable at startup, the app transparently falls back to an in-memory
store so it still runs for a demo — this is logged loudly and is not
meant for anything beyond local dev or a single Cloud Run instance.

**Metrics** (`GET /metrics`, Prometheus text format via Micrometer):
`smartroute_messages_total{decision}`, `smartroute_intent_total{intent}`,
`smartroute_intent_source_total{source}` (llm vs. keyword_fallback), and
a `smartroute_confidence_score` histogram. The dashboard itself reads
`GET /api/stats`, a JSON aggregate computed from the audit store, since a
browser UI wants percentages, not a scrape format.

## Project structure

```
backend/
├── pom.xml
├── Dockerfile                            Multi-stage Maven build → JRE runtime, Cloud Run-ready
└── src/main/java/com/smartroute/
    ├── SmartRouteApplication.java         Main class, CORS filter
    ├── config/
    │   └── AppConfig.java                 Env-based config (@ConfigurationProperties)
    ├── model/
    │   ├── Intent.java, Sentiment.java, Decision.java, IntentSource.java   Enums (Jackson @JsonValue-backed)
    │   ├── ChatRequest.java               POST /api/chat request body
    │   ├── IntentResult.java              Internal intent-detection result
    │   ├── RoutingResult.java             API response + Mongo document (audit_log collection)
    │   └── StatsResponse.java             GET /api/stats response
    ├── intent/
    │   └── IntentService.java             GPT-4o via WebClient + keyword fallback
    ├── routing/
    │   └── RoutingEngine.java             Routing decision rules
    ├── audit/
    │   └── AuditService.java              Reactive Mongo audit log + in-memory fallback
    ├── metrics/
    │   └── MetricsService.java            Micrometer/Prometheus metrics
    └── web/
        └── SmartRouteController.java      REST endpoints
```

## How to run

Requires **JDK 21+** and **Maven** (`brew install openjdk@21 maven` on macOS).

```bash
cd backend
mvn spring-boot:run
```

Or build and run the jar directly:

```bash
cd backend
mvn package -DskipTests
java -jar target/smartroute-backend.jar
```

- **No `OPENAI_API_KEY`?** The app still runs — every message goes through
  the keyword-matching fallback instead of GPT-4o. Good enough to exercise
  the full routing/dashboard/audit flow without an API key or cost.
- **No local MongoDB?** Also fine — it falls back to an in-memory store
  (logged loudly at startup). To use real Mongo:
  `docker run -d -p 27017:27017 mongo`, then restart the backend.
- `http://localhost:8080/health` for a liveness probe,
  `http://localhost:8080/metrics` for the Prometheus scrape endpoint.

### Quick smoke test

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "My internet is down and I am furious, fix this ASAP!"}'
```

Expect `"intent":"urgent"`, `"sentiment":"negative"`, `"decision":"ESCALATE"`.

## Configuration

Environment variables, bound via `src/main/resources/application.yml`
into `config/AppConfig.java`:

| Env var | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | *(empty)* | GPT-4o intent detection; empty → keyword fallback |
| `OPENAI_MODEL` | `gpt-4o` | Model name passed to the chat/completions call |
| `MONGO_URI` | `mongodb://localhost:27017` | Audit log connection string |
| `MONGO_DB_NAME` | `smartroute` | Database name (collection: `audit_log`) |
| `CONFIDENCE_THRESHOLD` | `0.7` | Below this, LLM output is discarded for keyword matching |
| `CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated allowed origins |
| `PORT` | `8080` | HTTP port (Cloud Run sets this automatically) |

## API

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Liveness/readiness probe |
| `POST` | `/api/chat` | Submit a customer message, get back the routing decision |
| `GET` | `/api/audit-log?limit=50` | Recent audit entries (`limit` 1–500) |
| `GET` | `/api/stats` | Aggregated dashboard numbers |
| `GET` | `/metrics` | Prometheus scrape endpoint |

## Deploying to Cloud Run

The repo root `Dockerfile` builds this service (multi-stage: Maven →
`eclipse-temurin:21-jre-jammy`), with `cloudbuild.yaml` wiring it into
Cloud Build → Artifact Registry → Cloud Run. See
[`../gcp/README-GCP.md`](../gcp/README-GCP.md) for the full deployment
guide (Secret Manager, monitoring alerts, and an optional MongoDB →
Firestore migration).
