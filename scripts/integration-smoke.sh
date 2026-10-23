#!/usr/bin/env bash
set -Eeuo pipefail
trap 'echo "Integration smoke command failed at line $LINENO (exit $?)." >&2' ERR

root="$(cd "$(dirname "$0")/.." && pwd)"
scenario="${1:-}"
transport="${2:-}"
expectation="${3:-}"
if [[ "$expectation" != investigation && "$expectation" != dependency-failure ]]; then
  echo "Usage: bash scripts/integration-smoke.sh {normal|fallback} {db|rabbit} {investigation|dependency-failure}" >&2
  exit 2
fi
compose() { bash "$root/scripts/integration.sh" "$scenario" "$transport" "$@"; }
command -v jq >/dev/null
command -v curl >/dev/null
compose config --quiet
for worker in worker-1 worker-2; do
  worker_ready=false
  for ((i=0; i<30; i++)); do
    container="$(compose ps -q "$worker")"
    if [[ -z "$container" || "$(docker inspect --format '{{.State.Running}}' "$container")" != true ]]; then
      echo "$worker is not running." >&2
      exit 1
    fi
    started="$(docker inspect --format '{{.State.StartedAt}}' "$container")"
    if docker logs --since "$started" "$container" 2>&1 | grep '^WORKER_READY$' >/dev/null; then
      worker_ready=true
      break
    fi
    sleep 1
  done
  if [[ "$worker_ready" != true ]]; then
    echo "$worker startup deadline exceeded." >&2
    exit 1
  fi
done

tmp="$(mktemp -d)"
trap 'rm -f "$tmp/cookies" "$tmp/body" "$tmp/result"; rmdir "$tmp"' EXIT
backend_port="$(compose port backend 8080)"
agent_port="$(compose port agent-api 8001)"
mcp_port="$(compose port agent-api 8002)"
backend="http://$backend_port"
agent="http://$agent_port"
mcp="http://$mcp_port/mcp"
agent_token="$(compose exec -T api-1 printenv AGENT_SERVICE_TOKEN)"
mcp_token="$(compose exec -T mcp-1 printenv BUSINESS_MCP_AGENT_TOKEN)"

request() {
  local expected="$1" method="$2" url="$3" auth="${4:-}" code
  local args=(--silent --show-error --max-time 15 --request "$method"
    --cookie "$tmp/cookies" --cookie-jar "$tmp/cookies" --output "$tmp/result"
    --write-out '%{http_code}')
  if [[ "$method" == POST ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "@$tmp/body")
  fi
  code="$(
    {
      printf 'header = "Accept: application/json, text/event-stream"\n'
      if [[ -n "$auth" ]]; then
        printf 'header = "Authorization: Bearer %s"\n' "$auth"
      elif [[ "$method" == POST ]]; then
        printf 'header = "X-XSRF-TOKEN: %s"\n' "$(awk '$6=="XSRF-TOKEN"{print $7}' "$tmp/cookies")"
      fi
    } | curl --config - "${args[@]}" "$url"
  )"
  if [[ "$code" != "$expected" ]]; then
    echo "Unexpected HTTP $code (expected $expected): $method ${url%%\?*}" >&2
    exit 1
  fi
}

request 200 GET "$agent/health"
jq -e '.readyForTasks == true' "$tmp/result" >/dev/null
request 401 GET "$agent/agent/metrics"
request 401 GET "$backend/api/auth/me"
printf '{"username":"viewer","password":"liveguard-local"}' > "$tmp/body"
request 200 POST "$backend/api/auth/login"

channel=20000000-0000-4000-8000-000000000001
request 200 GET "$backend/api/channels/$channel/lives/current"
live="$(jq -er '.data.liveId' "$tmp/result")"
existing="$(compose exec -T db psql -U liveguard -d liveguard -At -v ON_ERROR_STOP=1 -c \
  "SELECT count(*) FROM platform.messages WHERE live_id='$live'::uuid")"
if [[ "$existing" != 0 ]]; then
  echo "Smoke requires an unused seeded live. Use a fresh isolated project, not production data." >&2
  exit 1
fi
client_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
jq -n --arg id "$client_id" \
  '{clientMessageId:$id,text:"너 꺼져 integration connection test"}' > "$tmp/body"
request 201 POST "$backend/api/lives/$live/messages"
message="$(jq -er '.data.messageId' "$tmp/result")"
row="$(compose exec -T db psql -U liveguard -d liveguard -At -F '|' -v ON_ERROR_STOP=1 -c \
  "SELECT o.task_id,o.case_id FROM guard.agent_task_outbox o JOIN platform.messages m ON m.case_id=o.case_id WHERE m.id='$message'::uuid")"
