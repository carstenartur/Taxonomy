#!/usr/bin/env bash
set -euo pipefail

MODEL_REPOSITORY=${MODEL_REPOSITORY:-BAAI/bge-small-en-v1.5}
MODEL_REVISION=${MODEL_REVISION:-5c38ec7c405ec4b44b94cc5a9bb96e735b38267a}
MODEL_DIRECTORY=${MODEL_DIRECTORY:-models/bge-small-en-v1.5}
MODEL_ONNX_SHA256=${MODEL_ONNX_SHA256:-828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35}

BASE_URL="https://huggingface.co/${MODEL_REPOSITORY}/resolve/${MODEL_REVISION}"
mkdir -p "${MODEL_DIRECTORY}"

fetch() {
  local remote_path=$1
  local target_name=$2
  curl --fail --silent --show-error --location \
       --retry 4 --retry-all-errors --connect-timeout 20 \
       --output "${MODEL_DIRECTORY}/${target_name}" \
       "${BASE_URL}/${remote_path}"
}

fetch "onnx/model.onnx" "model.onnx"
fetch "tokenizer.json" "tokenizer.json"
fetch "tokenizer_config.json" "tokenizer_config.json"
fetch "special_tokens_map.json" "special_tokens_map.json"
fetch "config.json" "config.json"

printf '%s  %s\n' "${MODEL_ONNX_SHA256}" "${MODEL_DIRECTORY}/model.onnx" | sha256sum --check --strict

cat > "${MODEL_DIRECTORY}/MODEL_PROVENANCE.txt" <<EOF
repository=${MODEL_REPOSITORY}
revision=${MODEL_REVISION}
model_file=onnx/model.onnx
model_sha256=${MODEL_ONNX_SHA256}
license=MIT
source=https://huggingface.co/${MODEL_REPOSITORY}/tree/${MODEL_REVISION}
EOF

printf 'Pinned embedding model downloaded and verified: %s@%s\n' \
  "${MODEL_REPOSITORY}" "${MODEL_REVISION}"
