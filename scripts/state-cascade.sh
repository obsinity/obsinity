#!/usr/bin/env bash
set -euo pipefail

BASE_URL="http://localhost:8086/events/publish"
SERVICE_ID="payments"

profiles=250
states=("ACTIVE" "INACTIVE" "BLOCKED")
sleep_between=0.06

for i in $(seq 1 $profiles); do
  profile_id=$(printf "profile-%03d" "$i")
  for state in "${states[@]}"; do
    payload=$(cat <<JSON
{
  "resource": {
    "service": { "name": "$SERVICE_ID" }
  },
  "event": {
    "name": "user_profile.updated",
    "id": "$(uuid)"
  },
  "time": {
    "startedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
    "endedAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  },
  "attributes": {
    "user": {
      "profile_id": "$profile_id",
      "email": "${profile_id}@example.com",
      "status": "$state"
    }
  }
}
JSON
)
    curl -s -o /dev/null -w "%{http_code} $profile_id -> $state\n" \
      -H "Content-Type: application/json" \
      -d "$payload" \
      "$BASE_URL"
    sleep "$sleep_between"
  done
done
