#!/usr/bin/env bash
# Maven's ProductionImageIT owns invocation, image inspection and cold-start tests.
# Persistent cache parameters are optional; ordinary local Docker builds still work.
set -euo pipefail
if (( $# != 3 )) || [[ ! "$2" =~ ^[a-f0-9]{40}$ ]] || [[ "$3" != standard && "$3" != onnx ]]; then
  echo 'Usage: build-production-image.sh TAG COMMIT standard|onnx' >&2
  exit 2
fi
tag=$1 commit=$2 target=$3
cache_dir=${TAXONOMY_DOCKER_CACHE_DIRECTORY:-}
arguments=(docker build)
if [[ -n "$cache_dir" || -n "${TAXONOMY_DOCKER_BUILDER:-}" ]]; then
  arguments=(docker buildx build --load)
  if [[ -n "${TAXONOMY_DOCKER_BUILDER:-}" ]]; then arguments+=(--builder "$TAXONOMY_DOCKER_BUILDER"); fi
fi
arguments+=(--progress=plain --target "$target" --build-arg BUILD_DATE=1970-01-01T00:00:00Z
  --build-arg "VCS_REF=$commit" --build-arg "VERSION=ci-$commit" --tag "$tag")
cache_arguments=()
export_dir=
log=$(mktemp)
cleanup() { rm -f "$log"; if [[ -n "$export_dir" ]]; then rm -rf -- "$export_dir"; fi; }
trap cleanup EXIT
imported=false
if [[ -n "$cache_dir" ]]; then
  mkdir -p "$cache_dir"
  # Only directories created by this helper are replaced. Never touch host ~/.m2.
  export_dir=$(mktemp -d "$cache_dir/.export-XXXXXX")
  if [[ -f "$cache_dir/current/index.json" ]] && jq -e \
      '.schemaVersion == 2 and (.manifests | type == "array")' "$cache_dir/current/index.json" >/dev/null 2>&1; then
    cache_arguments+=(--cache-from "type=local,src=$cache_dir/current")
    imported=true
  fi
  cache_arguments+=(--cache-to "type=local,dest=$export_dir,mode=max,ignore-error=true")
fi
run_build() {
  set +e
  "${arguments[@]}" "$@" . 2>&1 | tee -a "$log"
  statuses=("${PIPESTATUS[@]}")
  set -e
  if (( statuses[0] != 0 )); then return "${statuses[0]}"; fi
  return "${statuses[1]}"
}
result=0
run_build "${cache_arguments[@]}" || result=$?
if (( result != 0 )) && [[ "$imported" == true ]]; then
  echo '::warning::Cached Docker build failed; retrying a real build without external cache.'
  result=0
  run_build || result=$?
fi
if (( result != 0 )); then exit "$result"; fi
if [[ -n "$export_dir" && -f "$export_dir/index.json" ]]; then
  rm -rf -- "$cache_dir/current"
  mv -- "$export_dir" "$cache_dir/current"
  export_dir=
fi
cached=$(grep -cE '^#[0-9]+ CACHED$' "$log" || true)
echo "Docker cache: target=$target imported=$imported cached_steps=$cached"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf '\nDocker cache (%s): imported=%s; CACHED steps=%s. Image and cold-start verification run freshly through Maven.\n' \
    "$target" "$imported" "$cached" >> "$GITHUB_STEP_SUMMARY"
fi
