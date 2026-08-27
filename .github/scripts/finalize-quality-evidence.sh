#!/usr/bin/env bash
set -euo pipefail

mkdir -p target/quality-reports/evidence/observability-performance
cp -a target/observability-performance/. \
  target/quality-reports/evidence/observability-performance/
rm -rf target/quality-reports/evidence/observability-performance/tests
if [[ -d target/observability-performance/tests ]]; then
  cp -a target/observability-performance/tests/. target/quality-reports/tests/
fi
cp target/ui-verification/summary.json \
  target/quality-reports/evidence/ui-verification-summary.json

source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")
build_id="${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}"
python3 .github/scripts/generate-quality-site.py \
  --root target/quality-reports --commit "$GITHUB_SHA" \
  --source-tree "$source_tree" --build-id "$build_id" \
  --tool "java=$(java -version 2>&1 | sed -n '1p')" \
  --tool "maven=$(./mvnw -version 2>&1 | sed -n '1p')" \
  --tool 'codeql-action=v4.37.6' --tool 'trivy-action=v0.36.0'
python3 .github/scripts/verify-quality-publication.py \
  --root target/quality-reports --expected-commit "$GITHUB_SHA"
printf 'Verified commit: %s\nSource tree: %s\nBuild ID: %s\n' \
  "$GITHUB_SHA" "$source_tree" "$build_id" > target/quality-reports/README.txt
