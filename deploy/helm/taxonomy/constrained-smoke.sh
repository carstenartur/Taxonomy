#!/usr/bin/env bash
set -euo pipefail
# Preserve the failing command's line/status when a shell-only guard exits silently.
trap 'status=$?; printf "::error::Constrained smoke failed at line %s (exit %s)\\n" "$LINENO" "$status" >&2' ERR

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
CHART_DIR="${ROOT_DIR}/deploy/helm/taxonomy"
# The prerequisites manifest owns this fixed namespace together with its
# ResourceQuota and LimitRange. Keeping one authority prevents a caller from
# redirecting the workload into an unconstrained or non-existent namespace.
NAMESPACE=taxonomy-smoke
RELEASE=${TAXONOMY_SMOKE_RELEASE:-taxonomy}
KIND_CLUSTER_NAME=${KIND_CLUSTER_NAME:-taxonomy-smoke}
KUBE_CONTEXT="kind-${KIND_CLUSTER_NAME}"
SOURCE_SHA=${SOURCE_SHA:-$(git -C "${ROOT_DIR}" rev-parse HEAD)}
IMAGE_REPOSITORY=${IMAGE_REPOSITORY:-taxonomy-smoke}
IMAGE_TAG=${IMAGE_TAG:-sha-${SOURCE_SHA}}
IMAGE_REFERENCE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"
EVIDENCE_DIR=${EVIDENCE_DIR:-"${ROOT_DIR}/target/kubernetes-smoke"}
KEEP_RESOURCES=${KEEP_RESOURCES:-false}
SMOKE_SECRET=taxonomy-smoke-credentials
RESOURCE_SELECTOR="app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/name=taxonomy"
PORT_FORWARD_PID=""
NAMESPACE_UID=""
FIXTURE_KUBECONFIG=""

fail() {
  echo "::error::$*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${PORT_FORWARD_PID}" ]]; then
    kill "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
    wait "${PORT_FORWARD_PID}" >/dev/null 2>&1 || true
  fi
  if [[ "${KEEP_RESOURCES}" != "true" && -n "${NAMESPACE_UID}" ]]; then
    # The API checks the UID when deleting. A replacement namespace with the
    # same name is outside this run's ownership, including during cleanup.
    printf '{"apiVersion":"v1","kind":"DeleteOptions","preconditions":{"uid":"%s"}}\n' "${NAMESPACE_UID}" \
      | kubectl delete --request-timeout=10s --raw "/api/v1/namespaces/${NAMESPACE}" -f - >/dev/null 2>&1 || true
  fi
  if [[ -n "${FIXTURE_KUBECONFIG}" ]]; then
    rm -f -- "${FIXTURE_KUBECONFIG}"
  fi
}
trap cleanup EXIT

mkdir -p "${EVIDENCE_DIR}"
rm -f -- "${EVIDENCE_DIR}/evidence.json" "${EVIDENCE_DIR}/small-search/evidence.json"

for command in docker kind kubectl helm curl jq od; do
  command -v "${command}" >/dev/null 2>&1 \
    || fail "${command} is required for the constrained-cluster smoke test"
