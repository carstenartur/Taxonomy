#!/usr/bin/env bash
set -euo pipefail

MODEL_REPOSITORY=${MODEL_REPOSITORY:-BAAI/bge-small-en-v1.5}
MODEL_REVISION=${MODEL_REVISION:-5c38ec7c405ec4b44b94cc5a9bb96e735b38267a}
MODEL_DIRECTORY=${MODEL_DIRECTORY:-models/bge-small-en-v1.5}
MODEL_ONNX_SHA256=${MODEL_ONNX_SHA256:-828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35}

BASE_URL="https://huggingface.co/${MODEL_REPOSITORY}/resolve/${MODEL_REVISION}"
REQUIRED_FILES=(model.onnx tokenizer.json tokenizer_config.json special_tokens_map.json config.json)

model_is_valid() {
  local file
  for file in "${REQUIRED_FILES[@]}"; do
    [[ -s "${MODEL_DIRECTORY}/${file}" ]] || return 1
  done
  printf '%s  %s\n' "${MODEL_ONNX_SHA256}" "${MODEL_DIRECTORY}/model.onnx" \
    | sha256sum --check --strict --status
}

if model_is_valid; then
  printf 'Using cached pinned embedding model: %s@%s\n' \
    "${MODEL_REPOSITORY}" "${MODEL_REVISION}"
  exit 0
fi

mkdir -p "$(dirname "${MODEL_DIRECTORY}")"
TEMP_DIRECTORY=$(mktemp -d "${MODEL_DIRECTORY}.download.XXXXXX")
trap 'rm -rf "${TEMP_DIRECTORY}"' EXIT

CURL_AUTH=()
if [[ -n "${HF_TOKEN:-}" ]]; then
  CURL_AUTH=(--header "Authorization: Bearer ${HF_TOKEN}")
fi

fetch() {
  local remote_path=$1
  local target_name=$2
  curl --fail --silent --show-error --location \
       --retry 12 --retry-all-errors --retry-max-time 600 \
       --connect-timeout 20 --max-time 300 \
       --user-agent "Taxonomy-CI/${GITHUB_SHA:-local}" \
       --header 'Accept: application/octet-stream' \
       "${CURL_AUTH[@]}" \
       --output "${TEMP_DIRECTORY}/${target_name}" \
       "${BASE_URL}/${remote_path}?download=true"
}

fetch "onnx/model.onnx" "model.onnx"
fetch "tokenizer.json" "tokenizer.json"
fetch "tokenizer_config.json" "tokenizer_config.json"
fetch "special_tokens_map.json" "special_tokens_map.json"
fetch "config.json" "config.json"

printf '%s  %s\n' "${MODEL_ONNX_SHA256}" "${TEMP_DIRECTORY}/model.onnx" \
  | sha256sum --check --strict

cat > "${TEMP_DIRECTORY}/MODEL_PROVENANCE.txt" <<EOF_PROVENANCE
repository=${MODEL_REPOSITORY}
revision=${MODEL_REVISION}
model_file=onnx/model.onnx
model_sha256=${MODEL_ONNX_SHA256}
license=MIT
source=https://huggingface.co/${MODEL_REPOSITORY}/tree/${MODEL_REVISION}
EOF_PROVENANCE

mkdir -p "${MODEL_DIRECTORY}"
for file in "${REQUIRED_FILES[@]}" MODEL_PROVENANCE.txt; do
  mv -f "${TEMP_DIRECTORY}/${file}" "${MODEL_DIRECTORY}/${file}"
done

printf 'Pinned embedding model downloaded and verified: %s@%s\n' \
  "${MODEL_REPOSITORY}" "${MODEL_REVISION}"
