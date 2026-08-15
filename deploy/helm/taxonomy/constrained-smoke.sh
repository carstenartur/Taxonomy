#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
CHART_DIR="${ROOT_DIR}/deploy/helm/taxonomy"
NAMESPACE=${TAXONOMY_SMOKE_NAMESPACE:-taxonomy-smoke}
RELEASE=${TAXONOMY_SMOKE_RELEASE:-taxonomy}
KIND_CLUSTER_NAME=${KIND_CLUSTER_NAME:-taxonomy-smoke}
SOURCE_SHA=${SOURCE_SHA:-$(git -C "${ROOT_DIR}" rev-parse HEAD)}
IMAGE_REPOSITORY=${IMAGE_REPOSITORY:-taxonomy-smoke}
IMAGE_TAG=${IMAGE_TAG:-sha-${SOURCE_SHA}}
IMAGE_REFERENCE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"
EVIDENCE_DIR=${EVIDENCE_DIR:-"${ROOT_DIR}/target/kubernetes-smoke"}
KEEP_RESOURCES=${KEEP_RESOURCES:-false}
PORT_FORWARD_PID=""

fail() {
  echo "::error::$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_RESOURCES}" != "true" ]]; then
    helm uninstall "${RELEASE}" --namespace "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl delete namespace "${NAMESPACE}" --wait=false >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

for command in docker kind kubectl helm curl jq; do
  command -v "${command}" >/dev/null 2>&1 \
    || fail "${command} is required for the constrained-cluster smoke test"
