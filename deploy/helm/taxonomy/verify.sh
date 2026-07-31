#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
CHART_DIR="${ROOT_DIR}/deploy/helm/taxonomy"
OUTPUT_FILE=${1:-"${ROOT_DIR}/target/taxonomy-helm-rendered.yaml"}
VALID_TAG=sha-0123456789abcdef0123456789abcdef01234567

if ! command -v helm >/dev/null 2>&1; then
  echo "Helm 3 is required to validate ${CHART_DIR}" >&2
  exit 1
fi

mkdir -p "$(dirname "${OUTPUT_FILE}")"
TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

COMMON_VALUES=(
  --set "image.tag=${VALID_TAG}"
  --set existingSecret=taxonomy-secrets
)

expect_failure() {
  local description=$1
  shift
  local log_file="${TMP_DIR}/failure.log"
  if "$@" >"${log_file}" 2>&1; then
    echo "Expected failure was accepted: ${description}" >&2
    return 1
  fi
}

helm lint "${CHART_DIR}" "${COMMON_VALUES[@]}"
helm template taxonomy "${CHART_DIR}" \
  "${COMMON_VALUES[@]}" \
  --namespace taxonomy \
  --set serviceMonitor.enabled=true \
  >"${OUTPUT_FILE}"

for required in \
  'kind: Deployment' \
  'kind: NetworkPolicy' \
  'kind: ServiceMonitor' \
  'namespace: taxonomy' \
  'automountServiceAccountToken: false' \
  'runAsNonRoot: true' \
  'runAsUser: 10001' \
  'readOnlyRootFilesystem: true' \
  'maxUnavailable: 0' \
  'path: /actuator/health/readiness' \
  'type: Bearer' \
  'key: "ADMIN_PASSWORD"'; do
  if ! grep -Fq "${required}" "${OUTPUT_FILE}"; then
    echo "Rendered chart is missing required contract: ${required}" >&2
    exit 1
  fi
done

PRERELEASE_OUTPUT="${TMP_DIR}/prerelease.yaml"
helm template taxonomy "${CHART_DIR}" \
  --namespace taxonomy \
  --set image.tag=v1.2.3-rc.1 \
  --set existingSecret=taxonomy-secrets \
  >"${PRERELEASE_OUTPUT}"
grep -Fq 'image: "ghcr.io/carstenartur/taxonomy:v1.2.3-rc.1"' "${PRERELEASE_OUTPUT}"

PERSISTENCE_OUTPUT="${TMP_DIR}/persistence.yaml"
helm template taxonomy "${CHART_DIR}" \
  "${COMMON_VALUES[@]}" \
  --set persistence.enabled=true \
  --set config.TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem \
  >"${PERSISTENCE_OUTPUT}"
grep -Fq 'kind: PersistentVolumeClaim' "${PERSISTENCE_OUTPUT}"
grep -Fq 'type: Recreate' "${PERSISTENCE_OUTPUT}"

MULTI_OUTPUT="${TMP_DIR}/multi-replica.yaml"
helm template taxonomy "${CHART_DIR}" \
  "${COMMON_VALUES[@]}" \
  --set replicaCount=2 \
  --set scaling.allowMultipleReplicas=true \
  --set podDisruptionBudget.enabled=true \
  >"${MULTI_OUTPUT}"
grep -Fq 'kind: PodDisruptionBudget' "${MULTI_OUTPUT}"

expect_failure 'missing immutable image reference' \
  helm template taxonomy "${CHART_DIR}" --set existingSecret=taxonomy-secrets
expect_failure 'mutable latest image tag' \
  helm template taxonomy "${CHART_DIR}" \
    --set image.tag=latest --set existingSecret=taxonomy-secrets
expect_failure 'arbitrary mutable image tag' \
  helm template taxonomy "${CHART_DIR}" \
    --set image.tag=stable --set existingSecret=taxonomy-secrets
expect_failure 'Docker-invalid SemVer build metadata' \
  helm template taxonomy "${CHART_DIR}" \
    --set image.tag=v1.2.3+metadata --set existingSecret=taxonomy-secrets
expect_failure 'simultaneous image tag and digest' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" \
    --set image.digest=sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
expect_failure 'missing credentials Secret' \
  helm template taxonomy "${CHART_DIR}" --set "image.tag=${VALID_TAG}"
expect_failure 'multiple replicas without explicit acknowledgement' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" --set replicaCount=2
expect_failure 'persistent multi-replica deployment' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" \
    --set replicaCount=2 \
    --set scaling.allowMultipleReplicas=true \
    --set persistence.enabled=true
expect_failure 'single-replica PodDisruptionBudget' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" --set podDisruptionBudget.enabled=true
expect_failure 'disk-backed Lucene without persistence' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" \
    --set config.TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
expect_failure 'Ingress blocked by an unconfigured NetworkPolicy' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" --set ingress.enabled=true
expect_failure 'ServiceMonitor authorization without a Secret' \
  helm template taxonomy "${CHART_DIR}" \
    --set "image.tag=${VALID_TAG}" \
    --set-json secretEnv='{}' \
    --set serviceMonitor.enabled=true

printf 'Helm chart verification passed. Rendered evidence: %s\n' "${OUTPUT_FILE}"