IFS='|' read -r task case_id <<< "$row"
if [[ -z "$task" || -z "$case_id" ]]; then
  echo "Real browser message did not enqueue a case." >&2
  exit 1
fi

ready=false
for ((i=0; i<60; i++)); do
  receipt="$(compose exec -T db psql -U liveguard -d liveguard -At -v ON_ERROR_STOP=1 -c \
    "SELECT status FROM guard.agent_task_outbox WHERE task_id='$task'::uuid")"
  execution="$(compose exec -T agent-db psql -U agent -d agent -At -v ON_ERROR_STOP=1 -c \
    "SELECT status FROM agent_inbox.jobs WHERE operation_id='run:$task'")"
  expected_status=WAITING_REVIEW
  if [[ "$expectation" == dependency-failure ]]; then
    expected_status=FAILED
  fi
  if [[ "$receipt" == ACCEPTED && "$execution" == "$expected_status" ]]; then
    ready=true
    break
  fi
  if [[ "$receipt" == FAILED || "$receipt" == UNKNOWN ||
        "$execution" == FAILED || "$execution" == UNKNOWN || "$execution" == NEEDS_REVIEW ]]; then
    echo "Smoke failed: Outbox=$receipt Agent=$execution (inspect safe observation SQL)." >&2
    exit 1
  fi
  sleep 1
done
if [[ "$ready" != true ]]; then
  echo "Smoke deadline exceeded: Outbox=$receipt Agent=$execution" >&2
  exit 1
fi
request 200 GET "$agent/agent/tasks/$task" "$agent_token"
jq -e --arg task "$task" --arg status "$expected_status" \
  '.taskId == $task and .status == $status' "$tmp/result" >/dev/null
if [[ "$expectation" == dependency-failure ]]; then
  jq -e '.code == "MODEL_UNAVAILABLE"' "$tmp/result" >/dev/null
else
  has_judgment="$(compose exec -T db psql -U liveguard -d liveguard -At -v ON_ERROR_STOP=1 -c \
    "SELECT judgment IS NOT NULL FROM guard.live_case_state WHERE case_id='$case_id'::uuid")"
  if [[ "$has_judgment" != t ]]; then
    echo "WAITING_REVIEW without a stored AI judgment is not an investigation success." >&2
    exit 1
  fi
fi
request 200 GET "$agent/agent/metrics" "$agent_token"
worker_id="$(compose exec -T agent-db psql -U agent -d agent -At -v ON_ERROR_STOP=1 -c \
  "SELECT last_worker_id FROM agent_inbox.jobs WHERE operation_id='run:$task' AND attempts>0")"
if [[ "$worker_id" != integration-worker-1 && "$worker_id" != integration-worker-2 ]]; then
  echo "No expected Worker execution owner recorded." >&2
  exit 1
fi

trace="$(uuidgen | tr '[:upper:]' '[:lower:]')"
request 403 GET "$backend/internal/cases/$case_id"
jq -n '{jsonrpc:"2.0",id:1,method:"initialize",params:{protocolVersion:"2025-03-26",capabilities:{},clientInfo:{name:"liveguard-integration-smoke",version:"1"}}}' > "$tmp/body"
request 401 POST "$mcp"
request 200 POST "$mcp" "$mcp_token"
jq -e '.result.protocolVersion != null and .error == null' "$tmp/result" >/dev/null
jq -n --arg caseId "$case_id" --arg trace "$trace" \
  '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"get_moderation_case",arguments:{caseId:$caseId,traceId:$trace}}}' > "$tmp/body"
request 200 POST "$mcp" "$mcp_token"
jq -e --arg caseId "$case_id" --arg trace "$trace" \
  '(.result.structuredContent // (.result.content[0].text | fromjson)) as $r |
   .error == null and .result.isError != true and
   $r.data.caseId == $caseId and $r.traceId == $trace and $r.data.handlingRoute == "CONTEXT_REVIEW"' \
  "$tmp/result" >/dev/null
echo "PASS (connection scope): real BE case -> common Agent API -> shared Worker; common MCP -> actual BE read."
echo "caseId=$case_id taskId=$task Outbox=$receipt Agent=$execution worker=$worker_id"
if [[ "$expectation" == dependency-failure ]]; then
  echo "EXPECTED DEPENDENCY FAILURE: MODEL_UNAVAILABLE; not an Agent investigation success."
fi
echo "Scope: no human approval or kick execution proof; inspect fallback separately."
