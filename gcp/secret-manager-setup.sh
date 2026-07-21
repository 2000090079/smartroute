#!/usr/bin/env bash
#
# gcp/secret-manager-setup.sh
#
# Creates the Secret Manager secrets SmartRoute needs and grants the
# Cloud Run runtime service account access to read them.
#
# Prerequisites:
#   - gcloud CLI installed and authenticated (`gcloud auth login`)
#   - `gcloud config set project <PROJECT_ID>` already run, or PROJECT_ID
#     exported below
#   - The `secretmanager.googleapis.com` API enabled (gcp/setup-gcp.sh does
#     this for you)
#
# Usage:
#   ./gcp/secret-manager-setup.sh
#
# The script prompts for each secret value interactively (nothing is
# echoed to the terminal or written to shell history) unless the
# corresponding environment variable is already set.

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
SERVICE_ACCOUNT_NAME="${SERVICE_ACCOUNT_NAME:-smartroute-run-sa}"
SERVICE_ACCOUNT_EMAIL="${SERVICE_ACCOUNT_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

if [[ -z "${PROJECT_ID}" ]]; then
  echo "ERROR: No GCP project set. Run 'gcloud config set project <PROJECT_ID>' first." >&2
  exit 1
fi

echo "Project:         ${PROJECT_ID}"
echo "Service account: ${SERVICE_ACCOUNT_EMAIL}"
echo

create_or_update_secret() {
  local secret_name="$1"
  local secret_value="$2"

  if gcloud secrets describe "${secret_name}" --project="${PROJECT_ID}" >/dev/null 2>&1; then
    echo "Secret '${secret_name}' already exists — adding a new version."
    printf '%s' "${secret_value}" | gcloud secrets versions add "${secret_name}" \
      --project="${PROJECT_ID}" \
      --data-file=-
  else
    echo "Creating secret '${secret_name}'."
    printf '%s' "${secret_value}" | gcloud secrets create "${secret_name}" \
      --project="${PROJECT_ID}" \
      --replication-policy=automatic \
      --data-file=-
  fi
}

prompt_secret() {
  local var_name="$1"
  local prompt_text="$2"
  local value="${!var_name:-}"

  if [[ -z "${value}" ]]; then
    read -r -s -p "${prompt_text}: " value
    echo
  fi
  printf '%s' "${value}"
}

# --- OPENAI_API_KEY: used by IntentService.java for GPT-4o intent detection ---
OPENAI_API_KEY_VALUE="$(prompt_secret OPENAI_API_KEY 'Enter OPENAI_API_KEY')"
create_or_update_secret "OPENAI_API_KEY" "${OPENAI_API_KEY_VALUE}"

# --- MONGODB_URI: connection string for the audit-log store (or Firestore
#     migration bridge, see gcp/firestore-schema.md). Maps to the app's
#     MONGO_URI env var via --set-secrets in cloudbuild.yaml / cloud-run.yaml. ---
MONGODB_URI_VALUE="$(prompt_secret MONGODB_URI 'Enter MONGODB_URI (e.g. mongodb+srv://user:pass@host/db)')"
create_or_update_secret "MONGODB_URI" "${MONGODB_URI_VALUE}"

# --- JWT_SECRET: reserved for signing/verifying auth tokens once endpoint
#     auth is added (see README "Known limitations" — no auth today). ---
JWT_SECRET_VALUE="${JWT_SECRET:-}"
if [[ -z "${JWT_SECRET_VALUE}" ]]; then
  read -r -s -p "Enter JWT_SECRET (leave blank to auto-generate a random 64-char secret): " JWT_SECRET_VALUE
  echo
  if [[ -z "${JWT_SECRET_VALUE}" ]]; then
    JWT_SECRET_VALUE="$(openssl rand -hex 32)"
    echo "Generated a random JWT_SECRET."
  fi
fi
create_or_update_secret "JWT_SECRET" "${JWT_SECRET_VALUE}"

echo
echo "Granting ${SERVICE_ACCOUNT_EMAIL} access to each secret..."

for secret in OPENAI_API_KEY MONGODB_URI JWT_SECRET; do
  gcloud secrets add-iam-policy-binding "${secret}" \
    --project="${PROJECT_ID}" \
    --member="serviceAccount:${SERVICE_ACCOUNT_EMAIL}" \
    --role="roles/secretmanager.secretAccessor" \
    --condition=None \
    >/dev/null
  echo "  granted roles/secretmanager.secretAccessor on ${secret}"
done

echo
echo "Done. Secrets created/updated: OPENAI_API_KEY, MONGODB_URI, JWT_SECRET"
echo "Cloud Run can now mount them as env vars with:"
echo "  --set-secrets=OPENAI_API_KEY=OPENAI_API_KEY:latest,MONGO_URI=MONGODB_URI:latest,JWT_SECRET=JWT_SECRET:latest"
