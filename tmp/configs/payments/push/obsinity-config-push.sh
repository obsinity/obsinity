#!/usr/bin/env bash
set -euo pipefail
# Usage: ./push/obsinity-config-push.sh services/payments
BASE_URL="${BASE_URL:-http://localhost:8086}"
TOKEN="${TOKEN:-}"
SERVICE_DIR="${1:?Pass service dir (e.g., services/payments)}"
if [[ ! -d "$SERVICE_DIR" ]]; then
  echo "Directory not found: $SERVICE_DIR" >&2
  exit 1
fi
header_auth=()
if [[ -n "$TOKEN" ]]; then
  header_auth=(-H "Authorization: Bearer $TOKEN")
fi
shopt -s globstar nullglob
echo "Pushing YAMLs under $SERVICE_DIR to $BASE_URL ..."
for f in "$SERVICE_DIR"/events/*/event.yaml "$SERVICE_DIR"/events/*/metrics/{counters,histograms}/**/*.y?(a)ml; do
  [[ -e "$f" ]] || continue
  echo "  -> $f"
  curl -sS -X POST "${BASE_URL}/api/admin/configs"         -H "Content-Type: application/yaml" "${header_auth[@]}"         --data-binary "@${f}"
  echo
done
echo "Done."
