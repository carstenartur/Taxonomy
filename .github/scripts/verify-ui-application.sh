#!/usr/bin/env bash
set -euo pipefail

manifest=target/ui-application/manifest.json
source_commit=$(jq -r '.sourceCommit' "$manifest")
source_tree=$(jq -r '.sourceTree' "$manifest")
jar_name=$(jq -r '.jarName' "$manifest")
expected_sha=$(jq -r '.sha256' "$manifest")
[[ "$jar_name" =~ ^taxonomy-app-[A-Za-z0-9._+-]+\.jar$ ]] || {
  echo '::error::UI application manifest contains an unsafe JAR name'
  exit 1
}
[[ "$source_commit" == "$GITHUB_SHA" ]] || {
  echo "::error::UI application belongs to $source_commit, not $GITHUB_SHA"
  exit 1
}
expected_source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")
[[ "$source_tree" == "$expected_source_tree" ]] || {
  echo "::error::UI application source tree is $source_tree, not $expected_source_tree"
  exit 1
}
actual_sha=$(sha256sum "target/ui-application/${jar_name}" | awk '{print $1}')
[[ "$actual_sha" == "$expected_sha" ]] || {
  echo '::error::UI application digest mismatch'
  exit 1
}
mkdir -p taxonomy-app/target
cp "target/ui-application/${jar_name}" "taxonomy-app/target/${jar_name}"
echo "TAXONOMY_UI_SOURCE_SHA=$source_commit" >> "$GITHUB_ENV"
echo "TAXONOMY_UI_ARTIFACT_SHA256=$expected_sha" >> "$GITHUB_ENV"
