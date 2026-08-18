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

mkdir -p "$(dirname "${OUTPUT_FILE}")" "${ROOT_DIR}/target"
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
  'type: Recreate' \
  'path: /actuator/health/readiness' \
  'type: Bearer' \
  'key: "ADMIN_PASSWORD"'; do
  if ! grep -Fq "${required}" "${OUTPUT_FILE}"; then
    echo "Rendered chart is missing required contract: ${required}" >&2
    exit 1
  fi
done
for forbidden in 'type: RollingUpdate' 'rollingUpdate:' 'maxUnavailable:' 'maxSurge:'; do
  if grep -Fq "${forbidden}" "${OUTPUT_FILE}"; then
    echo "Default chart must not render concurrent-version rollout field: ${forbidden}" >&2
    exit 1
  fi
done

ROLLING_OUTPUT="${TMP_DIR}/rolling.yaml"
helm template taxonomy "${CHART_DIR}" \
  "${COMMON_VALUES[@]}" \
  --namespace taxonomy \
  --set upgrade.strategy=RollingUpdate \
  --set upgrade.allowConcurrentApplicationVersions=true \
  >"${ROLLING_OUTPUT}"
for required in 'type: RollingUpdate' 'maxUnavailable: 0' 'maxSurge: 1'; do
  if ! grep -Fq "${required}" "${ROLLING_OUTPUT}"; then
    echo "Explicit rolling-update render is missing: ${required}" >&2
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
grep -Fq 'type: Recreate' "${PRERELEASE_OUTPUT}"

OPENSHIFT_OUTPUT="${TMP_DIR}/openshift.yaml"
helm template taxonomy "${CHART_DIR}" \
  --namespace taxonomy \
  --values "${CHART_DIR}/values-openshift.yaml" \
  --set "image.tag=${VALID_TAG}" \
  --set existingSecret=taxonomy-secrets \
  >"${OPENSHIFT_OUTPUT}"
grep -Fq 'runAsNonRoot: true' "${OPENSHIFT_OUTPUT}"
grep -Fq 'readOnlyRootFilesystem: true' "${OPENSHIFT_OUTPUT}"
grep -Fq 'type: Recreate' "${OPENSHIFT_OUTPUT}"
for forbidden in 'runAsUser:' 'runAsGroup:' 'fsGroup:' 'fsGroupChangePolicy:'; do
  if grep -Fq "${forbidden}" "${OPENSHIFT_OUTPUT}"; then
    echo "OpenShift profile must leave ${forbidden%:} to the cluster SCC" >&2
    exit 1
  fi
done

SMALL_OUTPUT="${TMP_DIR}/small.yaml"
SMALL_EVIDENCE="${ROOT_DIR}/target/taxonomy-helm-small-rendered.yaml"
helm lint "${CHART_DIR}" \
  --values "${CHART_DIR}/values-small.yaml" \
  "${COMMON_VALUES[@]}"
helm template taxonomy "${CHART_DIR}" \
  --namespace taxonomy \
  --values "${CHART_DIR}/values-small.yaml" \
  --set "image.tag=${VALID_TAG}" \
  --set existingSecret=taxonomy-secrets \
  >"${SMALL_OUTPUT}"
cp "${SMALL_OUTPUT}" "${SMALL_EVIDENCE}"
for required in \
  'cpu: 100m' \
  'cpu: 500m' \
  'memory: 768Mi' \
  'memory: 1536Mi' \
  'name: TAXONOMY_EMBEDDING_ENABLED' \
  'value: "false"' \
  'name: TAXONOMY_SEARCH_DIRECTORY_TYPE' \
  'value: "local-heap"' \
  'MaxRAMPercentage=65.0' \
  'type: Recreate'; do
  if ! grep -Fq "${required}" "${SMALL_OUTPUT}"; then
    echo "Rendered small profile is missing required contract: ${required}" >&2
    exit 1
  fi
done
if grep -Fq 'cpu: "2"' "${SMALL_OUTPUT}"; then
  echo "Small profile must not inherit the universal two-CPU limit" >&2
  exit 1
fi

