# Deploying SmartRoute to Google Cloud Platform

This guide walks through deploying SmartRoute's Spring Boot backend to
Cloud Run, backed by Secret Manager, Cloud Build, and Cloud Monitoring. It
assumes the repository root (this file lives in `gcp/README-GCP.md`,
one level below the repo root).

## Architecture

```
GitHub (push to main)
        │
        ▼
   Cloud Build  ───▶  Artifact Registry  ───▶  Cloud Run
 (cloudbuild.yaml)     (Docker image)        (smartroute-backend)
                                                     │
                                                     ▼
                                              Secret Manager
                                          (OPENAI_API_KEY, MONGODB_URI,
                                                 JWT_SECRET)
                                                     │
                                                     ▼
                                           Firestore  /  MongoDB
                                        (gcp/firestore-schema.md,
                                          or existing Mongo URI)
                                                     │
                                                     ▼
                                            Cloud Monitoring
                                      (gcp/monitoring-alerts.yaml:
                                       error rate, latency, max instances)
```

The frontend (`frontend/`) is a static Vite/React build and is not part
of this backend deployment — deploy it separately to Firebase Hosting,
Cloud Storage + Cloud CDN, or any static host, and point it at the
Cloud Run service URL (set `VITE_API_BASE_URL` or update
`frontend/vite.config.js`'s proxy target for local dev vs. production
build).

## Prerequisites

- [gcloud CLI](https://cloud.google.com/sdk/docs/install) installed and
  up to date (`gcloud components update`)
- A GCP project created with billing enabled:
  ```bash
  gcloud projects create YOUR_PROJECT_ID
  gcloud billing projects link YOUR_PROJECT_ID --billing-account=YOUR_BILLING_ACCOUNT_ID
  ```
- Authenticated: `gcloud auth login`
- `git clone` of this repository, with this `gcp/` directory present

Set these once per shell session for the commands below:

```bash
export PROJECT_ID=YOUR_PROJECT_ID
export REGION=us-central1
gcloud config set project "${PROJECT_ID}"
```

## Section 1 — Initial setup

`gcp/setup-gcp.sh` enables every required API, creates the Artifact
Registry repository, ensures a Firestore database exists (optional —
see Section 5), creates the `smartroute-run-sa` service account with
minimal IAM roles, and deploys the first Cloud Run revision built
directly from source.

```bash
chmod +x gcp/setup-gcp.sh
./gcp/setup-gcp.sh
```

APIs enabled: Cloud Run, Cloud Build, Artifact Registry, Secret
Manager, Cloud Monitoring, Firestore, IAM.

The first run will warn that no secrets exist yet and deploy without
them — that's expected the very first time. Continue to Section 2, then
re-run the deploy command the script prints (or just push to `main`
once Cloud Build is wired up in Section 3).

## Section 2 — Configure secrets

`gcp/secret-manager-setup.sh` creates three Secret Manager secrets and
grants `smartroute-run-sa` read access to each:

- `OPENAI_API_KEY` — used by `IntentService.java` for GPT-4o intent
  classification. If left unset the app still runs on its
  keyword-matching fallback (see main `README.md`), so this can be a
  throwaway/dev key while testing the deployment.
- `MONGODB_URI` — connection string for the audit log store. Point it
  at MongoDB Atlas (or any reachable `mongod`) for a production
  deployment; Cloud Run has no local disk to run MongoDB in-container.
- `JWT_SECRET` — reserved for signing auth tokens once endpoint auth is
  added (the app has none today). Leave the prompt blank to
  auto-generate a random value.

```bash
chmod +x gcp/secret-manager-setup.sh
./gcp/secret-manager-setup.sh
```

You'll be prompted for each value (input is hidden, nothing is logged
to shell history). Re-run this script any time to rotate a secret — it
adds a new version rather than failing if the secret already exists.

Redeploy after adding secrets so Cloud Run picks them up:

```bash
gcloud run services update smartroute-backend \
  --region="${REGION}" \
  --set-secrets=OPENAI_API_KEY=OPENAI_API_KEY:latest,MONGO_URI=MONGODB_URI:latest,JWT_SECRET=JWT_SECRET:latest
```

## Section 3 — Deploy: manual vs. Cloud Build auto-deploy

**Manual deploy** (from your machine, builds via Cloud Build under the
hood):

```bash
gcloud run deploy smartroute-backend \
  --source=. \
  --region="${REGION}" \
  --service-account="smartroute-run-sa@${PROJECT_ID}.iam.gserviceaccount.com" \
  --memory=512Mi --cpu=1 --min-instances=0 --max-instances=10 \
  --port=8080 --allow-unauthenticated \
  --set-secrets=OPENAI_API_KEY=OPENAI_API_KEY:latest,MONGO_URI=MONGODB_URI:latest,JWT_SECRET=JWT_SECRET:latest \
  --set-env-vars=OPENAI_MODEL=gpt-4o,MONGO_DB_NAME=smartroute,CONFIDENCE_THRESHOLD=0.7
```

