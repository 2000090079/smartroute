# SmartRoute

[![Deployable on Google Cloud Run](https://img.shields.io/badge/Deployable%20on-Google%20Cloud%20Run-4285F4?logo=googlecloud&logoColor=white)](gcp/README-GCP.md)

An AI-powered contact center routing system. A customer types a message into
a chat widget; GPT-4o classifies its intent, sentiment, and confidence; a
rule-based routing engine decides where it goes (auto-resolved, billing
queue, tech queue, or escalated to a human); and every decision is logged
and reflected in a live dashboard.

## Architecture

```
                POST /api/chat
Browser  ─────────────────────────▶  Spring WebFlux (SmartRouteController)
(React)                                   │
   ▲                                      ▼
   │                              IntentService: GPT-4o classifies
   │                              {intent, sentiment, confidence}
   │                              via a WebClient call to OpenAI's
   │                              chat/completions endpoint (JSON mode).
   │                              Falls back to keyword matching if
   │                              no API key, the call fails, or the
   │                              response doesn't parse/validate.
   │                                      │
   │                                      ▼
   │                              RoutingEngine: confidence-gate, then
   │                              rule-based decision (see below)
   │                                      │
   │                     ┌────────────────┼────────────────┐
   │                     ▼                ▼                ▼
   │              AuditService      MetricsService     IntentService
   │              writes the        increments          generates a
   │              decision to       Micrometer/          direct answer
   │              Mongo (or an      Prometheus           if decision ==
   │              in-memory list    counters/            AUTO_RESOLVE
   │              if Mongo isn't    histogram
   │              reachable)
   │
   └── GET /api/stats, /api/audit-log  ◀── Dashboard.jsx / AuditLog.jsx
                                            poll every 5s + refetch
                                            immediately after a send
```

**Routing rules** (`backend/src/main/java/com/smartroute/routing/RoutingEngine.java`):
1. If the LLM's confidence is below `CONFIDENCE_THRESHOLD` (default `0.7`),
   discard its intent/sentiment and re-derive them with keyword matching
   instead (`IntentService::keywordFallback`).
2. `intent == urgent` → **ESCALATE** (regardless of sentiment — this is an
   explicit branch in `decide()`; see the routing bug below for the
   history).
3. `intent == billing` → **BILLING_QUEUE**
4. `intent == technical` → **TECH_QUEUE**
5. `intent == general` → **AUTO_RESOLVE** (GPT-4o answers the customer
   directly; falls back to a canned message if no API key is set or the
   call fails)

`Intent` is an enum and `decide()` switches over it exhaustively, so
there's no "unrecognized intent" case to handle defensively — it's
unreachable at compile time.

Every decision — input, intent, sentiment, confidence, decision, timestamp,
resolution — is written to the audit store and counted in Micrometer/
Prometheus metrics before the response is returned.

**Data store**: MongoDB (via the reactive driver) is used as the stand-in
for what would be a DynamoDB table in production (same access pattern:
append-only writes, read back sorted by time). If no Mongo is reachable
at startup, the app transparently falls back to an in-process list so it
still runs for a demo — this is logged loudly and is not meant for
anything beyond local dev or a single Cloud Run instance.

**Metrics** (`GET /metrics`, Prometheus text format via Micrometer):
`smartroute_messages_total{decision}`, `smartroute_intent_total{intent}`,
`smartroute_intent_source_total{source}` (llm vs. keyword_fallback), and a
`smartroute_confidence_score` histogram. The dashboard itself reads
`GET /api/stats`, a JSON aggregate computed from the audit store, since a
browser UI wants percentages, not a scrape format.

### Project structure

```
smartroute/
├── backend/
│   ├── pom.xml
│   └── src/main/java/com/smartroute/
│       ├── SmartRouteApplication.java  Main class, CORS filter
│       ├── config/AppConfig.java        Env-based configuration
│       ├── model/                        Intent/Sentiment/Decision enums + DTOs
│       ├── intent/IntentService.java     GPT-4o intent/sentiment detection + keyword fallback
│       ├── routing/RoutingEngine.java    Routing decision engine (confidence gate + rules)
│       ├── audit/AuditService.java       Reactive Mongo audit log with in-memory fallback
│       ├── metrics/MetricsService.java   Micrometer/Prometheus counters + histogram
│       └── web/SmartRouteController.java REST endpoints: /api/chat, /api/stats, /api/audit-log, /metrics
└── frontend/src/
    ├── App.jsx          Tab navigation (Chat / Dashboard / Audit Log)
    ├── ChatWidget.jsx    Sends messages, renders routing result
    ├── Dashboard.jsx      Live stats, polls /api/stats every 5s
    └── AuditLog.jsx        Table view of /api/audit-log
```

## How to run

### Backend

Requires **JDK 21+** and **Maven** (`brew install openjdk@21 maven` on macOS).

```bash
cd backend
mvn spring-boot:run
```

- **No `OPENAI_API_KEY`?** The app still runs — every message goes through
  the keyword-matching fallback instead of GPT-4o. Good enough to exercise
  the full routing/dashboard/audit flow without an API key or cost.
- **No local MongoDB?** Also fine — it falls back to an in-memory store
  (logged at startup). To use real Mongo:
  `docker run -d -p 27017:27017 mongo`, then restart the backend.
- `http://localhost:8080/health` for a liveness probe,
  `http://localhost:8080/metrics` for the Prometheus scrape endpoint.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

Visit `http://localhost:5173`. The Vite dev server proxies `/api` and
`/metrics` to `http://localhost:8080` (see `vite.config.js`), so both must
be running.

### Quick smoke test

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "My internet is down and I am furious, fix this ASAP!"}'
```

Expect `intent: urgent`, `sentiment: negative`, `decision: ESCALATE`.

## Bugs found and fixed during development

### Routing bug — `urgent` intent with non-negative sentiment depended on an unrelated catch-all

**Where**: routing decision logic (`backend/src/main/java/com/smartroute/routing/RoutingEngine.java`)

The stated rule is "urgent + negative → ESCALATE." An early version of the
routing function encoded exactly that as a single conditional, leaving
`urgent` messages with neutral or positive sentiment to fall through to a
defensive default at the bottom of the function (intended only for
unrecognized intent values). Testing `decide()` directly with a
low-confidence `urgent`/`neutral` result surfaced the issue: the result
was correct, but for the wrong, undocumented reason — nothing in the code
stated "urgent always escalates," so the behavior was one refactor away
from silently breaking (e.g. adding a fifth intent type). Fixed by giving
`intent == URGENT` its own explicit branch with a comment explaining the
decision. The current implementation goes a step further: `Intent` is a
Java enum and `decide()` switches over it exhaustively, so an
"unrecognized intent" case isn't just handled defensively anymore — it's
unreachable at compile time.

### Frontend bug — browser autofill corrupted chat messages on submit

**Where**: `frontend/src/ChatWidget.jsx`

Manual testing of the chat widget in a browser showed that typing
`"My card was charged twice for the same order, please refund"` and
pressing Enter submitted `"...please refundhi"` — extra characters
appended to the end. The `<input>` had no `autoComplete` attribute, so the
browser's form autofill (keyed by field heuristics, not scoped to this
input alone) inserted a remembered value when Enter both accepted an
autofill suggestion and submitted the form in the same keystroke.
Reproduced consistently before the fix. Fixed by adding
`autoComplete="off"` to the input. Note: Chrome doesn't fully honor
`autocomplete="off"` for every autofill heuristic, so this class of bug is
worth watching if the input is ever given a generic `name` like
`"message"` or `"text"`.

### API bug — unbounded `limit` query param could return wrong data instead of failing

**Where**: `GET /api/audit-log` (`SmartRouteController.java`, `AuditService.java`)

The `limit` query param was originally unbounded. The in-memory fallback
path's naive list slicing silently misbehaves on out-of-range values —
a negative limit can drop items from the wrong end instead of erroring,
and zero can return an empty list without complaint — rather than
failing loudly or returning something sane. Fixed by validating the
bound explicitly (`1` to `500`) and returning `422 Unprocessable Entity`
on violation; `SmartRouteController.auditLog()` enforces this directly
before querying the audit store.

## Known limitations (not bugs, just scope cuts for a local demo)

- The in-memory audit fallback is per-instance and non-persistent — fine
  for a single Cloud Run instance or local run, not for multiple
  concurrently-scaled instances.
- No auth on any endpoint; this is a local prototype, not internet-facing.
- `AUTO_RESOLVE` answers are not fact-checked against any knowledge base —
  GPT-4o (or the canned fallback message) answers from the prompt alone.
- Keyword fallback does substring matching with no negation handling, so
  `"Nothing urgent, just wondering about your hours"` gets classified
  `urgent` purely because the word "urgent" appears in it. This is
  inherent to keyword matching as a *fallback*, not the primary path —
  GPT-4o's contextual understanding avoids this when it's available and
  confident.

## GCP Deployment

SmartRoute's backend deploys to [Cloud Run](https://cloud.google.com/run),
built via Cloud Build and pushed to Artifact Registry, with secrets held in
Secret Manager and alerting through Cloud Monitoring. An optional migration
path off MongoDB onto Firestore is also documented.

Full step-by-step instructions, prerequisites, and troubleshooting live in
[`gcp/README-GCP.md`](gcp/README-GCP.md). Quick start:

```bash
export PROJECT_ID=YOUR_PROJECT_ID
gcloud config set project "${PROJECT_ID}"
./gcp/setup-gcp.sh              # enable APIs, create infra, first deploy
./gcp/secret-manager-setup.sh   # create OPENAI_API_KEY, MONGODB_URI, JWT_SECRET
```

See [`gcp/README-GCP.md`](gcp/README-GCP.md) for the full guide, including
Cloud Build auto-deploy setup, Cloud Monitoring alert policies
(`gcp/monitoring-alerts.yaml`), and the MongoDB → Firestore migration
(`gcp/firestore-schema.md`).
