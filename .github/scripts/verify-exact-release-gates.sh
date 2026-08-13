#!/usr/bin/env bash
# Verify every authoritative release gate against one immutable final main SHA.
set -euo pipefail

EXPECTED_MAIN_SHA=${EXPECTED_MAIN_SHA:-}
DISCOVERY_ATTEMPTS=${RELEASE_GATE_DISCOVERY_ATTEMPTS:-12}
REGISTRATION_ATTEMPTS=${RELEASE_GATE_REGISTRATION_ATTEMPTS:-60}
POLL_SECONDS=${RELEASE_GATE_POLL_SECONDS:-5}

fail() {
  echo "::error::$*" >&2
  exit 1
}

if [[ ! "$EXPECTED_MAIN_SHA" =~ ^[0-9a-f]{40}$ ]]; then
  fail "EXPECTED_MAIN_SHA must be one canonical full Git commit ID, got '$EXPECTED_MAIN_SHA'"
fi
if [[ ! "$DISCOVERY_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  fail "RELEASE_GATE_DISCOVERY_ATTEMPTS must be a positive integer"
fi
if [[ ! "$REGISTRATION_ATTEMPTS" =~ ^[1-9][0-9]*$ ]]; then
  fail "RELEASE_GATE_REGISTRATION_ATTEMPTS must be a positive integer"
fi
if [[ ! "$POLL_SECONDS" =~ ^[0-9]+$ ]]; then
  fail "RELEASE_GATE_POLL_SECONDS must be a non-negative integer"
fi

readonly -a REQUIRED_WORKFLOWS=(
  ci-cd.yml
  database-compatibility.yml
  codeql.yml
  security-scan.yml
)

declare -a SELECTED_RUN_IDS=()
declare -a SELECTED_WORKFLOWS=()

assert_main_unchanged() {
  git fetch origin "refs/heads/main:refs/remotes/origin/main" --force
  local remote_sha
  remote_sha=$(git rev-parse origin/main)
  if [[ "$remote_sha" != "$EXPECTED_MAIN_SHA" ]]; then
    fail "Release gate candidate is $EXPECTED_MAIN_SHA, but origin/main is $remote_sha"
  fi
}

find_existing_run() {
  local workflow=$1
  gh run list \
    --workflow "$workflow" \
    --branch main \
    --commit "$EXPECTED_MAIN_SHA" \
    --limit 20 \
    --json databaseId,createdAt,event,headBranch,headSha \
    --jq 'map(select((.event == "push" or .event == "workflow_dispatch") and .headBranch == "main" and .headSha == "'"$EXPECTED_MAIN_SHA"'")) | sort_by(.createdAt) | last | .databaseId // empty'
}

find_dispatched_run() {
  local workflow=$1
  gh run list \
    --workflow "$workflow" \
    --branch main \
    --event workflow_dispatch \
    --commit "$EXPECTED_MAIN_SHA" \
    --limit 20 \
    --json databaseId,createdAt,headBranch,headSha \
    --jq 'map(select(.headBranch == "main" and .headSha == "'"$EXPECTED_MAIN_SHA"'")) | sort_by(.createdAt) | last | .databaseId // empty'
}

validate_run_identity() {
  local workflow=$1
  local run_id=$2
  local run_sha run_branch run_event
  run_sha=$(gh run view "$run_id" --json headSha --jq '.headSha')
  run_branch=$(gh run view "$run_id" --json headBranch --jq '.headBranch')
  run_event=$(gh run view "$run_id" --json event --jq '.event')

  if [[ "$run_sha" != "$EXPECTED_MAIN_SHA" ]]; then
    fail "$workflow run $run_id verifies $run_sha, not $EXPECTED_MAIN_SHA"
  fi
  if [[ "$run_branch" != "main" ]]; then
    fail "$workflow run $run_id verifies branch '$run_branch', not main"
  fi
  if [[ "$run_event" != "push" && "$run_event" != "workflow_dispatch" ]]; then
    fail "$workflow run $run_id has unsupported event '$run_event'"
  fi
}

assert_main_unchanged

# Locate all naturally triggered exact-head runs first. A workflow without one
# is dispatched only while main still points to the recorded candidate. This
# normally dispatches the database matrix while reusing the push-triggered CI,
# CodeQL and security runs.
for workflow in "${REQUIRED_WORKFLOWS[@]}"; do
  run_id=""
  for _ in $(seq 1 "$DISCOVERY_ATTEMPTS"); do
    run_id=$(find_existing_run "$workflow")
    if [[ -n "$run_id" ]]; then
      break
    fi
    sleep "$POLL_SECONDS"
  done

  if [[ -z "$run_id" ]]; then
    assert_main_unchanged
    echo "::notice::Dispatching $workflow for exact release candidate $EXPECTED_MAIN_SHA"
    gh workflow run "$workflow" --ref main

    for _ in $(seq 1 "$REGISTRATION_ATTEMPTS"); do
      run_id=$(find_dispatched_run "$workflow")
      if [[ -n "$run_id" ]]; then
        break
      fi
      sleep "$POLL_SECONDS"
    done
  fi

  if [[ -z "$run_id" ]]; then
    fail "Could not locate or dispatch $workflow for exact commit $EXPECTED_MAIN_SHA"
  fi

  validate_run_identity "$workflow" "$run_id"
  SELECTED_WORKFLOWS+=("$workflow")
  SELECTED_RUN_IDS+=("$run_id")
  echo "::notice::Selected $workflow run $run_id for $EXPECTED_MAIN_SHA"
done

# All missing workflows have now been dispatched and can execute concurrently.
# Watching sequentially does not serialize their execution; it only serializes
# the final status checks and keeps diagnostics attached to the exact run.
for index in "${!SELECTED_RUN_IDS[@]}"; do
  workflow=${SELECTED_WORKFLOWS[$index]}
  run_id=${SELECTED_RUN_IDS[$index]}
  watch_exit=0
  gh run watch "$run_id" --exit-status || watch_exit=$?
  validate_run_identity "$workflow" "$run_id"

  status=$(gh run view "$run_id" --json status --jq '.status')
  conclusion=$(gh run view "$run_id" --json conclusion --jq '.conclusion')
  if [[ "$status" != "completed" || "$conclusion" != "success" ]]; then
    fail "$workflow run $run_id ended with status=$status conclusion=$conclusion (gh run watch exit=$watch_exit)"
  fi
  if (( watch_exit != 0 )); then
    fail "$workflow run $run_id could not be watched reliably (exit=$watch_exit) despite status=$status conclusion=$conclusion"
  fi
  echo "::notice::$workflow passed for exact commit $EXPECTED_MAIN_SHA"
done

# A successful matrix is not sufficient if another commit has meanwhile become
# main. Publication remains bound to the one recorded and tested candidate.
assert_main_unchanged

echo "All exact release gates passed for $EXPECTED_MAIN_SHA: ${REQUIRED_WORKFLOWS[*]}"
