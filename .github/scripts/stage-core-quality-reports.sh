#!/usr/bin/env bash
set -euo pipefail

rm -rf target/quality-reports
# Finish discovery before creating copies. A streaming find can otherwise walk
# into its own output and duplicate reports; process substitution also hides a
# failed find from set -e. Keep the NUL-delimited inventory outside the checkout.
report_inventory=$(mktemp)
report_directories=$(mktemp)
trap 'rm -f "$report_inventory" "$report_directories"' EXIT
find . -path '*/target/quality-reports' -prune -o -type d \
  \( -path '*/target/surefire-reports' -o -path '*/target/failsafe-reports' \) -print0 \
  > "$report_directories"
while IFS= read -r -d '' report_dir; do
  find "$report_dir" -path '*/target/quality-reports/*' -prune -o -type f \
    \( -name 'TEST-*.xml' -o -name '*.txt' -o -name '*.dump' -o -name '*.dumpstream' \) -print0
done < "$report_directories" > "$report_inventory"

mkdir -p target/quality-reports/{tests,coverage,evidence}
while IFS= read -r -d '' report; do
  relative=${report#./}
  destination="target/quality-reports/tests/${relative}"
  mkdir -p "$(dirname "$destination")"
  cp "$report" "$destination"
done < "$report_inventory"

if [[ -d target/app-test-shards ]]; then
  shard_reports=$(mktemp)
  trap 'rm -f "$report_inventory" "$report_directories" "$shard_reports"' EXIT
  mkdir -p target/quality-reports/evidence/application-test-shards
  for shard_directory in target/app-test-shards/*; do
    [[ -d "$shard_directory" ]] || continue
    shard=${shard_directory##*/}
    evidence_directory="target/quality-reports/evidence/application-test-shards/$shard"
    mkdir -p "$evidence_directory"
    [[ ! -f "$shard_directory/manifest.json" ]] \
      || cp "$shard_directory/manifest.json" "$evidence_directory/"
    [[ ! -f "$shard_directory/coverage.exec" ]] \
      || cp "$shard_directory/coverage.exec" "$evidence_directory/"
    if [[ -f "$shard_directory/timings.json" ]]; then
      cp "$shard_directory/timings.json" "$evidence_directory/"
    fi
    if [[ "$shard" == core || ! -d "$shard_directory/tests" ]]; then
      continue
    fi
    find "$shard_directory/tests" -type f -name 'TEST-*.xml' -print0 > "$shard_reports"
    while IFS= read -r -d '' report; do
      relative=${report#"$shard_directory/tests/"}
      destination="target/quality-reports/tests/application-test-shards/$shard/$relative"
      mkdir -p "$(dirname "$destination")"
      cp "$report" "$destination"
    done < "$shard_reports"
  done
  if [[ -f target/app-test-shards/timings.json ]]; then
    cp target/app-test-shards/timings.json \
      target/quality-reports/evidence/application-test-shards/
  fi
fi

# Preserve diagnostics even when a failing integration test prevents aggregate
# coverage generation. This copy does not turn incomplete evidence into success.
large_result_report=target/ui-verification/large-results/report.json
if [[ -f "$large_result_report" ]]; then
  cp "$large_result_report" target/quality-reports/evidence/large-result-budget.json
fi
if [[ -f target/maven-verification.log ]]; then
  cp target/maven-verification.log target/quality-reports/evidence/
fi
# Keep the owner diagnostics, not its separate downloaded product documents.
for portfolio_context in root taxonomy; do
  context_source="taxonomy-app/target/portfolio-context-evidence/${portfolio_context}"
  if [[ -d "$context_source" ]]; then
    context_destination="target/quality-reports/evidence/portfolio-context/${portfolio_context}"
    mkdir -p "$context_destination"
    cp -a "$context_source/." "$context_destination/"
  fi
done

coverage=taxonomy-coverage/target/site/jacoco-aggregate
[[ -f "$coverage/jacoco.xml" ]] || {
  echo '::error::Aggregate JaCoCo XML is missing; refusing stale coverage evidence.'
  exit 1
}
cp -a "$coverage/." target/quality-reports/coverage/
for evidence in \
  target/version-state-report.txt \
  target/coverage-gate.txt target/dependency-hygiene-report.txt \
  target/frontend-api-boundary-report.txt target/hibernate-search-dependencies.txt \
  target/supply-chain-pins.json target/taxonomy-sbom.json target/taxonomy-sbom.xml \
  target/taxonomy-vex.json target/taxonomy-helm-rendered.yaml \
  target/taxonomy-helm-rancher-rke2-rendered.yaml; do
  [[ -f "$evidence" ]] && cp "$evidence" target/quality-reports/evidence/
done

source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")
build_id="${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}.core"
printf 'Staged core evidence\nCommit: %s\nSource tree: %s\nCore build ID: %s\n' \
  "$GITHUB_SHA" "$source_tree" "$build_id" > target/quality-reports/README.txt