done
if ! [[ "${SOURCE_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  fail "SOURCE_SHA must be a full 40-character Git commit ID"
fi
if [[ "${IMAGE_TAG}" != "sha-${SOURCE_SHA}" ]]; then
  fail "IMAGE_TAG must identify SOURCE_SHA exactly"
fi
if [[ "${KEEP_RESOURCES}" != "true" && "${KEEP_RESOURCES}" != "false" ]]; then
  fail "KEEP_RESOURCES must be true or false"
fi

FIXTURE_KUBECONFIG=$(mktemp)
kind get kubeconfig --name "${KIND_CLUSTER_NAME}" >"${FIXTURE_KUBECONFIG}" \
  || fail "Cannot obtain kubeconfig for kind cluster ${KIND_CLUSTER_NAME}"
[[ -s "${FIXTURE_KUBECONFIG}" ]] || fail "kind returned an empty fixture kubeconfig"
kubectl() { command kubectl --kubeconfig "${FIXTURE_KUBECONFIG}" --context "${KUBE_CONTEXT}" "$@"; }
helm() { command helm --kubeconfig "${FIXTURE_KUBECONFIG}" --kube-context "${KUBE_CONTEXT}" "$@"; }
existing_namespace=$(kubectl get namespace "${NAMESPACE}" --ignore-not-found --request-timeout=10s -o name)
[[ -z "${existing_namespace}" ]] || fail "namespace ${NAMESPACE} already exists in ${KUBE_CONTEXT}"
NAMESPACE_UID=$(kubectl create namespace "${NAMESPACE}" --request-timeout=10s -o jsonpath='{.metadata.uid}')
if ! [[ "${NAMESPACE_UID}" =~ ^[A-Za-z0-9-]+$ ]]; then
  NAMESPACE_UID=""
  fail "Could not establish ownership of fixture namespace ${NAMESPACE}"
fi

STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
SMOKE_START_EPOCH=$(date +%s)
checkout_sha=$(git -C "${ROOT_DIR}" rev-parse HEAD)
[[ "${checkout_sha}" == "${SOURCE_SHA}" ]] || fail "SOURCE_SHA must equal the checked-out commit"
checkout_status=$(git -C "${ROOT_DIR}" status --porcelain=v1 --untracked-files=all)
[[ -z "${checkout_status}" ]] \
  || fail "A modified checkout cannot attest the source of the image and chart"
BUILD_DATE=$(git -C "${ROOT_DIR}" show -s --format=%cI "${SOURCE_SHA}")
VERSION=$("${ROOT_DIR}/mvnw" -q -f "${ROOT_DIR}/pom.xml" -DforceStdout help:evaluate \
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

kind load docker-image "${IMAGE_REFERENCE}" --name "${KIND_CLUSTER_NAME}"

kubectl apply -f "${CHART_DIR}/constrained-smoke-prerequisites.yaml"
kubectl wait --for=jsonpath='{.status.phase}'=Active \
  "namespace/${NAMESPACE}" --timeout=60s

# Create a fresh namespace-local fixture Secret for every run. No credential value
# is committed to Git or copied into the retained evidence archive.
SMOKE_ADMIN_PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
kubectl create secret generic "${SMOKE_SECRET}" \
  --namespace "${NAMESPACE}" \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:hsqldb:file:/app/data/taxonomy;shutdown=true' \
  --from-literal=SPRING_DATASOURCE_USERNAME=SA \
  --from-literal=SPRING_DATASOURCE_PASSWORD= \
  --from-literal="ADMIN_PASSWORD=${SMOKE_ADMIN_PASSWORD}" \
  --dry-run=client -o yaml \
  | kubectl apply -f -
unset SMOKE_ADMIN_PASSWORD
kubectl get secret "${SMOKE_SECRET}" --namespace "${NAMESPACE}" -o json \
  | jq -e '.data
      | has("SPRING_DATASOURCE_URL")
        and has("SPRING_DATASOURCE_USERNAME")
        and has("SPRING_DATASOURCE_PASSWORD")
        and has("ADMIN_PASSWORD")' >/dev/null

helm lint "${CHART_DIR}" \
  --values "${CHART_DIR}/values-constrained-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" \
  --set "image.tag=${IMAGE_TAG}"

RENDERED_MANIFEST="${EVIDENCE_DIR}/rendered.yaml"
helm template "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NAMESPACE}" \
  --values "${CHART_DIR}/values-constrained-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" \
  --set "image.tag=${IMAGE_TAG}" \
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
grep -Fq 'secretKeyRef:' "${RENDERED_MANIFEST}"
grep -Fq 'name: "taxonomy-smoke-credentials"' "${RENDERED_MANIFEST}"
grep -Fq 'name: TAXONOMY_ADMIN_PASSWORD' "${RENDERED_MANIFEST}"
grep -Fq 'key: "ADMIN_PASSWORD"' "${RENDERED_MANIFEST}"

DEPLOYMENT_START_EPOCH=$(date +%s)
helm upgrade --install "${RELEASE}" "${CHART_DIR}" \
  --namespace "${NAMESPACE}" \
  --values "${CHART_DIR}/values-constrained-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" \
  --set "image.tag=${IMAGE_TAG}" \
  --atomic \
  --wait \
  --timeout 12m

# Resource names are derived by Helm's fullname helper and need not equal the
# release name. Resolve exactly one workload and Service through stable labels
# rather than duplicating the chart's naming rules in this external smoke test.
mapfile -t DEPLOYMENTS < <(kubectl get deployment \
  --namespace "${NAMESPACE}" \
  --selector "${RESOURCE_SELECTOR}" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
[[ ${#DEPLOYMENTS[@]} -eq 1 ]] \
  || fail "Expected exactly one Taxonomy Deployment, found ${#DEPLOYMENTS[@]}"
DEPLOYMENT=${DEPLOYMENTS[0]}

mapfile -t SERVICES < <(kubectl get service \
  --namespace "${NAMESPACE}" \
  --selector "${RESOURCE_SELECTOR}" \
  -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
[[ ${#SERVICES[@]} -eq 1 ]] \
  || fail "Expected exactly one Taxonomy Service, found ${#SERVICES[@]}"
SERVICE=${SERVICES[0]}

kubectl rollout status "deployment/${DEPLOYMENT}" \
  --namespace "${NAMESPACE}" --timeout=2m
READY_EPOCH=$(date +%s)
READINESS_SECONDS=$((READY_EPOCH - DEPLOYMENT_START_EPOCH))
POD=$(kubectl get pod --namespace "${NAMESPACE}" \
  --selector "${RESOURCE_SELECTOR}" \
  -o jsonpath='{.items[0].metadata.name}')
[[ -n "${POD}" ]] || fail "Taxonomy pod was not created"

# Do not archive Secret resources or decoded values. The manifest contains only
# Secret references and the evidence records only that the fixture existed.
kubectl get resourcequota,limitrange,pod,service,networkpolicy \
  --namespace "${NAMESPACE}" -o yaml \
  >"${EVIDENCE_DIR}/cluster-resources.yaml"
kubectl describe pod "${POD}" --namespace "${NAMESPACE}" \
  >"${EVIDENCE_DIR}/pod-describe.txt"
kubectl logs "${POD}" --namespace "${NAMESPACE}" \
  >"${EVIDENCE_DIR}/application.log"
if grep -Fq 'Generated a one-time local administrator bootstrap password' \
    "${EVIDENCE_DIR}/application.log"; then
  fail "Helm Secret was not bound to taxonomy.admin-password"
fi
grep -Fq 'Created configured administrator account' \
  "${EVIDENCE_DIR}/application.log"

command kubectl --kubeconfig "${FIXTURE_KUBECONFIG}" --context "${KUBE_CONTEXT}" \
  port-forward --namespace "${NAMESPACE}" --address 127.0.0.1 \
  "service/${SERVICE}" :80 \
  >"${EVIDENCE_DIR}/port-forward.log" 2>&1 &
PORT_FORWARD_PID=$!
WEB_READY=false
for _ in $(seq 1 60); do
  kill -0 "${PORT_FORWARD_PID}" 2>/dev/null || fail "Fixture port-forward exited before readiness"
  WEB_PORT=$(sed -nE 's/^Forwarding from 127\.0\.0\.1:([0-9]+) -> [0-9]+$/\1/p' "${EVIDENCE_DIR}/port-forward.log")
  if [[ "${WEB_PORT}" =~ ^[0-9]{1,5}$ ]] && curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
      "http://127.0.0.1:${WEB_PORT}/actuator/health/readiness" \
      >"${EVIDENCE_DIR}/readiness.json"; then
    WEB_READY=true
    break
  fi
  sleep 2
done
[[ "${WEB_READY}" == true ]] || fail "Fixture service did not become ready through its port-forward"
kill -0 "${PORT_FORWARD_PID}" 2>/dev/null || fail "Fixture port-forward exited before the login request"
curl --fail --location --silent --show-error \
  --connect-timeout 2 --max-time 10 \
  --header 'Accept: text/html' \
  "http://127.0.0.1:${WEB_PORT}/" \
  >"${EVIDENCE_DIR}/home.html"
[[ -s "${EVIDENCE_DIR}/home.html" ]] \
  || fail "Taxonomy root redirect did not produce a login page"
grep -Fq 'class="login-form"' "${EVIDENCE_DIR}/home.html"
grep -Fq 'method="post" action="/login"' "${EVIDENCE_DIR}/home.html"
grep -Fq 'name="password"' "${EVIDENCE_DIR}/home.html"
grep -Fq 'name="_csrf"' "${EVIDENCE_DIR}/home.html"
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
COMPLETED_EPOCH=$(date +%s)
TOTAL_SMOKE_SECONDS=$((COMPLETED_EPOCH - SMOKE_START_EPOCH))

jq -n \
  --arg sourceSha "${SOURCE_SHA}" \
  --arg sourceTree "$(git -C "${ROOT_DIR}" rev-parse "${SOURCE_SHA}^{tree}")" \
  --arg image "${IMAGE_REFERENCE}" \
  --arg imageId "${IMAGE_ID}" \
  --arg podImageId "${POD_IMAGE_ID}" \
  --arg namespace "${NAMESPACE}" \
  --arg namespaceUid "${NAMESPACE_UID}" \
  --arg context "${KUBE_CONTEXT}" \
  --arg deployment "${DEPLOYMENT}" \
  --arg service "${SERVICE}" \
  --arg pod "${POD}" \
  --arg node "${NODE}" \
  --arg startedAt "${STARTED_AT}" \
  --arg completedAt "${COMPLETED_AT}" \
  --arg kubernetesVersion "${KUBERNETES_VERSION}" \
  --arg kindVersion "${KIND_VERSION}" \
  --arg helmVersion "${HELM_VERSION}" \
  --argjson readinessSeconds "${READINESS_SECONDS}" \
  --argjson totalSmokeSeconds "${TOTAL_SMOKE_SECONDS}" \
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
      context: $context,
      namespace: $namespace,
      namespaceUid: $namespaceUid,
      node: $node
    },
    workload: {
      deployment: $deployment,
      service: $service,
      pod: $pod,
      readinessSeconds: $readinessSeconds,
      restartCount: $restartCount,
      resourceQuota: true,
      limitRange: true,
      fixtureSecret: true,
      configuredAdminPassword: true,
      restrictedEgress: true,
      minimalHttpWorkflow: true
    },
    totalSmokeSeconds: $totalSmokeSeconds,
    startedAt: $startedAt,
    completedAt: $completedAt
  }' >"${EVIDENCE_DIR}/evidence.json"

printf 'Constrained Kubernetes smoke passed; deployment reached readiness in %s seconds (total smoke %s seconds). Evidence: %s\n' \
  "${READINESS_SECONDS}" "${TOTAL_SMOKE_SECONDS}" "${EVIDENCE_DIR}/evidence.json"


# Reuse the owned namespace and built image for the independently recorded small profile.
if [[ "${MEASURE_SMALL_SEARCH:-false}" == "true" ]]; then
  source "${CHART_DIR}/small-search-budget.sh"
  measure_small_search
fi
