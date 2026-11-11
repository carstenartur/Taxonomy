#!/usr/bin/env bash
set -euo pipefail

# A partial rerun can reuse older successful shards. Never overlay two report archives.
groups=$(jq -er '.applicationTestShards.groups | keys[]' .mvn/verification-suites.json)
[[ ${GITHUB_RUN_ATTEMPT:?} =~ ^[1-9][0-9]*$ && ${GITHUB_RUN_ID:?} =~ ^[1-9][0-9]*$ ]]
destination=target/app-test-shards
[[ ! -e "$destination" ]]
staging=$(mktemp -d target/app-test-shards.XXXXXX)
trap 'rm -rf -- "$staging"' EXIT

while read -r shard; do
  for ((attempt=GITHUB_RUN_ATTEMPT; attempt>=1; attempt--)); do
    archive="app-test-shard-$shard-attempt-$attempt-success"
    directory="target/app-test-shard-attempts/$archive"
    [[ -d "$directory" ]] || continue
    build="$GITHUB_RUN_ID.$attempt.$shard"
    if ! jq -e --arg build "$build" '.buildId == $build' "$directory/$shard/manifest.json" >/dev/null; then
      echo "::error::$archive does not contain the expected build $build" >&2
      exit 1
    fi
    cp -a -- "$directory/$shard" "$staging/$shard"
    echo "Selected $archive ($build)"
    break
  done
  if [[ ! -d "$staging/$shard" ]]; then
    echo "::error::Missing successful application test evidence for $shard" >&2
    exit 1
  fi
done <<< "$groups"
mv -T -- "$staging" "$destination"
