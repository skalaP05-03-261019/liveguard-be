#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
agent="${AGENT_REPO:-$root/../liveguard-agent}"
command="${1:-}"
suite="${2:-}"
env_file="${3:-}"
project="${4:-}"
remaining_suite=""
transport=db
scenario=normal
load_pattern=burst
case "$suite" in
  normal|loss|capacity) ;;
  receipt|race|stale) remaining_suite="$suite" ;;
  receipt-rabbit) remaining_suite=receipt; transport=rabbit ;;
  load-burst) remaining_suite=load ;;
  load-steady) remaining_suite=load; load_pattern=steady ;;
  deadline) remaining_suite=deadline; scenario=fallback ;;
  *) suite="" ;;
esac
if [[ $# != 4 || ( "$command" != run && "$command" != down ) ||
      -z "$suite" || "$env_file" != /* ||
      "$project" != liveguard-joint96-be78-* ]]; then
  echo "Usage: bash scripts/joint-be-e2e.sh {run|down} {normal|loss|capacity|receipt|receipt-rabbit|race|stale|load-burst|load-steady|deadline} /absolute/private.env liveguard-joint96-be78-<unique>" >&2
  exit 2
fi
source "$root/deploy/integration/versions.env"
if [[ "$(git -C "$agent" rev-parse HEAD)" != "$AGENT_REVISION" ]] ||
   ! git -C "$agent" diff --quiet HEAD -- app prompts Dockerfile pyproject.toml uv.lock .dockerignore; then
  echo "Check out the pinned clean Agent source before running joint BE verification." >&2
  exit 2
fi
cd "$agent"
export PYTHONPATH="$agent"
export PYTHONDONTWRITEBYTECODE=1
args=(--be-repo "$root" --env-file "$env_file" --project "$project")
if [[ "$suite" == loss ]]; then args+=(--action-loss); fi
if [[ -n "$remaining_suite" ]]; then
  args+=(--extended-faults --transport "$transport" --be-scenario "$scenario")
fi
joint() { uv run --locked python -m app.joint_test.run "$1" "${args[@]}" "${@:2}"; }
if [[ "$command" == down ]]; then
  joint down
  exit 0
fi
if [[ -e "$env_file" ]]; then
  echo "Use a new private environment and unused project; existing data is never reset." >&2
  exit 2
fi
if [[ -n "$(docker ps -aq --filter "label=com.docker.compose.project=$project")" ||
      -n "$(docker volume ls -q --filter "label=com.docker.compose.project=$project")" ]]; then
  echo "Project already has containers or volumes; choose an unused name without deleting evidence." >&2
  exit 2
fi
results="$root/integration-results"
mkdir -p "$results"
for suffix in agent backend recovery capacity remaining; do
  if [[ -e "$results/$project-$suffix.json" ]]; then
    echo "Existing report must not be overwritten: $project-$suffix.json" >&2
    exit 2
  fi
done
docker image inspect "$BE_IMAGE" >/dev/null
joint init
trap 'joint down' EXIT
joint build
if [[ "$suite" == capacity ]]; then
  uv run --locked python "$root/scripts/verify_joint_capacity.py" "${args[@]}" \
    --report "$results/$project-capacity.json"
  exit 0
fi
joint up
if [[ -n "$remaining_suite" ]]; then
  uv run --locked python -m app.joint_test.remaining \
    --be-repo "$root" --env-file "$env_file" --project "$project" \
    --suite "$remaining_suite" --transport "$transport" --be-scenario "$scenario" \
    --input-count 24 --load-pattern "$load_pattern" --report "$results/$project-remaining.json"
  echo "BE78 remaining verification passed. Report: $results/$project-remaining.json"
  exit 0
fi
fault=model-timeout
if [[ "$suite" == loss ]]; then fault=model429; fi
joint run --suite full --handoff --model-fault "$fault" --report "$results/$project-agent.json"
uv run --locked python "$root/scripts/verify_joint_backend.py" "${args[@]}" \
  --source-report "$results/$project-agent.json" --report "$results/$project-backend.json"
if [[ "$suite" == normal ]]; then
  uv run --locked python "$root/scripts/verify_joint_recovery.py" "${args[@]}" \
    --report "$results/$project-recovery.json"
fi
echo "BE78 joint verification passed. Reports: $results/$project-*.json"
echo "Containers will be removed on exit; private environment and exact project volumes are retained."