done
if ! [[ "${SOURCE_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  fail "SOURCE_SHA must be a full 40-character Git commit ID"
fi
if ! [[ "${IMAGE_TAG}" =~ ^sha-[0-9a-f]{40}$ ]]; then
  fail "IMAGE_TAG must use sha-<40 lowercase hex>"
fi
if [[ "${KEEP_RESOURCES}" != "true" && "${KEEP_RESOURCES}" != "false" ]]; then
  fail "KEEP_RESOURCES must be true or false"
fi

mkdir -p "${EVIDENCE_DIR}"
STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
START_EPOCH=$(date +%s)
BUILD_DATE=$(git -C "${ROOT_DIR}" show -s --format=%cI "${SOURCE_SHA}")
VERSION=$(./mvnw -q -f "${ROOT_DIR}/pom.xml" -DforceStdout help:evaluate \
  -Dexpression=project.version)

printf 'Building constrained-smoke image %s from %s\n' \
  "${IMAGE_REFERENCE}" "${SOURCE_SHA}"
docker build \
  --build-arg "BUILD_DATE=${BUILD_DATE}" \
  --build-arg "VCS_REF=${SOURCE_SHA}" \
  --build-arg "VERSION=${VERSION}" \
  --tag "${IMAGE_REFERENCE}" \
  "${ROOT_DIR}"
IMAGE_ID=$(docker image inspect "${IMAGE_REFERENCE}" --format '{{.Id}}')
if ! [[ "${IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  fail "Docker did not return an immutable local image ID"
fi

kind get clusters | grep -Fxq "${KIND_CLUSTER_NAME}" \
  || fail "kind cluster ${KIND_CLUSTER_NAME} does not exist"
kind load docker-image "${IMAGE_REFERENCE}" --name "${KIND_CLUSTER_NAME}"

kubectl apply -f "${CHART_DIR}/constrained-smoke-prerequisites.yaml"
kubectl wait --for=jsonpath='{.status.phase}'=Active \
  "namespace/${NAMESPACE}" --timeout=60s

helm lint "${CHART_DIR}" \
  --values "${CHART_DIR}/values-constrained-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" \
  --set "image.tag=${IMAGE_TAG}" \
  --set-json secretEnv='{}'

RENDERED_MANIFEST="${EVIDENCE_DIR}/rendered.yaml"
helm template "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NAMESPACE}" \
  --values "${CHART_DIR}/values-constrained-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" \
  --set "image.tag=${IMAGE_TAG}" \
  --set-json secretEnv='{}' \
  >"${RENDERED_MANIFEST}"

if grep -A3 '^  egress:' "${RENDERED_MANIFEST}" | grep -Fq -- '- {}'; then
  fail "constrained smoke manifest contains unrestricted NetworkPolicy egress"
fi
grep -Fq 'kind: ResourceQuota' \
  "${CHART_DIR}/constrained-smoke-prerequisites.yaml"
grep -Fq 'kind: LimitRange' \
  "${CHART_DIR}/constrained-smoke-prerequisites.yaml"
grep -Fq 'kubernetes.io/metadata.name: kube-system' "${RENDERED_MANIFEST}"
grep -Fq 'protocol: UDP' "${RENDERED_MANIFEST}"
grep -Fq 'protocol: TCP' "${RENDERED_MANIFEST}"

helm upgrade --install "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NAMESPACE}" \
  --values "${CHART_DIR}/values-constrained-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" \
  --set "image.tag=${IMAGE_TAG}" \
  --set-json secretEnv='{}' \
  --atomic \
  --wait \
  --timeout 12m

kubectl rollout status "deployment/${RELEASE}" \
  --namespace "${NAMESPACE}" --timeout=2m
READY_EPOCH=$(date +%s)
READINESS_SECONDS=$((READY_EPOCH - START_EPOCH))
POD=$(kubectl get pod --namespace "${NAMESPACE}" \
  -l app.kubernetes.io/instance="${RELEASE}" \
  -o jsonpath='{.items[0].metadata.name}')
[[ -n "${POD}" ]] || fail "Taxonomy pod was not created"

kubectl get resourcequota,limitrange,pod,service,networkpolicy \
  --namespace "${NAMESPACE}" -o yaml \
  >"${EVIDENCE_DIR}/cluster-resources.yaml"
kubectl describe pod "${POD}" --namespace "${NAMESPACE}" \
  >"${EVIDENCE_DIR}/pod-describe.txt"
kubectl logs "${POD}" --namespace "${NAMESPACE}" \
  >"${EVIDENCE_DIR}/application.log"

kubectl port-forward --namespace "${NAMESPACE}" \
  "service/${RELEASE}" 18080:80 \
  >"${EVIDENCE_DIR}/port-forward.log" 2>&1 &
PORT_FORWARD_PID=$!
for _ in $(seq 1 60); do
  if curl --fail --silent --show-error \
      http://127.0.0.1:18080/actuator/health/readiness \
      >"${EVIDENCE_DIR}/readiness.json"; then
    break
  fi
  sleep 2
done
curl --fail --silent --show-error http://127.0.0.1:18080/ \
  >"${EVIDENCE_DIR}/home.html"
grep -Fq 'Taxonomy' "${EVIDENCE_DIR}/home.html"
jq -e '.status == "UP"' "${EVIDENCE_DIR}/readiness.json" >/dev/null

POD_IMAGE_ID=$(kubectl get pod "${POD}" --namespace "${NAMESPACE}" \
  -o jsonpath='{.status.containerStatuses[0].imageID}')
RESTARTS=$(kubectl get pod "${POD}" --namespace "${NAMESPACE}" \
  -o jsonpath='{.status.containerStatuses[0].restartCount}')
NODE=$(kubectl get pod "${POD}" --namespace "${NAMESPACE}" \
  -o jsonpath='{.spec.nodeName}')
KUBERNETES_VERSION=$(kubectl version -o json \
  | jq -r '.serverVersion.gitVersion')
KIND_VERSION=$(kind version | awk '{print $2}')
HELM_VERSION=$(helm version --short)
COMPLETED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)

jq -n \
  --arg sourceSha "${SOURCE_SHA}" \
  --arg sourceTree "$(git -C "${ROOT_DIR}" rev-parse "${SOURCE_SHA}^{tree}")" \
  --arg image "${IMAGE_REFERENCE}" \
  --arg imageId "${IMAGE_ID}" \
  --arg podImageId "${POD_IMAGE_ID}" \
  --arg namespace "${NAMESPACE}" \
  --arg pod "${POD}" \
  --arg node "${NODE}" \
  --arg startedAt "${STARTED_AT}" \
  --arg completedAt "${COMPLETED_AT}" \
  --arg kubernetesVersion "${KUBERNETES_VERSION}" \
  --arg kindVersion "${KIND_VERSION}" \
  --arg helmVersion "${HELM_VERSION}" \
  --argjson readinessSeconds "${READINESS_SECONDS}" \
  --argjson restartCount "${RESTARTS}" \
  '{
    schemaVersion: 1,
    result: "passed",
    source: {commit: $sourceSha, tree: $sourceTree},
    image: {reference: $image, localImageId: $imageId, podImageId: $podImageId},
    cluster: {
      kind: $kindVersion,
      kubernetes: $kubernetesVersion,
      helm: $helmVersion,
      namespace: $namespace,
      node: $node
    },
    workload: {
      pod: $pod,
      readinessSeconds: $readinessSeconds,
      restartCount: $restartCount,
      resourceQuota: true,
      limitRange: true,
      restrictedEgress: true,
      minimalHttpWorkflow: true
    },
    startedAt: $startedAt,
    completedAt: $completedAt
  }' >"${EVIDENCE_DIR}/evidence.json"

printf 'Constrained Kubernetes smoke passed in %s seconds. Evidence: %s\n' \
  "${READINESS_SECONDS}" "${EVIDENCE_DIR}/evidence.json"
