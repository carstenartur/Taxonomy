#!/usr/bin/env bash
set -euo pipefail

HELM_VERSION=${HELM_VERSION:-v3.21.0}
HELM_INSTALL_DIR=${HELM_INSTALL_DIR:-"${HOME}/.local/bin"}

if [[ "${HELM_VERSION}" != "v3.21.0" ]]; then
  echo "Unsupported Helm version ${HELM_VERSION}; add and review its official checksum before updating" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) platform=linux ;;
  *)
    echo "This verified installer currently supports Linux runners only" >&2
    exit 1
    ;;
esac

case "$(uname -m)" in
  x86_64|amd64)
    arch=amd64
    sha256=0093eb572e3d2380f094df162ddb525e219249de88957afe24cfbb19632acd36
    ;;
  aarch64|arm64)
    arch=arm64
    sha256=8de5a0c9a47431e59fd560e91e0779c8cf9316c383da7efb84128a4c339ecb2d
    ;;
  *)
    echo "Unsupported runner architecture: $(uname -m)" >&2
    exit 1
    ;;
esac

archive="helm-${HELM_VERSION}-${platform}-${arch}.tar.gz"
tmp_dir=$(mktemp -d)
trap 'rm -rf "${tmp_dir}"' EXIT

curl --fail --silent --show-error --location --retry 3 \
  "https://get.helm.sh/${archive}" \
  --output "${tmp_dir}/${archive}"
printf '%s  %s\n' "${sha256}" "${tmp_dir}/${archive}" | sha256sum --check --strict -

tar --extract --gzip --file "${tmp_dir}/${archive}" --directory "${tmp_dir}"
mkdir -p "${HELM_INSTALL_DIR}"
install -m 0755 "${tmp_dir}/${platform}-${arch}/helm" "${HELM_INSTALL_DIR}/helm"

if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "${HELM_INSTALL_DIR}" >> "${GITHUB_PATH}"
fi

"${HELM_INSTALL_DIR}/helm" version --short