RANCHER_OUTPUT="${TMP_DIR}/rancher.yaml"
RANCHER_EVIDENCE="${ROOT_DIR}/target/taxonomy-helm-rancher-rke2-rendered.yaml"
helm template taxonomy "${CHART_DIR}" \
  --namespace taxonomy \
  --values "${CHART_DIR}/values-rancher-rke2.yaml" \
  --set "image.tag=${VALID_TAG}" \
  --set existingSecret=taxonomy-secrets \
  >"${RANCHER_OUTPUT}"
cp "${RANCHER_OUTPUT}" "${RANCHER_EVIDENCE}"
for required in \
  'kind: Ingress' \
  'ingressClassName: nginx' \
  'nginx.ingress.kubernetes.io/rewrite-target: /$2' \
  'nginx.ingress.kubernetes.io/x-forwarded-prefix: /taxonomy' \
  'path: /taxonomy(/|$)(.*)' \
  'pathType: ImplementationSpecific' \
  'cpu: 500m' \
  'type: Recreate' \
  'kubernetes.io/metadata.name: kube-system' \
  'kubernetes.io/metadata.name: ingress-nginx'; do
  if ! grep -Fq "${required}" "${RANCHER_OUTPUT}"; then
    echo "Rendered Rancher profile is missing required contract: ${required}" >&2
    exit 1
  fi
done

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
grep -Fq 'type: Recreate' "${MULTI_OUTPUT}"

PACKAGE_DIR="${TMP_DIR}/package"
mkdir -p "${PACKAGE_DIR}"
helm package "${CHART_DIR}" \
  --destination "${PACKAGE_DIR}" \
  --version 1.2.3 \
  --app-version 1.2.3 >/dev/null
RELEASE_ARCHIVE="${PACKAGE_DIR}/taxonomy-1.2.3.tgz"
test -f "${RELEASE_ARCHIVE}"
tar -tzf "${RELEASE_ARCHIVE}" > "${TMP_DIR}/release-chart-contents.txt"
grep -Fxq 'taxonomy/questions.yaml' "${TMP_DIR}/release-chart-contents.txt"
helm lint "${RELEASE_ARCHIVE}" --set existingSecret=taxonomy-secrets
PACKAGED_OUTPUT="${TMP_DIR}/packaged-release.yaml"
helm template taxonomy "${RELEASE_ARCHIVE}" \
  --namespace taxonomy \
  --set existingSecret=taxonomy-secrets \
  >"${PACKAGED_OUTPUT}"
grep -Fq 'image: "ghcr.io/carstenartur/taxonomy:v1.2.3"' "${PACKAGED_OUTPUT}"
grep -Fq 'type: Recreate' "${PACKAGED_OUTPUT}"
if grep -Fq 'type: RollingUpdate' "${PACKAGED_OUTPUT}"; then
  echo "Packaged release chart must default to Recreate" >&2
  exit 1
fi

expect_failure 'missing immutable image reference in a source/SNAPSHOT chart' \
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
expect_failure 'invalid upgrade strategy' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" --set upgrade.strategy=BlueGreen
expect_failure 'RollingUpdate without database compatibility acknowledgement' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" --set upgrade.strategy=RollingUpdate
expect_failure 'persistent RollingUpdate deployment' \
  helm template taxonomy "${CHART_DIR}" \
    "${COMMON_VALUES[@]}" \
    --set upgrade.strategy=RollingUpdate \
    --set upgrade.allowConcurrentApplicationVersions=true \
    --set persistence.enabled=true \
    --set config.TAXONOMY_SEARCH_DIRECTORY_TYPE=local-filesystem
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

for required in \
  'workflow_run:' \
  'packages: write' \
  'oci://ghcr.io/${GITHUB_REPOSITORY_OWNER}/charts' \
  'oci://ghcr.io/carstenartur/charts/taxonomy'; do
  if ! grep -Fq "${required}" "${ROOT_DIR}/.github/workflows/publish-helm-oci.yml"; then
    echo "OCI publication workflow is missing required contract: ${required}" >&2
    exit 1
  fi
done

grep -Fq 'publish-helm-oci.yml' "${ROOT_DIR}/.mvn/verification-suites.json"
grep -Fq 'upgrade.strategy' "${CHART_DIR}/questions.yaml"

printf 'Helm chart verification passed. Rendered evidence: %s\n' "${OUTPUT_FILE}"
