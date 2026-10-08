#!/usr/bin/env bash
# Admission and final read-only checks for the protected release handoff.
# This script never approves, retries or dispatches a GitHub workflow.
set -euo pipefail

mode=${1:-preflight}
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${EXPECTED_SHA:?EXPECTED_SHA is required}"
[[ "$mode" == preflight || "$mode" == verify ]] || { echo 'Expected preflight or verify' >&2; exit 2; }
[[ "$GITHUB_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || exit 2
[[ "$PR_NUMBER" =~ ^[1-9][0-9]*$ && "$EXPECTED_SHA" =~ ^[0-9a-f]{40}$ ]] || exit 2

fail() {
  printf '::error::%s\n' "$*" >&2
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf '\nRelease handoff stopped: %s\n' "$*" >> "$GITHUB_STEP_SUMMARY"
  fi
  exit 1
}

check_head() {
  local pr
  pr=$(gh pr view "$PR_NUMBER" --repo "$GITHUB_REPOSITORY" \
    --json headRefOid,state,baseRefName) || fail "Cannot read PR #$PR_NUMBER; see GitHub error above."
  jq -e --arg sha "$EXPECTED_SHA" \
    '.state == "OPEN" and .baseRefName == "main" and .headRefOid == $sha' \
    <<< "$pr" >/dev/null || fail "PR #$PR_NUMBER is closed, has a different base or no longer has head $EXPECTED_SHA."
}

for attempt in $(seq 1 12); do
  check_head
  # Include all pages, but only the newest run of each workflow for this PR/head.
  # A superseded action_required result must not block a later approved attempt.
  runs=$(gh api --paginate --slurp \
    "repos/${GITHUB_REPOSITORY}/actions/runs?event=pull_request&head_sha=${EXPECTED_SHA}&per_page=100") \
    || fail "Cannot read workflow admission for PR #$PR_NUMBER; see GitHub error above."
  jq -e 'type == "array" and length > 0 and all(.[];
    (.workflow_runs | type) == "array" and all(.workflow_runs[];
      (.head_sha | type) == "string" and (.event | type) == "string" and
      (.pull_requests | type) == "array" and (.workflow_id | type) == "number" and
      .workflow_id > 0 and (.run_number | type) == "number" and
      (.run_attempt | type) == "number"))' \
    <<< "$runs" >/dev/null || fail 'GitHub returned an invalid workflow-run response.'
  latest=$(jq -ce --arg sha "$EXPECTED_SHA" --argjson pr "$PR_NUMBER" '
    [.[].workflow_runs[]
      | select(.head_sha == $sha and .event == "pull_request")
      | select(any(.pull_requests[]?; .number == $pr))]
    | group_by(.workflow_id)
    | map(max_by([.run_number, .run_attempt]))' <<< "$runs") \
    || fail 'Cannot classify workflow runs.'
  approval=$(jq -r '.[] | select(.conclusion == "action_required")
    | "\(.name): \(.html_url)"' <<< "$latest")
  if [[ -n "$approval" ]]; then
    printf '%s\n' "$approval" >&2
    fail "PR #$PR_NUMBER requires 'Approve workflows to run'. No replacement CI was dispatched. Approve the original workflows in GitHub; keep this head unchanged."
  fi

  # gh returns 1 for failed checks as well as API/CLI errors. Preserve stderr and
  # classify valid JSON instead of hiding the reason behind a registration timeout.
  status=0
  checks=$(gh pr checks "$PR_NUMBER" --repo "$GITHUB_REPOSITORY" \
    --required --json name,bucket,state,link) || status=$?
  [[ "$status" == 0 || "$status" == 1 || "$status" == 8 ]] \
    || fail "Cannot query required checks for PR #$PR_NUMBER (gh exit $status); see GitHub error above."
  jq -e 'type == "array" and all(.[];
    (.name | type) == "string" and (.name | length) > 0 and
    (.bucket == "pass" or .bucket == "pending" or .bucket == "fail" or
     .bucket == "skipping" or .bucket == "cancel"))' <<< "$checks" >/dev/null \
    || fail "Required checks query returned no usable JSON (gh exit $status); see GitHub error above."
  rejected=$(jq -r '.[] | select(.bucket != "pass" and .bucket != "pending")
    | "\(.name): \(.state) [\(.bucket)] \(.link // "")"' <<< "$checks")
  if [[ -n "$rejected" ]]; then
    printf '%s\n' "$rejected" >&2
    fail "Required checks are failed, cancelled or skipped for PR #$PR_NUMBER; none count as success."
  fi
  # Never interpret an error exit accompanied by otherwise successful data as success.
  [[ "$status" != 1 ]] || fail 'GitHub returned an error despite no failed checks in its response.'
  if [[ "$(jq 'length' <<< "$latest")" -gt 0 && "$(jq 'length' <<< "$checks")" -gt 0 ]]; then
    if [[ "$mode" == verify ]]; then
      [[ "$status" == 0 ]] || fail "Required check query still reports a pending exit for PR #$PR_NUMBER; required checks are still pending."
      jq -e 'all(.[]; .bucket == "pass")' <<< "$checks" >/dev/null \
        || fail "Required checks for PR #$PR_NUMBER are still pending."
    fi
    check_head
    jq -r '.[] | "Required check: \(.name) [\(.bucket)]"' <<< "$checks"
    if [[ "$mode" == preflight ]]; then
      echo 'Workflow admission is ready; pending checks are NOT a merge approval.'
    else
      echo 'All registered required checks passed on the expected PR head.'
    fi
    exit 0
  fi
  [[ "$status" == 0 || "$status" == 8 ]] || fail 'Required checks are unavailable.'
  if [[ "$attempt" != 12 ]]; then sleep 5; fi
done
fail "Workflow runs or required checks have not registered for PR #$PR_NUMBER. No replacement CI was dispatched."