Or apply the declarative manifest directly:

```bash
export SERVICE_NAME=smartroute-backend
envsubst < gcp/cloud-run.yaml | gcloud run services replace - --region="${REGION}"
```

**Automatic deploy on push to `main`** via `cloudbuild.yaml` (build →
push to Artifact Registry → deploy to Cloud Run):

```bash
gcloud builds triggers create github \
  --name=smartroute-main-deploy \
  --repo-name=smartroute \
  --repo-owner=2000090079 \
  --branch-pattern='^main$' \
  --build-config=cloudbuild.yaml \
  --region="${REGION}"
```

This requires connecting the GitHub repository to Cloud Build first
(one-time, via the Cloud Console: Cloud Build → Triggers → Connect
Repository, or `gcloud builds repositories create` for the newer
2nd-gen connections). From then on, every push to `main` rebuilds and
redeploys automatically.

## Section 4 — Monitor with Cloud Monitoring

`gcp/monitoring-alerts.yaml` defines three alert policies: error rate
above 5%, p99 latency above 2000ms, and instance count hitting the
max-instances ceiling (10). All three notify a single email channel.

**1. Create the notification channel:**

```bash
gcloud beta monitoring channels create \
  --display-name="SmartRoute alerts" \
  --type=email \
  --channel-labels=email_address=YOUR_ALERT_EMAIL@example.com

# Copy the returned name (projects/.../notificationChannels/NNNNNNNNNN):
export NOTIFICATION_CHANNEL_ID=projects/${PROJECT_ID}/notificationChannels/NNNNNNNNNN
```

**2. Apply the three policies.** `gcp/monitoring-alerts.yaml` holds
three `---`-separated YAML documents; `gcloud`'s
`--policy-from-file` takes one policy per call, so substitute
`${SERVICE_NAME}`, `${PROJECT_ID}`, and `${NOTIFICATION_CHANNEL_ID}` with
`envsubst`, split on the `---` markers with `awk`, and apply each file:

```bash
export SERVICE_NAME=smartroute-backend

envsubst < gcp/monitoring-alerts.yaml > /tmp/smartroute-alerts-resolved.yaml

awk '
  BEGIN { n = 1; file = "/tmp/smartroute-alert-1.yaml" }
  /^---$/ { n++; file = "/tmp/smartroute-alert-" n ".yaml"; next }
  { print > file }
' /tmp/smartroute-alerts-resolved.yaml

for f in /tmp/smartroute-alert-*.yaml; do
  gcloud alpha monitoring policies create \
    --policy-from-file="$f" \
    --project="${PROJECT_ID}"
done
```

**3. View them:** Cloud Console → Monitoring → Alerting, or:

```bash
gcloud alpha monitoring policies list --project="${PROJECT_ID}" --format='table(displayName, enabled)'
```

Cloud Run's built-in metrics (`run.googleapis.com/request_count`,
`request_latencies`, `container/instance_count`) back all three
policies — no custom instrumentation needed beyond what Cloud Run
already emits. The app's own `GET /metrics` (Prometheus format, see
`MetricsService.java`) remains available for a Prometheus/Grafana stack
if you run one separately; it is not what these Cloud Monitoring alerts
read.

## Section 5 — Migrate MongoDB → Firestore (optional)

The deployment above works unmodified against MongoDB (Atlas or
self-hosted) via the `MONGODB_URI` secret. If you'd rather run fully on
managed GCP services with no external database to operate, see
[`gcp/firestore-schema.md`](firestore-schema.md) for the full collection
design (`routing_decisions`, `metrics`, `notifications`) and a
step-by-step migration plan, including a feature flag
(`AUDIT_BACKEND=mongo|firestore`) so you can cut over gradually instead
of all at once. This section is entirely optional — skip it if MongoDB
Atlas already fits your needs.

## Troubleshooting

- **`PERMISSION_DENIED` on secret access**: confirm
  `gcp/secret-manager-setup.sh` ran successfully and granted
  `roles/secretmanager.secretAccessor` to `smartroute-run-sa` — rerun
  the script, it's idempotent.
- **Cloud Run revision fails to start / health check fails**: check
  `gcloud run services logs read smartroute-backend --region="${REGION}"`.
  A common cause is `MONGODB_URI` pointing at an unreachable host — the
  app falls back to in-memory storage rather than crashing, so this
  alone won't fail the health check, but a malformed URI can throw at
  startup.
- **Build fails on `docker push`**: confirm the Artifact Registry
  repository exists (`gcloud artifacts repositories list`) and that
  Cloud Build's service account has `roles/artifactregistry.writer`
  (granted by default to the Cloud Build service account on the
  Artifact Registry API's first use).
