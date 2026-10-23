#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
setup="$root/deploy/integration"
# This tracked file contains only public image tags and source revisions.
source "$setup/versions.env"

if [[ "${1:-}" == build ]]; then
  agent_repo="${AGENT_REPO:-$root/../liveguard-agent}"
  git -C "$root" cat-file -e "$BE_REVISION^{commit}"
  git -C "$agent_repo" cat-file -e "$AGENT_REVISION^{commit}"
  git -C "$root" archive "$BE_REVISION" | docker build --label "org.opencontainers.image.revision=$BE_REVISION" -t "$BE_IMAGE" -
  git -C "$agent_repo" archive "$AGENT_REVISION" | docker build --label "org.opencontainers.image.revision=$AGENT_REVISION" -t "$AGENT_IMAGE" -
  exit 0
fi

scenario="${1:-}"
transport="${2:-}"
if [[ "$scenario" != normal && "$scenario" != fallback ]] ||
   [[ "$transport" != db && "$transport" != rabbit ]] || [[ $# -lt 3 ]]; then
  echo "Usage: bash scripts/integration.sh build | {normal|fallback} {db|rabbit} <compose arguments...>" >&2
  exit 2
fi
shift 2
env_file="${INTEGRATION_ENV_FILE:-$setup/.env}"
if [[ ! -f "$env_file" ]]; then
  echo "Missing private integration environment: copy deploy/integration/.env.example to .env and configure it." >&2
  exit 2
fi
args=(--project-name "${INTEGRATION_PROJECT_NAME:-liveguard-integration}"
  --env-file "$setup/versions.env" --env-file "$env_file"
  -f "$setup/compose.yaml" -f "$setup/$scenario.yaml")
if [[ "$transport" == rabbit ]]; then
  args+=(-f "$setup/rabbit.yaml")
fi
export BE_IMAGE AGENT_IMAGE
docker compose "${args[@]}" "$@"
