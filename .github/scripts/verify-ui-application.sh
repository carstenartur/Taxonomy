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
# An old deployment must not add executable code or redirect a verified copy.
for destination in taxonomy-app taxonomy-app/target taxonomy-app/target/plugins taxonomy-app/target/features; do
  [[ ! -L "$destination" ]] || { echo '::error::Installed distribution path is a link'; exit 1; }
done
for kind in plugins features; do
  destination="taxonomy-app/target/$kind"
  if [[ -d "$destination" ]]; then
    while IFS= read -r -d '' installed; do
      [[ -f "$installed" && ! -L "$installed" ]] || { echo '::error::Installed distribution contains a link or non-file'; exit 1; }
      installed_name=$(basename "$installed")
      jq -e --arg kind "$kind" --arg name "$installed_name" '.[$kind] | any(.name == $name)' "$manifest" > /dev/null || {
        echo '::error::Installed distribution contains unlisted code'; exit 1;
      }
    done < <(find "$destination" -mindepth 1 -maxdepth 1 -print0)
  fi
done

mkdir -p taxonomy-app/target
cp "target/ui-application/${jar_name}" "taxonomy-app/target/${jar_name}"
echo "TAXONOMY_UI_SOURCE_SHA=$source_commit" >> "$GITHUB_ENV"
echo "TAXONOMY_UI_ARTIFACT_SHA256=$expected_sha" >> "$GITHUB_ENV"

# Refuse incomplete or substituted external code before starting the application.
plugin_count=$(jq -er '.plugins | length | select(. > 0)' "$manifest")
jq -e '.plugins | length == (map(.name) | unique | length)' "$manifest" > /dev/null || {
  echo '::error::Duplicate external plugin names'; exit 1;
}
[[ -z "$(find target/ui-application/plugins -mindepth 1 -maxdepth 1 ! -type f -print -quit)" ]] || {
  echo '::error::External plugin distribution contains a link or non-file entry'; exit 1;
}
actual_plugin_count=$(find target/ui-application/plugins -maxdepth 1 -type f -name '*.jar' | wc -l)
[[ "$plugin_count" -eq "$actual_plugin_count" ]] || { echo '::error::External plugin inventory mismatch'; exit 1; }
while IFS=$'\t' read -r plugin_name plugin_sha; do
  [[ "$plugin_name" =~ ^taxonomy-[A-Za-z0-9._+-]+\.jar$ && "$plugin_sha" =~ ^[a-f0-9]{64}$ ]] || {
    echo '::error::Unsafe external plugin manifest'; exit 1;
  }
  actual_plugin_sha=$(sha256sum "target/ui-application/plugins/$plugin_name" | awk '{print $1}')
  [[ "$actual_plugin_sha" == "$plugin_sha" ]] || { echo '::error::External plugin digest mismatch'; exit 1; }
done < <(jq -r '.plugins[] | [.name,.sha256] | @tsv' "$manifest")
mkdir -p taxonomy-app/target/plugins
cp target/ui-application/plugins/*.jar taxonomy-app/target/plugins/

jq -e '(.features | map(.id) | sort) == ["analysis","architecture","interop","portfolio","reporting","templates"]
       and (.features | length == (map(.name) | unique | length))' "$manifest" > /dev/null || {
  echo '::error::Default startup feature inventory is incomplete or duplicated'; exit 1;
}
[[ -z "$(find target/ui-application/features -mindepth 1 -maxdepth 1 ! -type f -print -quit)" ]] || {
  echo '::error::Startup feature distribution contains a link or non-file entry'; exit 1;
}
[[ $(find target/ui-application/features -maxdepth 1 -type f -name '*.jar' | wc -l) -eq 6 ]] || {
  echo '::error::Startup feature inventory mismatch'; exit 1;
}
while IFS=$'\t' read -r feature_id feature_name feature_sha; do
  [[ "$feature_name" =~ ^taxonomy-${feature_id}-[0-9][A-Za-z0-9._+-]*\.jar$ && "$feature_sha" =~ ^[a-f0-9]{64}$ ]] || {
    echo '::error::Unsafe startup feature manifest'; exit 1;
  }
  [[ $(sha256sum "target/ui-application/features/$feature_name" | awk '{print $1}') == "$feature_sha" ]] || {
    echo '::error::Startup feature digest mismatch'; exit 1;
  }
done < <(jq -r '.features[] | [.id,.name,.sha256] | @tsv' "$manifest")
mkdir -p taxonomy-app/target/features
cp target/ui-application/features/*.jar taxonomy-app/target/features/
