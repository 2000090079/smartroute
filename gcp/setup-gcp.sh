#!/usr/bin/env bash
#
# gcp/setup-gcp.sh
#
# One-shot setup for deploying SmartRoute to Google Cloud Platform:
#   1. Enables required APIs
#   2. Creates the Artifact Registry repository
#   3. Creates the Cloud Run runtime service account with minimal roles
#   4. Builds and deploys the initial Cloud Run revision from source
#
# Prerequisites:
#   - gcloud CLI installed (https://cloud.google.com/sdk/docs/install)
#   - `gcloud auth login` already run
#   - A GCP project created and billing enabled
#   - Run gcp/secret-manager-setup.sh FIRST (or right after this script) so
#     OPENAI_API_KEY / MONGODB_URI / JWT_SECRET exist before the deploy step
#
# Usage:
#   export PROJECT_ID=my-gcp-project
#   export REGION=us-central1          # optional, defaults to us-central1
#   ./gcp/setup-gcp.sh

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${REGION:-us-central1}"
REPOSITORY="${REPOSITORY:-smartroute}"
SERVICE="${SERVICE:-smartroute-backend}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-smartroute-run-sa}"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

if [[ -z "${PROJECT_ID}" ]]; then
  echo "ERROR: No GCP project set. Run 'export PROJECT_ID=<your-project-id>' or 'gcloud config set project <PROJECT_ID>' first." >&2
  exit 1
fi

echo "=== SmartRoute GCP setup ==="
echo "Project:    ${PROJECT_ID}"
echo "Region:     ${REGION}"
echo "Repository: ${REPOSITORY}"
echo "Service:    ${SERVICE}"
echo

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "--- 1. Enabling required APIs ---"
gcloud services enable \
  run.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  monitoring.googleapis.com \
  firestore.googleapis.com \
  iam.googleapis.com \
  --project="${PROJECT_ID}"

echo
echo "--- 2. Creating Artifact Registry repository ---"
if gcloud artifacts repositories describe "${REPOSITORY}" \
    --location="${REGION}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Repository '${REPOSITORY}' already exists in ${REGION}, skipping."
else
  gcloud artifacts repositories create "${REPOSITORY}" \
    --repository-format=docker \
    --location="${REGION}" \
    --description="SmartRoute backend container images" \
    --project="${PROJECT_ID}"
fi

echo
echo "--- 2b. Ensuring a Firestore database exists (optional; see gcp/firestore-schema.md) ---"
if gcloud firestore databases describe --database='(default)' \
    --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Firestore database already exists in this project, skipping."
else
  gcloud firestore databases create \
    --database='(default)' \
    --location="${REGION}" \
    --type=firestore-native \
    --project="${PROJECT_ID}"
fi

echo
echo "--- 3. Creating Cloud Run runtime service account ---"
if gcloud iam service-accounts describe "${SERVICE_ACCOUNT_EMAIL}" \
    --project="${PROJECT_ID}" >/dev/null 2>&1; then
  echo "Service account '${SERVICE_ACCOUNT_EMAIL}' already exists, skipping creation."
else
  gcloud iam service-accounts create "${SERVICE_ACCOUNT_NAME}" \
    --display-name="SmartRoute Cloud Run runtime" \
    --project="${PROJECT_ID}"
fi

echo "Granting minimal roles to ${SERVICE_ACCOUNT_EMAIL}..."

# Read access to Secret Manager secrets (scoped per-secret in
# gcp/secret-manager-setup.sh; this call is a fallback no-op if none
# exist yet).
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/secretmanager.secretAccessor" \
  --condition=None \
  >/dev/null

# Firestore read/write, needed only after the optional Mongo -> Firestore
# migration described in gcp/firestore-schema.md. Harmless to grant now.
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/datastore.user" \
  --condition=None \
  >/dev/null

# Write application logs/metrics — required for Cloud Run's own logging
# and for the custom metrics Cloud Monitoring alerts on.
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/logging.logWriter" \
  --condition=None \
  >/dev/null

gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${SERVICE_ACCOUNT_EMAIL}" \
  --role="roles/monitoring.metricWriter" \
  --condition=None \
  >/dev/null

echo
echo "--- 4. Building and deploying the initial Cloud Run revision ---"
echo "(Building from source in $(git -C "$(dirname "$0")/.." rev-parse --show-toplevel 2>/dev/null || echo .) via Cloud Build)"

SECRET_FLAGS=""
for secret in OPENAI_API_KEY MONGODB_URI JWT_SECRET; do
  if gcloud secrets describe "${secret}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    case "${secret}" in
      OPENAI_API_KEY) SECRET_FLAGS="${SECRET_FLAGS}OPENAI_API_KEY=OPENAI_API_KEY:latest," ;;
      MONGODB_URI)    SECRET_FLAGS="${SECRET_FLAGS}MONGO_URI=MONGODB_URI:latest," ;;
      JWT_SECRET)     SECRET_FLAGS="${SECRET_FLAGS}JWT_SECRET=JWT_SECRET:latest," ;;
    esac
  fi
done
SECRET_FLAGS="${SECRET_FLAGS%,}"

if [[ -z "${SECRET_FLAGS}" ]]; then
  echo "WARNING: no secrets found in Secret Manager yet. Run gcp/secret-manager-setup.sh" >&2
  echo "         first, then re-run 'gcloud run deploy' (command printed at the end)." >&2
fi

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

DEPLOY_ARGS=(
  run deploy "${SERVICE}"
  --source="${REPO_ROOT}"
  --region="${REGION}"
  --platform=managed
  --service-account="${SERVICE_ACCOUNT_EMAIL}"
  --memory=512Mi
  --cpu=1
  --min-instances=0
  --max-instances=10
  --port=8080
  --allow-unauthenticated
  --set-env-vars=OPENAI_MODEL=gpt-4o,MONGO_DB_NAME=smartroute,CONFIDENCE_THRESHOLD=0.7
  --project="${PROJECT_ID}"
  --quiet
)

if [[ -n "${SECRET_FLAGS}" ]]; then
  DEPLOY_ARGS+=(--set-secrets="${SECRET_FLAGS}")
fi

gcloud "${DEPLOY_ARGS[@]}"

echo
SERVICE_URL="$(gcloud run services describe "${SERVICE}" \
  --region="${REGION}" --project="${PROJECT_ID}" --format='value(status.url)')"

echo "=== Done ==="
echo "Service URL: ${SERVICE_URL}"
echo "Health check: ${SERVICE_URL}/health"
echo
echo "Next steps:"
echo "  - If the warning above fired, run gcp/secret-manager-setup.sh then redeploy."
echo "  - Wire up gcp/monitoring-alerts.yaml for alerting (see gcp/README-GCP.md)."
echo "  - Connect this repo to Cloud Build for auto-deploy on push (see cloudbuild.yaml)."
