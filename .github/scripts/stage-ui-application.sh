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

# External code is part of the commit-bound distribution and has its own digest.
mkdir -p target/ui-application/plugins
mapfile -t plugins < <(find taxonomy-app/target/plugins -maxdepth 1 -type f -name '*.jar' | sort)
[[ ${#plugins[@]} -gt 0 ]] || { echo '::error::Standard external plugin distribution is missing'; exit 1; }
plugin_manifest='[]'
for plugin in "${plugins[@]}"; do
  plugin_name=$(basename "$plugin")
  [[ "$plugin_name" =~ ^taxonomy-[A-Za-z0-9._+-]+\.jar$ ]] || exit 1
  cp "$plugin" "target/ui-application/plugins/$plugin_name"
  plugin_sha=$(sha256sum "$plugin" | awk '{print $1}')
  plugin_manifest=$(jq --arg name "$plugin_name" --arg sha256 "$plugin_sha" '. + [{name:$name,sha256:$sha256}]' <<< "$plugin_manifest")
  printf '%s  plugins/%s\n' "$plugin_sha" "$plugin_name" >> target/ui-application/SHA256SUMS
done
jq --argjson plugins "$plugin_manifest" '. + {plugins:$plugins}' target/ui-application/manifest.json > target/ui-application/manifest.next.json
mv target/ui-application/manifest.next.json target/ui-application/manifest.json
