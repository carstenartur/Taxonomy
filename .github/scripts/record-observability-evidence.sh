#!/usr/bin/env bash
set -euo pipefail

mkdir -p target/observability-performance
jq -n --arg sourceCommit "$GITHUB_SHA" --arg executed "${EXECUTED:-false}" \
  '{schemaVersion:1,sourceCommit:$sourceCommit,executed:($executed=="true")}' \
  > target/observability-performance/ci-scope.json
if [[ "${EXECUTED:-false}" == true ]]; then
  reports=taxonomy-app/target/failsafe-reports
  xml="$reports/TEST-com.taxonomy.ObservabilityPerformanceIT.xml"
  [[ -f "$xml" ]] || {
    echo '::error::Observability performance JUnit evidence is missing'
    exit 1
  }
  destination=target/observability-performance/tests/taxonomy-app/target/failsafe-reports
  mkdir -p "$destination"
  cp "$reports"/*ObservabilityPerformanceIT* "$destination/"
fi
