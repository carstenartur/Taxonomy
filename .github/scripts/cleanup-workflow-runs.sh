#!/usr/bin/env bash
set -euo pipefail

# Repository workflow-run retention planner and executor.
# Required environment: GH_TOKEN and REPO (owner/name).

readonly SUMMARY_FILE="${GITHUB_STEP_SUMMARY:-/dev/stdout}"
readonly TMP_ROOT="$(mktemp -d)"
trap 'rm -rf "${TMP_ROOT}"' EXIT

fail() {
  echo "::error title=Workflow cleanup configuration::$*" >&2
  exit 2
}

integer_setting() {
  local name="$1"
  local raw="$2"
  local min="$3"
  local max="$4"
  raw="${raw%.*}"
  [[ "${raw}" =~ ^[0-9]+$ ]] || fail "${name} must be a whole number, got '${2}'."
  (( raw >= min && raw <= max )) || fail "${name} must be between ${min} and ${max}, got '${raw}'."
  printf '%s' "${raw}"
}

boolean_setting() {
  local name="$1"
  local raw="${2,,}"
  case "${raw}" in
    true|false) printf '%s' "${raw}" ;;
    *) fail "${name} must be true or false, got '${2}'." ;;
  esac
}

[[ -n "${GH_TOKEN:-}" ]] || fail "GH_TOKEN is required."
[[ "${REPO:-}" == */* ]] || fail "REPO must use owner/name form."

RETAIN_DAYS="$(integer_setting RETAIN_DAYS "${RETAIN_DAYS:-5}" 1 365)"
HISTORICAL_RETAIN_DAYS="$(integer_setting HISTORICAL_RETAIN_DAYS "${HISTORICAL_RETAIN_DAYS:-2}" 0 365)"
KEEP_MINIMUM_RUNS="$(integer_setting KEEP_MINIMUM_RUNS "${KEEP_MINIMUM_RUNS:-3}" 0 100)"
MAX_DELETIONS="$(integer_setting MAX_DELETIONS "${MAX_DELETIONS:-1500}" 1 4000)"
DRY_RUN="$(boolean_setting DRY_RUN "${DRY_RUN:-false}")"

readonly NOW_EPOCH="$(date -u +%s)"
readonly ACTIVE_CUTOFF_EPOCH="$(( NOW_EPOCH - RETAIN_DAYS * 86400 ))"
readonly HISTORICAL_CUTOFF_EPOCH="$(( NOW_EPOCH - HISTORICAL_RETAIN_DAYS * 86400 ))"
readonly CANDIDATES_FILE="${TMP_ROOT}/candidates.tsv"
readonly SORTED_CANDIDATES_FILE="${TMP_ROOT}/candidates-sorted.tsv"
readonly WORKFLOW_REPORT_FILE="${TMP_ROOT}/workflow-report.tsv"
: > "${CANDIDATES_FILE}"
: > "${WORKFLOW_REPORT_FILE}"

declare -A ACTIVE_PATHS=()
declare -A OPEN_PR_SHAS=()
declare -A TAG_SHAS=()
declare -A TAG_NAMES=()
declare -A RELEASE_TAGS=()
declare -A PROTECTION_COUNTS=()
declare -A WF_NAME=()
declare -A WF_PATH=()
declare -A WF_CLASS=()
declare -A WF_TOTAL=()
declare -A WF_PROTECTED=()
declare -A WF_RECENT=()
declare -A WF_CANDIDATES=()
declare -A WF_SELECTED=()
declare -A WF_DELETE_FAILURES=()

sanitize_field() {
  local value="$1"
  value="${value//$'\t'/ }"
  value="${value//$'\n'/ }"
  value="${value//$'\r'/ }"
  printf '%s' "${value}"
}

protect() {
  local reason="$1"
  PROTECTION_COUNTS["${reason}"]=$(( ${PROTECTION_COUNTS["${reason}"]:-0} + 1 ))
}

api_lines() {
  gh api "$@"
}

capture_api_lines() {
  local output_file="$1"
  shift
  # Materialize every safety-critical API response before planning deletions.
  # A transient failure must abort the run instead of looking like an empty
  # protection set.
  api_lines "$@" > "${output_file}"
}

contains_key() {
  local map_name="$1"
  local key="$2"
  [[ -n "${key}" ]] || return 1
  local -n map_ref="${map_name}"
  [[ -n "${map_ref["${key}"]+x}" ]]
}

echo "Repository: ${REPO}"
echo "Policy: retain=${RETAIN_DAYS}d historical-retain=${HISTORICAL_RETAIN_DAYS}d keep-minimum=${KEEP_MINIMUM_RUNS} max-deletions=${MAX_DELETIONS} dry-run=${DRY_RUN}"

DEFAULT_BRANCH="$(api_lines "repos/${REPO}" --jq '.default_branch')"
DEFAULT_HEAD="$(api_lines "repos/${REPO}/branches/${DEFAULT_BRANCH}" --jq '.commit.sha')"
[[ -n "${DEFAULT_BRANCH}" && -n "${DEFAULT_HEAD}" ]] || fail "Unable to resolve the default branch and its head."

echo "Default branch: ${DEFAULT_BRANCH} @ ${DEFAULT_HEAD}"

ACTIVE_PATHS_FILE="${TMP_ROOT}/active-workflow-paths.txt"
OPEN_PRS_FILE="${TMP_ROOT}/open-pr-heads.tsv"
TAGS_FILE="${TMP_ROOT}/tags.tsv"
RELEASES_FILE="${TMP_ROOT}/releases.txt"
WORKFLOWS_FILE="${TMP_ROOT}/workflows.tsv"

capture_api_lines "${ACTIVE_PATHS_FILE}" "repos/${REPO}/contents/.github/workflows?ref=${DEFAULT_BRANCH}" \
  --jq '.[] | select(.type == "file") | .path'
capture_api_lines "${OPEN_PRS_FILE}" "repos/${REPO}/pulls?state=open&per_page=100" --paginate \
  --jq '.[] | [.number, .head.sha, .head.ref] | @tsv'
capture_api_lines "${TAGS_FILE}" "repos/${REPO}/tags?per_page=100" --paginate \
  --jq '.[] | [.name, .commit.sha] | @tsv'
capture_api_lines "${RELEASES_FILE}" "repos/${REPO}/releases?per_page=100" --paginate \
  --jq '.[] | select(.draft == false) | .tag_name'
capture_api_lines "${WORKFLOWS_FILE}" "repos/${REPO}/actions/workflows?per_page=100" --paginate \
  --jq '.workflows[] | [.id, .name, .path, .state] | @tsv'

while IFS= read -r path; do
  [[ -n "${path}" ]] && ACTIVE_PATHS["${path}"]=1
done < "${ACTIVE_PATHS_FILE}"

while IFS=$'\t' read -r number sha ref; do
  [[ -n "${sha}" ]] || continue
  OPEN_PR_SHAS["${sha}"]="${number}:${ref}"
done < "${OPEN_PRS_FILE}"

while IFS=$'\t' read -r tag sha; do
  [[ -n "${tag}" ]] && TAG_NAMES["${tag}"]=1
  [[ -n "${sha}" ]] && TAG_SHAS["${sha}"]=1
done < "${TAGS_FILE}"

while IFS= read -r tag; do
  [[ -n "${tag}" ]] && RELEASE_TAGS["${tag}"]=1
done < "${RELEASES_FILE}"

mapfile -t WORKFLOWS < "${WORKFLOWS_FILE}"

ACTIVE_WORKFLOW_COUNT=0
HISTORICAL_WORKFLOW_COUNT=0
TOTAL_COMPLETED=0
TOTAL_PROTECTED=0
TOTAL_RECENT=0
TOTAL_CANDIDATES=0

for workflow in "${WORKFLOWS[@]}"; do
  IFS=$'\t' read -r wf_id wf_name wf_path wf_state <<< "${workflow}"
  wf_name="$(sanitize_field "${wf_name}")"
  wf_path="$(sanitize_field "${wf_path}")"

  if [[ -n "${ACTIVE_PATHS["${wf_path}"]+x}" ]]; then
    wf_class="active"
    cutoff_epoch="${ACTIVE_CUTOFF_EPOCH}"
    keep_count="${KEEP_MINIMUM_RUNS}"
    priority=1
    ACTIVE_WORKFLOW_COUNT=$((ACTIVE_WORKFLOW_COUNT + 1))
  else
    wf_class="historical"
    cutoff_epoch="${HISTORICAL_CUTOFF_EPOCH}"
    keep_count=0
    priority=0
    HISTORICAL_WORKFLOW_COUNT=$((HISTORICAL_WORKFLOW_COUNT + 1))
  fi

  WF_NAME["${wf_id}"]="${wf_name}"
  WF_PATH["${wf_id}"]="${wf_path}"
  WF_CLASS["${wf_id}"]="${wf_class}"
  WF_TOTAL["${wf_id}"]=0
  WF_PROTECTED["${wf_id}"]=0
  WF_RECENT["${wf_id}"]=0
  WF_CANDIDATES["${wf_id}"]=0
  WF_SELECTED["${wf_id}"]=0
  WF_DELETE_FAILURES["${wf_id}"]=0

  echo "Inspecting ${wf_class} workflow: ${wf_name} (${wf_path}, id=${wf_id}, state=${wf_state})"

  run_index=0
  latest_success_seen=false
  latest_failure_seen=false
  raw_candidates="${TMP_ROOT}/candidates-${wf_id}.tsv"
  : > "${raw_candidates}"

  runs_file="${TMP_ROOT}/runs-${wf_id}.tsv"
  capture_api_lines "${runs_file}" "repos/${REPO}/actions/workflows/${wf_id}/runs?status=completed&per_page=100" --paginate \
    --jq '.workflow_runs[] | [.id, .created_at, (.conclusion // "unknown"), (.head_sha // ""), (.head_branch // ""), .event, .run_number, .html_url] | @tsv'

  while IFS=$'\t' read -r run_id created_at conclusion head_sha head_branch event run_number html_url; do
    [[ -n "${run_id}" ]] || continue
    run_index=$((run_index + 1))
    WF_TOTAL["${wf_id}"]=$((WF_TOTAL["${wf_id}"] + 1))
    TOTAL_COMPLETED=$((TOTAL_COMPLETED + 1))

    is_latest_success=false
    is_latest_failure=false
    if [[ "${wf_class}" == "active" && "${conclusion}" == "success" && "${latest_success_seen}" == "false" ]]; then
      latest_success_seen=true
      is_latest_success=true
    fi
    if [[ "${wf_class}" == "active" && "${latest_failure_seen}" == "false" ]]; then
      case "${conclusion}" in
        failure|timed_out|action_required|startup_failure)
          latest_failure_seen=true
          is_latest_failure=true
          ;;
      esac
    fi

    reason=""
    if [[ "${head_sha}" == "${DEFAULT_HEAD}" ]]; then
      reason="current-default-head"
    elif contains_key OPEN_PR_SHAS "${head_sha}"; then
      reason="current-open-pr-head"
    elif [[ "${event}" == "release" ]] \
      || contains_key TAG_SHAS "${head_sha}" \
      || contains_key TAG_NAMES "${head_branch}" \
      || contains_key RELEASE_TAGS "${head_branch}"; then
      reason="release-or-tag"
    elif (( run_index <= keep_count )); then
      reason="minimum-per-active-workflow"
    elif [[ "${is_latest_success}" == "true" ]]; then
      reason="latest-success"
    elif [[ "${is_latest_failure}" == "true" ]]; then
      reason="latest-failure"
    fi

    if [[ -n "${reason}" ]]; then
      protect "${reason}"
      WF_PROTECTED["${wf_id}"]=$((WF_PROTECTED["${wf_id}"] + 1))
      TOTAL_PROTECTED=$((TOTAL_PROTECTED + 1))
      continue
    fi

    created_epoch="$(date -u -d "${created_at}" +%s)"
    if (( created_epoch >= cutoff_epoch )); then
      WF_RECENT["${wf_id}"]=$((WF_RECENT["${wf_id}"] + 1))
      TOTAL_RECENT=$((TOTAL_RECENT + 1))
      continue
    fi

    conclusion="$(sanitize_field "${conclusion}")"
    head_branch="$(sanitize_field "${head_branch}")"
    event="$(sanitize_field "${event}")"
    html_url="$(sanitize_field "${html_url}")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${run_id}" "${created_at}" "${conclusion}" "${head_branch}" \
      "${event}" "${run_number}" "${html_url}" "${head_sha}" >> "${raw_candidates}"
    WF_CANDIDATES["${wf_id}"]=$((WF_CANDIDATES["${wf_id}"] + 1))
    TOTAL_CANDIDATES=$((TOTAL_CANDIDATES + 1))
  done < "${runs_file}"

  # API order is newest first. Reverse each workflow queue so the oldest
  # eligible run is removed first, then assign a round-robin rank.
  candidate_rank=0
  while IFS=$'\t' read -r run_id created_at conclusion head_branch event run_number html_url head_sha; do
    [[ -n "${run_id}" ]] || continue
    candidate_rank=$((candidate_rank + 1))
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "${candidate_rank}" "${priority}" "${wf_id}" "${wf_class}" "${wf_name}" "${wf_path}" \
      "${run_id}" "${created_at}" "${conclusion}" "${event}" "${head_branch}" "${html_url}" \
      >> "${CANDIDATES_FILE}"
  done < <(tac "${raw_candidates}")
done

if [[ -s "${CANDIDATES_FILE}" ]]; then
  # Candidate rank first gives every workflow one deletion before any workflow
  # receives a second. Historical workflows win ties so removed one-off workflow
  # names disappear from the Actions page as early as possible.
  sort -t $'\t' -k1,1n -k2,2n -k8,8 "${CANDIDATES_FILE}" > "${SORTED_CANDIDATES_FILE}"
else
  : > "${SORTED_CANDIDATES_FILE}"
fi

TOTAL_SELECTED=0
TOTAL_DELETED=0
TOTAL_DELETE_FAILURES=0
STOP_REASON="all eligible candidates processed"

while IFS=$'\t' read -r rank priority wf_id wf_class wf_name wf_path run_id created_at conclusion event head_branch html_url; do
  [[ -n "${run_id}" ]] || continue
  if (( TOTAL_SELECTED >= MAX_DELETIONS )); then
    STOP_REASON="deletion cap reached"
    break
  fi

  TOTAL_SELECTED=$((TOTAL_SELECTED + 1))
  WF_SELECTED["${wf_id}"]=$((WF_SELECTED["${wf_id}"] + 1))

  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "[dry-run] delete ${run_id} ${created_at} ${wf_class} ${wf_name} (${conclusion}, ${event}, ${head_branch})"
    continue
  fi

  echo "Deleting ${run_id} ${created_at} ${wf_class} ${wf_name} (${conclusion}, ${event}, ${head_branch})"
  if gh api --method DELETE "repos/${REPO}/actions/runs/${run_id}" >/dev/null 2>&1; then
    TOTAL_DELETED=$((TOTAL_DELETED + 1))
  else
    TOTAL_DELETE_FAILURES=$((TOTAL_DELETE_FAILURES + 1))
    WF_DELETE_FAILURES["${wf_id}"]=$((WF_DELETE_FAILURES["${wf_id}"] + 1))
    echo "::warning title=Workflow run deletion failed::Could not delete ${run_id} (${html_url})."
    if (( TOTAL_DELETE_FAILURES >= 10 )); then
      STOP_REASON="ten deletion failures reached"
      break
    fi
  fi

  # Avoid secondary abuse throttling while still allowing the backlog to drain.
  sleep 0.2
  if (( TOTAL_SELECTED % 100 == 0 )); then
    remaining="$(gh api rate_limit --jq '.rate.remaining' 2>/dev/null || echo 0)"
    if [[ "${remaining}" =~ ^[0-9]+$ ]] && (( remaining < 250 )); then
      STOP_REASON="GitHub API safety threshold reached"
      break
    fi
  fi
done < "${SORTED_CANDIDATES_FILE}"

for wf_id in "${!WF_NAME[@]}"; do
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${WF_CLASS["${wf_id}"]}" "${WF_NAME["${wf_id}"]}" "${WF_PATH["${wf_id}"]}" \
    "${WF_TOTAL["${wf_id}"]}" "${WF_PROTECTED["${wf_id}"]}" "${WF_RECENT["${wf_id}"]}" \
    "${WF_CANDIDATES["${wf_id}"]}" "${WF_SELECTED["${wf_id}"]}" "${WF_DELETE_FAILURES["${wf_id}"]}" "${wf_id}" \
    >> "${WORKFLOW_REPORT_FILE}"
done

if [[ "${DRY_RUN}" == "true" ]]; then
  TOTAL_REMAINING=$(( TOTAL_CANDIDATES - TOTAL_SELECTED ))
else
  TOTAL_REMAINING=$(( TOTAL_CANDIDATES - TOTAL_DELETED ))
fi
(( TOTAL_REMAINING < 0 )) && TOTAL_REMAINING=0

{
  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "### Workflow-run cleanup — dry run"
  else
    echo "### Workflow-run cleanup"
  fi
  echo
  echo "| Setting | Value |"
  echo "| --- | ---: |"
  echo "| Active workflow retention | ${RETAIN_DAYS} days |"
  echo "| Historical workflow retention | ${HISTORICAL_RETAIN_DAYS} days |"
  echo "| Minimum recent runs per active workflow | ${KEEP_MINIMUM_RUNS} |"
  echo "| Maximum deletions | ${MAX_DELETIONS} |"
  echo "| Dry run | ${DRY_RUN} |"
  echo "| Default branch | \`${DEFAULT_BRANCH}\` |"
  echo "| Open PR heads protected | ${#OPEN_PR_SHAS[@]} |"
  echo "| Tag commit SHAs protected | ${#TAG_SHAS[@]} |"
  echo
  echo "| Outcome | Count |"
  echo "| --- | ---: |"
  echo "| Active workflows | ${ACTIVE_WORKFLOW_COUNT} |"
  echo "| Historical workflow identities | ${HISTORICAL_WORKFLOW_COUNT} |"
  echo "| Completed runs inspected | ${TOTAL_COMPLETED} |"
  echo "| Protected by evidence policy | ${TOTAL_PROTECTED} |"
  echo "| Retained by age | ${TOTAL_RECENT} |"
  echo "| Eligible deletion candidates | ${TOTAL_CANDIDATES} |"
  echo "| Selected this run | ${TOTAL_SELECTED} |"
  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "| Would delete | ${TOTAL_SELECTED} |"
  else
    echo "| Deleted | ${TOTAL_DELETED} |"
    echo "| Delete failures | ${TOTAL_DELETE_FAILURES} |"
  fi
  echo "| Eligible candidates remaining | ${TOTAL_REMAINING} |"
  echo "| Stop reason | ${STOP_REASON} |"
  echo
  echo "#### Protected evidence"
  echo
  echo "| Reason | Runs |"
  echo "| --- | ---: |"
  for reason in current-default-head current-open-pr-head release-or-tag minimum-per-active-workflow latest-success latest-failure; do
    echo "| ${reason} | ${PROTECTION_COUNTS["${reason}"]:-0} |"
  done
  echo
  echo "#### Workflows with eligible candidates"
  echo
  echo "| Class | Workflow | Completed | Protected | Recent | Eligible | Selected | Failures |"
  echo "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |"
  shown=0
  while IFS=$'\t' read -r wf_class wf_name wf_path total protected recent candidates selected failures wf_id; do
    (( candidates > 0 )) || continue
    shown=$((shown + 1))
    if (( shown > 100 )); then
      echo "| … | Additional workflows omitted from the summary | | | | | | |"
      break
    fi
    wf_name_md="${wf_name//|/\\|}"
    echo "| ${wf_class} | ${wf_name_md} | ${total} | ${protected} | ${recent} | ${candidates} | ${selected} | ${failures} |"
  done < <(sort -t $'\t' -k1,1r -k7,7nr -k2,2 "${WORKFLOW_REPORT_FILE}")
  echo
  if [[ "${DRY_RUN}" == "true" ]]; then
    echo "> No workflow runs were deleted. Re-run with dry_run=false after reviewing this plan."
  elif (( TOTAL_REMAINING > 0 )); then
    echo "> ${TOTAL_REMAINING} eligible runs remain. The next scheduled execution continues with fair round-robin selection."
  fi
} >> "${SUMMARY_FILE}"

echo "Completed workflow cleanup: selected=${TOTAL_SELECTED} deleted=${TOTAL_DELETED} failures=${TOTAL_DELETE_FAILURES} remaining=${TOTAL_REMAINING} stop='${STOP_REASON}'"
