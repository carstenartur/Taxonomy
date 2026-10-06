#!/usr/bin/env bash
set -euo pipefail

# Profiles own the repository, immutable revision, export and every file digest.
# A custom MODEL_DIRECTORY is a storage choice; it never selects another profile.
MODEL_PROFILE=${MODEL_PROFILE:-MULTILINGUAL_MINILM_L12}
REQUIRED_FILES=(model.onnx tokenizer.json tokenizer_config.json special_tokens_map.json config.json)
case "${MODEL_PROFILE}" in
  MULTILINGUAL_MINILM_L12)
    PINNED_REPOSITORY=sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
    PINNED_REVISION=e8f8c211226b894fcb81acc59f3b34ba3efd5f42
    DEFAULT_DIRECTORY=models/multilingual-minilm
    MODEL_REMOTE_PATH=onnx/model_quint8_avx2.onnx
    MODEL_LICENSE=Apache-2.0
    FILE_SHA256=(
      98a01d88b7de996cdea58c32ca71208c09968d143798814b2ea09d3439dc334f
      2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8
      5036ea374ffedd706e3bef33e2e0d6953cb868ef8a490e76e32ba0faa37a6b9b
      378eb3bf733eb16e65792d7e3fda5b8a4631387ca04d2015199c4d4f22ae554d
      6300193cb75e01cf80c96decef7187dfb33094d97cc1490b7ead6ff134476e4e
    )
    ;;
  BGE_SMALL_EN)
    PINNED_REPOSITORY=BAAI/bge-small-en-v1.5
    PINNED_REVISION=5c38ec7c405ec4b44b94cc5a9bb96e735b38267a
    DEFAULT_DIRECTORY=models/bge-small-en-v1.5
    MODEL_REMOTE_PATH=onnx/model.onnx
    MODEL_LICENSE=MIT
    FILE_SHA256=(
      828e1496d7fabb79cfa4dcd84fa38625c0d3d21da474a00f08db0f559940cf35
      d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66
      9261e7d79b44c8195c1cada2b453e55b00aeb81e907a6664974b4d7776172ab3
      b6d346be366a7d1d48332dbc9fdf3bf8960b5d879522b7799ddba59e76237ee3
      094f8e891b932f2000c92cfc663bac4c62069f5d8af5b5278c4306aef3084750
    )
    ;;
  *)
    printf 'Unsupported embedding model profile: %s\n' "${MODEL_PROFILE}" >&2
    exit 1
    ;;
esac

# Retain compatibility with matching old callers, but never weaken the trust root.
if [[ "${MODEL_REPOSITORY:-${PINNED_REPOSITORY}}" != "${PINNED_REPOSITORY}" ||
      "${MODEL_REVISION:-${PINNED_REVISION}}" != "${PINNED_REVISION}" ||
      "${MODEL_ONNX_SHA256:-${FILE_SHA256[0]}}" != "${FILE_SHA256[0]}" ]]; then
  printf 'Repository, revision and checksum overrides must match the selected pinned profile.\n' >&2
  exit 1
fi
MODEL_REPOSITORY=${PINNED_REPOSITORY}
MODEL_REVISION=${PINNED_REVISION}
MODEL_DIRECTORY=${MODEL_DIRECTORY:-${DEFAULT_DIRECTORY}}
MODEL_ONNX_SHA256=${FILE_SHA256[0]}
BASE_URL="https://huggingface.co/${MODEL_REPOSITORY}/resolve/${MODEL_REVISION}"

model_is_valid() {
  local directory=${1:-${MODEL_DIRECTORY}}
  local index file
  for index in "${!REQUIRED_FILES[@]}"; do
    file=${REQUIRED_FILES[index]}
    [[ -s "${directory}/${file}" ]] || return 1
    printf '%s  %s\n' "${FILE_SHA256[index]}" "${directory}/${file}" \
      | sha256sum --check --strict --status || return 1
  done
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

fetch "${MODEL_REMOTE_PATH}" "model.onnx"
fetch "tokenizer.json" "tokenizer.json"
fetch "tokenizer_config.json" "tokenizer_config.json"
fetch "special_tokens_map.json" "special_tokens_map.json"
fetch "config.json" "config.json"

if ! model_is_valid "${TEMP_DIRECTORY}"; then
  printf 'Downloaded embedding model bundle failed SHA-256 verification.\n' >&2
  exit 1
fi

cat > "${TEMP_DIRECTORY}/MODEL_PROVENANCE.txt" <<EOF_PROVENANCE
profile=${MODEL_PROFILE}
repository=${MODEL_REPOSITORY}
revision=${MODEL_REVISION}
model_file=${MODEL_REMOTE_PATH}
model_sha256=${MODEL_ONNX_SHA256}
tokenizer_sha256=${FILE_SHA256[1]}
tokenizer_config_sha256=${FILE_SHA256[2]}
special_tokens_map_sha256=${FILE_SHA256[3]}
config_sha256=${FILE_SHA256[4]}
license=${MODEL_LICENSE}
source=https://huggingface.co/${MODEL_REPOSITORY}/tree/${MODEL_REVISION}
EOF_PROVENANCE

mkdir -p "${MODEL_DIRECTORY}"
for file in "${REQUIRED_FILES[@]}" MODEL_PROVENANCE.txt; do
  mv -f "${TEMP_DIRECTORY}/${file}" "${MODEL_DIRECTORY}/${file}"
done

printf 'Pinned embedding model downloaded and verified: %s@%s\n' \
  "${MODEL_REPOSITORY}" "${MODEL_REVISION}"
