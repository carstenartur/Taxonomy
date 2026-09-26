#!/usr/bin/env bash
set -euo pipefail

rm -rf target/quality-reports
# Finish discovery before creating copies. A streaming find can otherwise walk
# into its own output and duplicate reports; process substitution also hides a
# failed find from set -e. Keep the NUL-delimited inventory outside the checkout.
report_inventory=$(mktemp)
report_directories=$(mktemp)
trap 'rm -f "$report_inventory" "$report_directories"' EXIT
find . -type d \
  \( -path '*/target/surefire-reports' -o -path '*/target/failsafe-reports' \) -print0 \
  > "$report_directories"
while IFS= read -r -d '' report_dir; do
  find "$report_dir" -type f \
    \( -name 'TEST-*.xml' -o -name '*.txt' -o -name '*.dump' -o -name '*.dumpstream' \) -print0
done < "$report_directories" > "$report_inventory"

mkdir -p target/quality-reports/{tests,coverage,evidence}
while IFS= read -r -d '' report; do
  relative=${report#./}
  destination="target/quality-reports/tests/${relative}"
  mkdir -p "$(dirname "$destination")"
  cp "$report" "$destination"
done < "$report_inventory"

coverage=taxonomy-coverage/target/site/jacoco-aggregate
[[ -f "$coverage/jacoco.xml" ]] || {
  echo '::error::Aggregate JaCoCo XML is missing; refusing stale coverage evidence.'
  exit 1
}
cp -a "$coverage/." target/quality-reports/coverage/
for evidence in \
  target/maven-verification.log target/version-state-report.txt \
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
