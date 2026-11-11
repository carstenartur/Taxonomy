#!/usr/bin/env bash
set -euo pipefail

mkdir -p target/quality-reports/evidence/observability-performance
cp -a target/observability-performance/. \
  target/quality-reports/evidence/observability-performance/
rm -rf target/quality-reports/evidence/observability-performance/tests
if [[ -d target/observability-performance/tests ]]; then
  # Core also records the disabled invocation; keep both original lane outcomes.
  mkdir -p target/quality-reports/tests/observability-performance
  cp -a target/observability-performance/tests/. target/quality-reports/tests/observability-performance/
fi
cp target/ui-verification/summary.json \
  target/quality-reports/evidence/ui-verification-summary.json

# Preserve actual JUnit browser scenario/component XML from every shard.
while IFS= read -r -d '' report; do
  relative=${report#target/ui-verification/}
  destination="target/quality-reports/tests/ui/$relative"
  mkdir -p "$(dirname "$destination")"
  cp "$report" "$destination"
done < <(find target/ui-verification -type f -name 'TEST-*.xml' -print0)

# Dedicated packaged integrations are imported once, with the original producer
# manifest. Missing originals fail here even before publication verification.
mkdir -p target/quality-reports/tests/plugin-profile/target/failsafe-reports \
  target/quality-reports/evidence/plugin-profile
for suite in ExternalPluginPackagedIT OptionalFeaturePackagedIT; do
  cp "target/plugin-profile-evidence/TEST-com.taxonomy.build.$suite.xml" \
    target/quality-reports/tests/plugin-profile/target/failsafe-reports/
done
cp target/plugin-profile-evidence/manifest.json target/quality-reports/evidence/plugin-profile/

source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")
build_id="${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}"
# The action pin is configuration, not evidence of a completed independent scan.
# QualityToolMetadataTest checks it against every CodeQL workflow invocation.
bash "$(dirname -- "${BASH_SOURCE[0]}")/taxonomy-tooling.sh" generate-quality-site \
  --root target/quality-reports --commit "$GITHUB_SHA" \
  --source-tree "$source_tree" --build-id "$build_id" \
  --tool "java=$(java -version 2>&1 | sed -n '1p')" \
  --tool "maven=$(./mvnw -version 2>&1 | sed -n '1p')" \
  --tool 'codeql-action=configured:github/codeql-action@33d7b85da1a04171e211befe2c739303b9a4c066' \
  --tool 'trivy-action=v0.36.0'
bash "$(dirname -- "${BASH_SOURCE[0]}")/taxonomy-tooling.sh" verify-quality-publication \
  --root target/quality-reports --expected-commit "$GITHUB_SHA"
bash "$(dirname -- "${BASH_SOURCE[0]}")/taxonomy-tooling.sh" write-quality-manifest \
  --root target/quality-reports --expected-commit "$GITHUB_SHA" --expected-tree "$source_tree"
printf 'Verified commit: %s\nSource tree: %s\nBuild ID: %s\n' \
  "$GITHUB_SHA" "$source_tree" "$build_id" > target/quality-reports/README.txt
