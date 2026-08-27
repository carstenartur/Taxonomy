#!/usr/bin/env bash
set -euo pipefail

mapfile -t jars < <(find taxonomy-app/target -maxdepth 1 -type f \
  -name 'taxonomy-app-*.jar' ! -name 'original-*' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort)
if [[ ${#jars[@]} -ne 1 ]]; then
  echo "::error::Expected one executable application JAR, found ${#jars[@]}"
  printf '%s\n' "${jars[@]}"
  exit 1
fi
mkdir -p target/ui-application
jar_name=$(basename "${jars[0]}")
cp "${jars[0]}" "target/ui-application/${jar_name}"
jar_sha=$(sha256sum "target/ui-application/${jar_name}" | awk '{print $1}')
source_tree=$(git rev-parse "${GITHUB_SHA}^{tree}")
jq -n --arg sourceCommit "$GITHUB_SHA" --arg sourceTree "$source_tree" \
  --arg jarName "$jar_name" --arg sha256 "$jar_sha" \
  '{schemaVersion:1,sourceCommit:$sourceCommit,sourceTree:$sourceTree,jarName:$jarName,sha256:$sha256}' \
  > target/ui-application/manifest.json
printf '%s  %s\n' "$jar_sha" "$jar_name" > target/ui-application/SHA256SUMS
