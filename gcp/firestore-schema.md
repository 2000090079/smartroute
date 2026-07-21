# Firestore migration guide

SmartRoute's backend currently persists to MongoDB via the reactive
driver (`backend/src/main/java/com/smartroute/audit/AuditService.java`),
with an in-memory fallback when Mongo is unreachable (see
`backend/src/main/java/com/smartroute/config/AppConfig.java`'s
`mongoUri` and the app's README). This document describes an
**optional** migration path to Firestore (Native mode) for teams that
want a fully-managed GCP-native datastore instead of running or paying
for a MongoDB instance (self-hosted or Atlas) alongside Cloud Run.

Nothing in this document requires the migration — `gcp/cloud-run.yaml`
and `cloudbuild.yaml` both ship with `MONGO_URI` wired through Secret
Manager, so the app runs against MongoDB unmodified. Firestore is a
drop-in replacement for the same access patterns.

## Why Firestore maps cleanly here

| Current (MongoDB)                                   | Firestore equivalent                              |
|-------------------------------------------------------|-----------------------------------------------------|
| `audit_log` collection, append-only, sorted by `timestamp` | `routing_decisions` collection, same access pattern |
| Micrometer/Prometheus counters + histogram in `MetricsService.java` (in-process, not persisted) | `metrics` collection: periodic rollup documents, queryable across restarts/instances |
| No notification system today | `notifications` collection: durable outbox for the SNS-equivalent alerting described in `gcp/monitoring-alerts.yaml` and any future escalation webhook |

Firestore's serverless scaling matches Cloud Run's `min-instances: 0`
model — there's no connection pool to manage or idle database to pay
for, which a self-hosted `mongod` requires.

## Collection: `routing_decisions`

Mirrors the MongoDB `audit_log` collection and the `RoutingResult`
record in `backend/src/main/java/com/smartroute/model/RoutingResult.java`.
One document per processed chat message.

**Document ID**: the same UUID `AuditService.newId()` already generates
(`RoutingResult.id`) — reuse it as the Firestore document ID so writes
are naturally idempotent.

**Path**: `routing_decisions/{decision_id}`

```json
{
  "id": "3fa1c9de-8b2a-4e1f-9c77-2a6b9d4e7f10",
  "message": "My internet is down and I am furious, fix this ASAP!",
  "intent": "urgent",
  "sentiment": "negative",
  "confidence": 0.94,
  "intent_source": "llm",
  "decision": "ESCALATE",
  "resolution": null,
  "timestamp": "2026-07-21T14:32:07.481Z"
}
```

**Indexes**:
- Single-field index on `timestamp` (descending) — Firestore creates
  this automatically; needed for `AuditService.recent()`'s
  `ORDER BY timestamp DESC LIMIT N` equivalent.
- Composite index on `(decision ASC, timestamp DESC)` if you add a
  "recent escalations" dashboard filter — not required by the current
  `GET /api/audit-log` or `GET /api/stats` endpoints, which read the
  full window and aggregate in the controller.

**Query examples** (`com.google.cloud:google-cloud-firestore`, reactive
wrapper via `Mono.fromFuture`):

```java
// Equivalent of AuditService.recent(limit)
ApiFuture<QuerySnapshot> future = firestore.collection("routing_decisions")
        .orderBy("timestamp", Query.Direction.DESCENDING)
        .limit(limit)
        .get();

// Equivalent of AuditService.append(result)
firestore.collection("routing_decisions").document(entry.id()).set(entry);
```

## Collection: `metrics`

The current app computes Micrometer/Prometheus metrics in-process
(`MetricsService.java`) and never persists them — restart the process
and the counters reset, and with multiple Cloud Run instances each
instance's `/metrics` output only reflects its own traffic. Firestore
gives these numbers a durable, cross-instance home if you want
historical dashboards beyond what Cloud Monitoring's scrape of
`/metrics` already provides.

**Document ID**: `{metric_name}_{ISO-8601 hour bucket}`, e.g.
`smartroute_messages_total_2026-07-21T14`. One document per metric per
hour keeps writes to at most a handful of documents per hour regardless
of request volume (increment via a Firestore transaction/`FieldValue.increment`,
not one document per request).

**Path**: `metrics/{bucket_id}`

```json
{
  "metric_name": "smartroute_messages_total",
  "hour_bucket": "2026-07-21T14:00:00Z",
  "labels": {
    "decision": "ESCALATE"
  },
  "value": 132,
  "updated_at": "2026-07-21T14:58:03.221Z"
}
```

A confidence-score histogram (`smartroute_confidence_score` in
`MetricsService.java`) maps to a document per bucket boundary instead of
a single counter:

```json
{
  "metric_name": "smartroute_confidence_score",
  "hour_bucket": "2026-07-21T14:00:00Z",
  "buckets": {
    "0.1": 0, "0.2": 1, "0.3": 2, "0.4": 5, "0.5": 9,
    "0.6": 14, "0.7": 22, "0.8": 41, "0.9": 88, "1.0": 130
  },
  "sample_count": 130,
  "sum": 108.42,
  "updated_at": "2026-07-21T14:58:03.221Z"
}
```

**Write pattern**: increment counters with `FieldValue.increment(1)`
inside `MetricsService.record()` in addition to (not instead of) the
existing Micrometer calls, so `/metrics` keeps serving the live
Cloud-Monitoring-scraped view while Firestore accumulates the durable
history.

**Indexes**: none beyond the default — documents are looked up by ID
(`metric_name_hour_bucket`), not queried by range, in the common case.
Add a composite index on `(metric_name ASC, hour_bucket DESC)` only if
you build a "last N hours for this metric" dashboard query.

## Collection: `notifications`

The SNS-equivalent durable outbox. SmartRoute doesn't send notifications
today (see README "Known limitations"), but `RoutingEngine.decide()`
already identifies the moment that matters — an `ESCALATE` decision.
This collection is the schema for wiring that up: write a notification
document whenever `decision == ESCALATE`, and have a separate consumer
(Cloud Function on Firestore `onCreate`, or a Cloud Run job) deliver it
(email, Slack, PagerDuty, etc.) and mark it processed. This durable
write-then-deliver split is what SNS + a subscriber gives you in AWS;
Firestore + a trigger is the equivalent on GCP.

**Document ID**: auto-generated by Firestore (`add()`), since
notifications aren't looked up by a natural key.

**Path**: `notifications/{auto_id}`

```json
{
  "routing_decision_id": "3fa1c9de-8b2a-4e1f-9c77-2a6b9d4e7f10",
  "type": "ESCALATION",
  "channel": "email",
  "recipient": "oncall@example.com",
  "subject": "SmartRoute escalation: urgent/negative message",
  "body": "Customer message routed to human escalation. Intent: urgent, sentiment: negative, confidence: 0.94.",
  "status": "pending",
  "created_at": "2026-07-21T14:32:07.900Z",
  "delivered_at": null,
  "attempts": 0
}
```

**Status lifecycle**: `pending` → `delivered` | `failed`. A Cloud
Function triggered on document creation (`onCreate`) attempts delivery,
updates `status`/`delivered_at`/`attempts`, and retries `failed`
documents on a schedule (Cloud Scheduler + Cloud Run job, or a
Firestore TTL policy to expire stale `pending` docs after 24h).

**Indexes**: composite index on `(status ASC, created_at ASC)` to
efficiently query "oldest pending notifications" for a retry sweep.

## Migration steps

1. **Add the dependency**: `com.google.cloud:google-cloud-firestore` in
   `backend/pom.xml` alongside (not replacing)
   `spring-boot-starter-data-mongodb-reactive` until the migration is
   verified.
2. **New class** `FirestoreAuditService.java` implementing the same
   interface `AuditService` exposes today — `initStore()`,
   `append(result)`, `recent(limit)`, `allEntries()` — using the
   Firestore queries shown above. Keep the method signatures identical
   so `SmartRouteController.java` doesn't change.
3. **Feature-flag the backend** in `AppConfig.java`: an
   `auditBackend` property (`mongo` by default) bound from an
   `AUDIT_BACKEND` env var, selecting which implementation is wired up
   via a `@ConditionalOnProperty`-annotated `@Bean`. This lets you
   deploy the Firestore path to a Cloud Run revision with a small
   traffic split before cutting over fully.
4. **Backfill** existing MongoDB documents into `routing_decisions` with
   a one-off job that reads `AuditService.allEntries()` and writes each
   via the new class — the shared `RoutingResult` record means no field
   mapping is needed.
5. **Grant IAM**: the Cloud Run service account needs `roles/datastore.user`
   (already granted by `gcp/setup-gcp.sh`) to read/write Firestore.
6. **Cut over**: flip `AUDIT_BACKEND=firestore` via `--update-env-vars`
   on the Cloud Run service, verify `/api/audit-log` and `/api/stats`,
   then remove the Mongo dependency and `MONGO_URI` secret once
   confident.

Firestore Native mode is region-scoped at the project level (choose the
same region as Cloud Run, `us-central1`, when the database is first
created — `gcloud firestore databases create --location=us-central1`,
already run by `gcp/setup-gcp.sh` via the enabled `firestore.googleapis.com`
API) and cannot be changed after creation without deleting and
recreating the database.
