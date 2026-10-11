#!/usr/bin/env bash
set -euo pipefail
umask 077

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
CHART_DIR="${ROOT_DIR}/deploy/helm/taxonomy"
NAMESPACE=taxonomy-artemis-smoke
RELEASE=taxonomy-artemis
SOURCE_SHA=${SOURCE_SHA:-$(git -C "${ROOT_DIR}" rev-parse HEAD)}
IMAGE_REPOSITORY=${IMAGE_REPOSITORY:-taxonomy-smoke}
IMAGE_TAG=${IMAGE_TAG:-sha-${SOURCE_SHA}}
IMAGE_REFERENCE="${IMAGE_REPOSITORY}:${IMAGE_TAG}"
KIND_CLUSTER_NAME=${KIND_CLUSTER_NAME:-taxonomy-smoke}
KUBE_CONTEXT="kind-${KIND_CLUSTER_NAME}"
EVIDENCE_DIR=${EVIDENCE_DIR:-"${ROOT_DIR}/target/kubernetes-smoke/artemis"}
KEEP_RESOURCES=${KEEP_RESOURCES:-false}
MODE=${1:-live}
PRIVATE_DIR=""
BACKGROUND_PIDS=()
LOCK_PID=""
PG_POD=""
NAMESPACE_CREATED=false
WEB_SELECTOR="app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/component=web"
WEB_FORWARD_PID=""
BROKER_FORWARD_PID=""
WORKER_SELECTOR="app.kubernetes.io/instance=${RELEASE},app.kubernetes.io/component=analysis-worker"

fail() { echo "::error::$*" >&2; exit 1; }
require_command() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required for Artemis constrained smoke"; }
await_until() {
  local deadline=$((SECONDS + $1)) description=$2
  shift 2
  until "$@"; do
    (( SECONDS < deadline )) || fail "Timed out: ${description}"
    sleep 2
  done
}
sql() {
  kubectl exec --namespace "${NAMESPACE}" "${PG_POD}" -- sh -c \
    'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -U taxonomy -d taxonomy -v ON_ERROR_STOP=1 -Atc "$1"' sh "$1"
}
lost_coordinator_disconnected() {
  local pod_disconnected=false broker_disconnected=false database_disconnected=false
  [[ "${LOSS_STAGE}" == before-dispatch || "${LOSS_STAGE}" == after-dispatch ]] || return 1
  # Each poll replaces all three stage-specific artifacts. Failure never retains old success.
  if kubectl get pods --namespace "${NAMESPACE}" --selector "${WEB_SELECTOR}" --request-timeout=10s -o json >"${PRIVATE_DIR}/coordinator-pods.json" &&
      jq -e --arg uid "${COORDINATOR_UID}" 'select(.items | type == "array")
        | select(all(.items[]; .metadata.uid | type == "string"))
        | {observed:true, observedAt:(now | todateiso8601), oldUidPresent:any(.items[]; .metadata.uid == $uid)}' \
        "${PRIVATE_DIR}/coordinator-pods.json" >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-pods.json"; then
    if jq -e '.oldUidPresent == false' "${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-pods.json" >/dev/null; then pod_disconnected=true; fi
  else
    jq -n '{observed:false, observedAt:(now | todateiso8601)}' >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-pods.json"
  fi
  if [[ "${LOSS_STAGE}" == before-dispatch ]]; then
    if broker_stopped; then
      jq -n '{observed:true, observedAt:(now | todateiso8601), brokerStopped:true}' >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-broker.json"
      broker_disconnected=true
    else
      jq -n '{observed:false, observedAt:(now | todateiso8601)}' >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-broker.json"
    fi
  elif broker_connections && node "${CHART_DIR}/verify-artemis-coordinator.mjs" "${COORDINATOR_IP}" <"${PRIVATE_DIR}/broker-connections.json" >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-broker.json"; then
    if jq -e '.connectionCount == 0' "${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-broker.json" >/dev/null; then broker_disconnected=true; fi
  else
    jq -n '{observed:false, observedAt:(now | todateiso8601)}' >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-broker.json"
  fi
  if sql "select json_build_object('sessionCount',count(*)) from pg_stat_activity where client_addr='${COORDINATOR_IP}'::inet" >"${PRIVATE_DIR}/coordinator-database.json" &&
      jq -e 'select(.sessionCount | type == "number" and . >= 0 and floor == .)
        | {observed:true, observedAt:(now | todateiso8601), sessionCount}' "${PRIVATE_DIR}/coordinator-database.json" >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-database.json"; then
    if jq -e '.sessionCount == 0' "${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-database.json" >/dev/null; then database_disconnected=true; fi
  else
    jq -n '{observed:false, observedAt:(now | todateiso8601)}' >"${EVIDENCE_DIR}/${LOSS_STAGE}-coordinator-database.json"
  fi
  [[ "${pod_disconnected}" == true && "${broker_disconnected}" == true && "${database_disconnected}" == true ]]
}
release_lock() {
  if [[ -n "${LOCK_PID}" ]]; then
    # Only the acceptance-owned connection is terminated; rollback releases its row lock.
    sql "select pg_terminate_backend(pid) from pg_stat_activity where application_name='taxonomy-artemis-smoke-lock'" >/dev/null || true
    wait "${LOCK_PID}" 2>/dev/null || true
    LOCK_PID=""
  fi
}
redact_diagnostics() {
  if [[ -n "${PRIVATE_DIR}" && -f "${PRIVATE_DIR}/redactions.sed" ]]; then
    sed -f "${PRIVATE_DIR}/redactions.sed"
  else
    cat
  fi
}
collect_diagnostics() {
  local output="${EVIDENCE_DIR}/diagnostics" pod
  local -a pods=()
  mkdir -p "${output}"
  # Do not export Secret resources. Redact generated credentials if a failed
  # client includes a connection URL or configuration value in its exception.
  kubectl get pod,deployment,replicaset,service,networkpolicy,resourcequota,limitrange \
    --namespace "${NAMESPACE}" --request-timeout=15s -o yaml 2>&1 \
    | redact_diagnostics >"${output}/resources.yaml" || true
  kubectl get events --namespace "${NAMESPACE}" --request-timeout=15s --sort-by=.lastTimestamp 2>&1 \
    | redact_diagnostics >"${output}/events.txt" || true
  mapfile -t pods < <(kubectl get pods --namespace "${NAMESPACE}" --request-timeout=15s \
    -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
  for pod in "${pods[@]}"; do
    [[ "${pod}" =~ ^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$ ]] || continue
    kubectl describe pod "${pod}" --namespace "${NAMESPACE}" --request-timeout=15s 2>&1 \
      | redact_diagnostics >"${output}/${pod}-describe.txt" || true
    kubectl logs "${pod}" --namespace "${NAMESPACE}" --request-timeout=15s \
      --all-containers=true --timestamps=true --tail=1000 2>&1 \
      | redact_diagnostics >"${output}/${pod}-current.log" || true
    kubectl logs "${pod}" --namespace "${NAMESPACE}" --request-timeout=15s \
      --all-containers=true --timestamps=true --tail=1000 --previous 2>&1 \
      | redact_diagnostics >"${output}/${pod}-previous.log" || true
  done
}
cleanup() {
  local status=$?
  if [[ "${status}" != 0 && "${NAMESPACE_CREATED}" == true ]]; then collect_diagnostics || true; fi
  release_lock
  for pid in "${BACKGROUND_PIDS[@]}"; do kill "${pid}" >/dev/null 2>&1 || true; wait "${pid}" 2>/dev/null || true; done
  if [[ -n "${PRIVATE_DIR}" ]]; then rm -rf -- "${PRIVATE_DIR}"; fi
  if [[ "${NAMESPACE_CREATED}" == true && "${KEEP_RESOURCES}" != true ]]; then
    helm uninstall "${RELEASE}" --namespace "${NAMESPACE}" >/dev/null 2>&1 || true
    kubectl delete namespace "${NAMESPACE}" --wait=false >/dev/null 2>&1 || true
  fi
  return "${status}"
}
trap cleanup EXIT

# A failed or render-only rerun must not leave a previous live success marker.
mkdir -p "${EVIDENCE_DIR}"
rm -f -- "${EVIDENCE_DIR}/evidence.json" \
  "${EVIDENCE_DIR}/worker-loss-broker.json" "${EVIDENCE_DIR}/worker-loss-database.json" \
  "${EVIDENCE_DIR}"/{before-dispatch,after-dispatch}-coordinator-{pods,broker,database}.json \
  "${EVIDENCE_DIR}"/coordinator-{before-dispatch,after-dispatch}.json
rm -rf -- "${EVIDENCE_DIR}/startup" "${EVIDENCE_DIR}/diagnostics"
[[ "${MODE}" == live || "${MODE}" == --render-only ]] || fail "Usage: $0 [--render-only]"
[[ "${SOURCE_SHA}" =~ ^[0-9a-f]{40}$ ]] || fail "SOURCE_SHA must be a full Git commit"
[[ "${IMAGE_TAG}" == "sha-${SOURCE_SHA}" ]] || fail "IMAGE_TAG must identify SOURCE_SHA exactly"
[[ "${KEEP_RESOURCES}" == true || "${KEEP_RESOURCES}" == false ]] || fail "KEEP_RESOURCES must be true or false"
require_command helm
helm lint "${CHART_DIR}" --values "${CHART_DIR}/values-artemis-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" --set "image.tag=${IMAGE_TAG}"
helm template "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" \
  --values "${CHART_DIR}/values-artemis-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" --set "image.tag=${IMAGE_TAG}" >"${EVIDENCE_DIR}/rendered.yaml"
cp "${CHART_DIR}/artemis-smoke-fixtures.yaml" "${EVIDENCE_DIR}/fixtures.yaml"
if [[ "${MODE}" == --render-only ]]; then
  echo "Rendered Artemis fixture only; no live cluster assertions executed."
  exit 0
fi
for command in docker kind kubectl curl jq node sha256sum openssl keytool od; do require_command "${command}"; done
# Never operate on an unrelated current kubeconfig context.
kubectl() { command kubectl --context "${KUBE_CONTEXT}" "$@"; }
helm() { command helm --kube-context "${KUBE_CONTEXT}" "$@"; }
existing_namespace=$(kubectl get namespace "${NAMESPACE}" --ignore-not-found -o name) \
  || fail "Cannot inspect fixture namespace in ${KUBE_CONTEXT}"
[[ -z "${existing_namespace}" ]] \
  || fail "Fixture namespace ${NAMESPACE} already exists in ${KUBE_CONTEXT}; inspect and remove it before rerunning"
docker image inspect "${IMAGE_REFERENCE}" >/dev/null \
  || fail "Build the exact image with constrained-smoke.sh before running this additive scenario"
[[ $(docker image inspect "${IMAGE_REFERENCE}" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}') == "${SOURCE_SHA}" ]] \
  || fail "Application image revision does not match SOURCE_SHA"
IMAGE_ID=$(docker image inspect "${IMAGE_REFERENCE}" --format '{{.Id}}')
[[ "${IMAGE_ID}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail "Missing immutable application image ID"
kind load docker-image "${IMAGE_REFERENCE}" --name "${KIND_CLUSTER_NAME}"
STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PRIVATE_DIR=$(mktemp -d)

kubectl create namespace "${NAMESPACE}"
NAMESPACE_CREATED=true
kubectl apply -f "${CHART_DIR}/artemis-smoke-fixtures.yaml"
random_secret() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
admin_password=$(random_secret)
admin_token=$(random_secret)
db_password=$(random_secret)
broker_password=$(random_secret)
tls_password=$(random_secret)
printf 's/%s/[REDACTED]/g\n' "${admin_password}" "${admin_token}" "${db_password}" \
  "${broker_password}" "${tls_password}" >"${PRIVATE_DIR}/redactions.sed"
openssl req -x509 -newkey rsa:2048 -nodes -days 2 -subj /CN=smoke-broker \
  -addext 'subjectAltName=DNS:smoke-broker,DNS:smoke-broker.taxonomy-artemis-smoke.svc' \
  -keyout "${PRIVATE_DIR}/broker.key" -out "${PRIVATE_DIR}/broker.crt" >/dev/null 2>&1
openssl pkcs12 -export -in "${PRIVATE_DIR}/broker.crt" -inkey "${PRIVATE_DIR}/broker.key" \
  -name smoke-broker -out "${PRIVATE_DIR}/broker.p12" -passout "pass:${tls_password}"
keytool -importcert -noprompt -alias smoke-broker -file "${PRIVATE_DIR}/broker.crt" \
  -keystore "${PRIVATE_DIR}/client.p12" -storetype PKCS12 -storepass "${tls_password}" >/dev/null 2>&1
# Reuse the schema-tested operator XML. Its single password substitution stays in a Secret.
sed 's/${artemis.keystore.password}/'"${tls_password}"'/g' "${ROOT_DIR}/deploy/artemis/broker.xml" >"${PRIVATE_DIR}/broker.xml"
printf 'taxonomy-admin=taxonomy-smoke\n' >"${PRIVATE_DIR}/artemis-roles.properties"
kubectl create secret generic taxonomy-artemis-broker-config --namespace "${NAMESPACE}" \
  --from-file="${PRIVATE_DIR}/broker.xml" --from-file="${PRIVATE_DIR}/artemis-roles.properties" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic taxonomy-artemis-broker-tls --namespace "${NAMESPACE}" \
  --from-file="${PRIVATE_DIR}/broker.p12" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic taxonomy-artemis-client-tls --namespace "${NAMESPACE}" \
  --from-file="${PRIVATE_DIR}/client.p12" --dry-run=client -o yaml | kubectl apply -f -
kubectl create secret generic taxonomy-artemis-smoke --namespace "${NAMESPACE}" \
  --from-literal=SPRING_DATASOURCE_URL='jdbc:postgresql://smoke-postgres:5432/taxonomy' \
  --from-literal=SPRING_DATASOURCE_USERNAME=taxonomy --from-literal="SPRING_DATASOURCE_PASSWORD=${db_password}" \
  --from-literal="ADMIN_PASSWORD=${admin_password}" --from-literal="ADMIN_TOKEN=${admin_token}" \
  --from-literal=ARTEMIS_USER=taxonomy-smoke --from-literal="ARTEMIS_PASSWORD=${broker_password}" \
  --from-literal="ARTEMIS_BROKER_URL=tcp://smoke-broker:61617?sslEnabled=true&verifyHost=true&trustStoreType=PKCS12&trustStorePath=/var/run/taxonomy-artemis/client.p12&trustStorePassword=${tls_password}" \
  --dry-run=client -o yaml | kubectl apply -f -
printf 'user = "admin:%s"\n' "${admin_password}" >"${PRIVATE_DIR}/web.curl"
printf 'user = "taxonomy-smoke:%s"\n' "${broker_password}" >"${PRIVATE_DIR}/broker.curl"
unset admin_password admin_token db_password broker_password tls_password

kubectl rollout status deployment/smoke-postgres --namespace "${NAMESPACE}" --timeout=5m
kubectl rollout status deployment/smoke-broker --namespace "${NAMESPACE}" --timeout=5m
PG_POD=$(kubectl get pods --namespace "${NAMESPACE}" --selector app.kubernetes.io/name=taxonomy-smoke-postgres -o jsonpath='{.items[0].metadata.name}')
start_broker_forward() {
  if [[ -n "${BROKER_FORWARD_PID}" ]]; then kill "${BROKER_FORWARD_PID}" 2>/dev/null || true; wait "${BROKER_FORWARD_PID}" 2>/dev/null || true; fi
  kubectl port-forward --namespace "${NAMESPACE}" service/smoke-broker 18161:8161 >>"${EVIDENCE_DIR}/broker-forward.log" 2>&1 &
  BROKER_FORWARD_PID=$!; BACKGROUND_PIDS+=("${BROKER_FORWARD_PID}")
}
start_broker_forward
BROKER_MBEAN='org.apache.activemq.artemis:broker="taxonomy-analysis",component=addresses,address="taxonomy.analysis.subtaxonomy.CP",subcomponent=queues,routing-type="anycast",queue="taxonomy.analysis.subtaxonomy.CP"'
jq -n --arg bean "${BROKER_MBEAN}" '{type:"read",mbean:$bean,attribute:["DeliveringCount","MessageCount","ConsumerCount","MessagesAcknowledged","MessagesAdded"]}' >"${PRIVATE_DIR}/broker-request.json"
broker_stats() {
  curl --fail --silent --show-error --connect-timeout 3 --max-time 10 --config "${PRIVATE_DIR}/broker.curl" \
    -H 'Content-Type: application/json' --data-binary "@${PRIVATE_DIR}/broker-request.json" \
    http://127.0.0.1:18161/console/jolokia/ >"${PRIVATE_DIR}/broker-stats.json" && \
    jq -e '.status == 200 and (.value.DeliveringCount | type == "number")' "${PRIVATE_DIR}/broker-stats.json" >/dev/null
}
jq -n '{type:"exec",mbean:"org.apache.activemq.artemis:broker=\"taxonomy-analysis\"",operation:"listConnectionsAsJSON()",arguments:[]}' >"${PRIVATE_DIR}/broker-connections-request.json"
broker_connections() {
  curl --fail --silent --show-error --connect-timeout 3 --max-time 10 --max-filesize 65536 --config "${PRIVATE_DIR}/broker.curl" \
    -H 'Content-Type: application/json' --data-binary "@${PRIVATE_DIR}/broker-connections-request.json" \
    http://127.0.0.1:18161/console/jolokia/ >"${PRIVATE_DIR}/broker-connections.json"
}
broker_stopped() {
  kubectl get pods --namespace "${NAMESPACE}" --selector app.kubernetes.io/name=taxonomy-smoke-broker --request-timeout=10s -o json >"${PRIVATE_DIR}/broker-pods.json" &&
    jq -e '.items | type == "array" and length == 0' "${PRIVATE_DIR}/broker-pods.json" >/dev/null
}
await_until 90 "authenticated broker management" broker_stats
# Keep failed pods until the EXIT handler captures their logs; cleanup owns removal.
helm upgrade --install "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" \
  --values "${CHART_DIR}/values-artemis-smoke.yaml" \
  --set "image.repository=${IMAGE_REPOSITORY}" --set "image.tag=${IMAGE_TAG}" --wait --timeout 12m
start_web_forward() {
  if [[ -n "${WEB_FORWARD_PID}" ]]; then kill "${WEB_FORWARD_PID}" 2>/dev/null || true; wait "${WEB_FORWARD_PID}" 2>/dev/null || true; fi
  local pod
  pod=$(kubectl get pods --namespace "${NAMESPACE}" --selector "${WEB_SELECTOR}" -o jsonpath='{.items[0].metadata.name}')
  [[ "${pod}" =~ ^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$ ]] || fail "Replacement coordinator pod unavailable"
  kubectl port-forward --namespace "${NAMESPACE}" "pod/${pod}" 18081:8080 >>"${EVIDENCE_DIR}/web-forward.log" 2>&1 &
  WEB_FORWARD_PID=$!; BACKGROUND_PIDS+=("${WEB_FORWARD_PID}")
}
start_web_forward
web_ready() { curl --fail --silent --connect-timeout 2 --max-time 5 http://127.0.0.1:18081/actuator/health/readiness >"${EVIDENCE_DIR}/web-readiness.json" && jq -e ' .status == "UP" ' "${EVIDENCE_DIR}/web-readiness.json" >/dev/null; }
await_until 90 "coordinator readiness" web_ready
jq -e '.status == "UP"' "${EVIDENCE_DIR}/web-readiness.json" >/dev/null

# Record actual startup measurements before admission. Heap-backed indexes have no disk-size claim.
mkdir -p "${EVIDENCE_DIR}/startup"
mapfile -t STARTUP_PODS < <(kubectl get pod --namespace "${NAMESPACE}" --selector "app.kubernetes.io/instance=${RELEASE}" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
for pod in "${STARTUP_PODS[@]}"; do
  kubectl get pod "${pod}" --namespace "${NAMESPACE}" -o json | jq '{name:.metadata.name,uid:.metadata.uid,labels:.metadata.labels,resources:.spec.containers[0].resources,imageID:.status.containerStatuses[0].imageID,configuration:[.spec.containers[0].env[] | select(.name | test("RUNTIME_ROLE|WORKER_SHARDS|SEARCH_DIRECTORY_TYPE|LLM_MOCK"))]}' >"${EVIDENCE_DIR}/startup/${pod}-config.json"
  kubectl exec --namespace "${NAMESPACE}" "${pod}" -- sh -c 'curl -fsS -H "Authorization: Bearer $ADMIN_PASSWORD" "http://127.0.0.1:8080/actuator/metrics/jvm.memory.used?tag=area:heap"' >"${EVIDENCE_DIR}/startup/${pod}-heap.json"
  jq -e '.name == "jvm.memory.used" and (.measurements | length > 0) and (.measurements[0].value >= 0)' "${EVIDENCE_DIR}/startup/${pod}-heap.json" >/dev/null
  kubectl exec --namespace "${NAMESPACE}" "${pod}" -- sh -c 'cat /proc/1/status; if [ -d /app/data/lucene-index ]; then du -sk /app/data/lucene-index; else echo "No disk index: configured local-heap"; fi' >"${EVIDENCE_DIR}/startup/${pod}-process.txt"
  for target in smoke-postgres:5432 smoke-broker:61617; do
    kubectl exec --namespace "${NAMESPACE}" "${pod}" -- bash -c 'timeout 5 bash -c "exec 3<>/dev/tcp/${1%:*}/${1##*:}"' sh "${target}" \
      || fail "Required egress ${target} was blocked for ${pod}"
  done
  # Same known-live broker, different port: fixture ingress is open, so only application egress blocks this.
  if kubectl exec --namespace "${NAMESPACE}" "${pod}" -- bash -c 'timeout 5 bash -c "exec 3<>/dev/tcp/smoke-broker/8161"'; then
    fail "Forbidden management egress succeeded for ${pod}; NetworkPolicy is not enforced"
  else
    probe_exit=$?
    [[ "${probe_exit}" == 124 ]] || fail "Management probe failed with ${probe_exit}, not a policy timeout, for ${pod}"
  fi
  broker_stats || fail "Management listener was unavailable during the denied-egress check"
done

# Workers begin at zero for admission; no timing race against the fast MOCK provider.
kubectl scale deployment --namespace "${NAMESPACE}" --selector "${WORKER_SELECTOR}" --replicas=0
workers_absent() { [[ $(kubectl get pod --namespace "${NAMESPACE}" --selector "${WORKER_SELECTOR}" -o json | jq '.items | length') == 0 ]]; }
await_until 120 "workers stopped before admission" workers_absent
no_cp_consumers() { broker_stats && jq -e '.value.ConsumerCount == 0 and .value.DeliveringCount == 0' "${PRIVATE_DIR}/broker-stats.json" >/dev/null; }
await_until 90 "old CP consumers disconnected before admission" no_cp_consumers
curl --fail --silent --show-error --config "${PRIVATE_DIR}/web.curl" -H 'Content-Type: application/json' \
  --data '{"displayName":"Artemis constrained acceptance"}' http://127.0.0.1:18081/api/workspace/create >"${PRIVATE_DIR}/workspace.json"
WORKSPACE=$(jq -er '.workspaceId' "${PRIVATE_DIR}/workspace.json")
[[ "${WORKSPACE}" =~ ^[A-Za-z0-9_-]+$ ]] || fail "Unexpected workspace identifier"
curl --fail --silent --show-error --config "${PRIVATE_DIR}/web.curl" -H 'Content-Type: application/json' --data '{}' \
  "http://127.0.0.1:18081/api/workspace/provision?workspaceId=${WORKSPACE}" >"${PRIVATE_DIR}/provision.json"
jq -e '.status == "READY"' "${PRIVATE_DIR}/provision.json" >/dev/null
# The fixture broker uses emptyDir. It is stopped only before the first task/permit
# debit exists. This is a dispatch-unavailable case, not broker durability/HA evidence.
[[ $(sql "select count(*) from analysis_dispatch_intent") == 0 ]] || fail "Broker stop would destroy an existing dispatch authority"
broker_stats && jq -e '.value.MessageCount == 0 and .value.DeliveringCount == 0 and .value.MessagesAdded == 0' "${PRIVATE_DIR}/broker-stats.json" >/dev/null
kubectl scale deployment/smoke-broker --namespace "${NAMESPACE}" --replicas=0
await_until 120 "disposable broker stopped before any dispatch" broker_stopped
curl --fail --silent --show-error --max-time 1800 --config "${PRIVATE_DIR}/web.curl" -H 'Content-Type: application/json' \
  --data '{"businessText":"Reliable command communication for constrained Artemis acceptance.","provider":"MOCK","includeArchitectureView":false,"analysisScope":{"taxonomyRoots":["CP","IP"],"mode":"TAXONOMIES_ONLY"}}' \
  "http://127.0.0.1:18081/api/analyze?workspaceId=${WORKSPACE}" >"${PRIVATE_DIR}/admission-response.json" 2>"${EVIDENCE_DIR}/analysis-http.log" &
REQUEST_PID=$!; BACKGROUND_PIDS+=("${REQUEST_PID}")
admitted() { [[ $(sql "select count(*) from analysis_cluster_run where username='admin'") == 1 ]]; }
await_until 120 "durable two-root admission with workers absent" admitted
OPERATION=$(sql "select id from analysis_cluster_run where username='admin'")
[[ "${OPERATION}" =~ ^[A-Za-z0-9_-]+$ ]] || fail "Unexpected operation identifier"
[[ $(sql "select count(*) from analysis_cluster_work where operation_id='${OPERATION}' and state='QUEUED'") == 2 ]] || fail "Admission did not queue both roots"

# Both real after-commit intents must be durable and recoverable while transport is down.
waiting_dispatch() {
  [[ $(sql "select count(*) from analysis_dispatch_intent where operation_id='${OPERATION}' and status in ('DISPATCH_PENDING','WAITING_FOR_BROKER')") == 2 ]] &&
    [[ $(sql "select count(*) from analysis_dispatch_intent where operation_id='${OPERATION}' and status='WAITING_FOR_BROKER'") -ge 1 ]]
}
await_until 120 "real broker-unavailable durable intents" waiting_dispatch
frozen_identity() {
  # Hash immutable admitted data privately. Public evidence retains only the aggregate
  # digest, operation ID and revision, never source text, task envelopes or catalogue data.
  sql "select json_build_object(
    'run',(select json_build_array(id,username,scope_key,md5(context_json),md5(command_json),md5(view_json),state,total_roots,completed_roots,event_revision) from analysis_cluster_run where id='${OPERATION}'),
    'work',(select json_agg(json_build_array(id,task_id,task_type,root_code,ordinal_number,md5(message_json),state,settled,delivery_attempts) order by ordinal_number) from analysis_cluster_work where operation_id='${OPERATION}'),
    'inputs',(select json_agg(json_build_array(id,root_code,md5(input_json)) order by root_code) from analysis_cluster_input where operation_id='${OPERATION}'),
    'intents',(select json_agg(json_build_array(id,task_id,task_type,routing_root,md5(message_json)) order by task_id) from analysis_dispatch_intent where operation_id='${OPERATION}'),
    'events',(select json_agg(json_build_array(id,event_revision,md5(event_json)) order by event_revision) from analysis_cluster_event where operation_id='${OPERATION}'))" | jq -eSc '
      select(.run | length == 10) | select(.work | length == 2) | select(.inputs | length == 2)
      | select(.intents | length == 2) | select(.events | length > 0)'
}
frozen_identity >"${PRIVATE_DIR}/admitted-identity.json"
ADMITTED_DIGEST=$(sha256sum "${PRIVATE_DIR}/admitted-identity.json" | cut -d ' ' -f 1)
ADMITTED_REVISION=$(sql "select event_revision from analysis_cluster_run where id='${OPERATION}'")
identity_unchanged() {
  frozen_identity >"${PRIVATE_DIR}/current-identity.json" && cmp -s "${PRIVATE_DIR}/admitted-identity.json" "${PRIVATE_DIR}/current-identity.json"
}
replace_coordinator() {
  local pod replacement_uid
  pod=$(kubectl get pod --namespace "${NAMESPACE}" --selector "${WEB_SELECTOR}" -o jsonpath='{.items[0].metadata.name}')
  COORDINATOR_UID=$(kubectl get pod "${pod}" --namespace "${NAMESPACE}" -o jsonpath='{.metadata.uid}')
  COORDINATOR_IP=$(kubectl get pod "${pod}" --namespace "${NAMESPACE}" -o jsonpath='{.status.podIP}')
  [[ "${COORDINATOR_UID}" =~ ^[a-f0-9-]{36}$ && "${COORDINATOR_IP}" =~ ^[a-fA-F0-9:.]+$ ]] || fail "Coordinator identity unavailable"
  # Prove connections belonged to this actual coordinator before deleting it.
  [[ $(sql "select count(*) from pg_stat_activity where client_addr='${COORDINATOR_IP}'::inet") -gt 0 ]] || fail "Old coordinator database connection was not observed"
  if [[ "${LOSS_STAGE}" == after-dispatch ]]; then
    broker_connections && node "${CHART_DIR}/verify-artemis-coordinator.mjs" "${COORDINATOR_IP}" <"${PRIVATE_DIR}/broker-connections.json" >"${PRIVATE_DIR}/before-loss-connections.json"
    jq -e '.connectionCount > 0' "${PRIVATE_DIR}/before-loss-connections.json" >/dev/null || fail "Old coordinator broker connection was not observed"
  fi
  kubectl scale deployment/taxonomy-artemis --namespace "${NAMESPACE}" --replicas=0
  kubectl delete pod "${pod}" --namespace "${NAMESPACE}" --grace-period=0 --force --wait=true --ignore-not-found=true
  if [[ -n "${WEB_FORWARD_PID}" ]]; then kill "${WEB_FORWARD_PID}" 2>/dev/null || true; wait "${WEB_FORWARD_PID}" 2>/dev/null || true; WEB_FORWARD_PID=""; fi
  await_until 120 "${LOSS_STAGE} coordinator lost UID and all broker/database connections" lost_coordinator_disconnected
  identity_unchanged || fail "Coordinator loss changed immutable admission, queued work or progress revision"
  kubectl scale deployment/taxonomy-artemis --namespace "${NAMESPACE}" --replicas=1
  kubectl rollout status deployment/taxonomy-artemis --namespace "${NAMESPACE}" --timeout=10m
  replacement_uid=$(kubectl get pod --namespace "${NAMESPACE}" --selector "${WEB_SELECTOR}" -o jsonpath='{.items[0].metadata.uid}')
  [[ "${replacement_uid}" =~ ^[a-f0-9-]{36}$ && "${replacement_uid}" != "${COORDINATOR_UID}" ]] || fail "Coordinator was not replaced"
  start_web_forward
  await_until 90 "${LOSS_STAGE} replacement coordinator readiness" web_ready
  curl --fail --silent --show-error --max-time 15 --config "${PRIVATE_DIR}/web.curl" \
    "http://127.0.0.1:18081/api/analysis-runs/${OPERATION}/request?workspaceId=${WORKSPACE}" >"${PRIVATE_DIR}/recovered-request.json"
  jq -e --arg op "${OPERATION}" '.operationId == $op and .provider == "MOCK" and .businessText == "Reliable command communication for constrained Artemis acceptance." and .analysisScope.taxonomyRoots == ["CP","IP"] and .analysisScope.mode == "TAXONOMIES_ONLY"' "${PRIVATE_DIR}/recovered-request.json" >/dev/null
  identity_unchanged || fail "Replacement coordinator changed original frozen admission"
  [[ $(sql "select count(*) from analysis_cluster_run where username='admin'") == 1 ]] || fail "Replacement coordinator admitted another operation"
  jq -n --arg operation "${OPERATION}" --arg stage "${LOSS_STAGE}" --arg lostUid "${COORDINATOR_UID}" --arg replacementUid "${replacement_uid}" \
    --arg digest "${ADMITTED_DIGEST}" --argjson revision "${ADMITTED_REVISION}" \
    '{observed:true,observedAt:(now | todateiso8601),stage:$stage,operationId:$operation,lostUid:$lostUid,replacementUid:$replacementUid,admittedIdentityDigest:$digest,unchangedRevision:$revision}' \
    >"${EVIDENCE_DIR}/coordinator-${LOSS_STAGE}.json"
}
LOSS_STAGE=before-dispatch
replace_coordinator
# The disconnected POST is expected to fail. Recovery reads the same scoped durable
# operation; it must never submit a second analyze request.
request_finished() { ! kill -0 "${REQUEST_PID}" 2>/dev/null; }
await_until 90 "original POST detached with old coordinator" request_finished
if wait "${REQUEST_PID}"; then fail "Original coordinator POST unexpectedly completed with all workers absent"; fi
waiting_dispatch || fail "Pending intents disappeared with the first coordinator"
kubectl scale deployment/smoke-broker --namespace "${NAMESPACE}" --replicas=1
kubectl rollout status deployment/smoke-broker --namespace "${NAMESPACE}" --timeout=5m
start_broker_forward
await_until 90 "fresh broker configured queue readiness" broker_stats
no_cp_consumers || fail "Replacement broker unexpectedly acquired CP consumers"
dispatched() { [[ $(sql "select count(*) from analysis_dispatch_intent where operation_id='${OPERATION}' and status='DISPATCHED'") == 2 ]]; }
await_until 120 "connection-event dispatch of both retained intents" dispatched
# Both roots must be queued once on the fresh broker, before any worker starts.
queued_once() { broker_stats && jq -e '.value.MessageCount == 1 and .value.MessagesAdded == 1 and .value.ConsumerCount == 0 and .value.DeliveringCount == 0' "${PRIVATE_DIR}/broker-stats.json" >/dev/null; }
await_until 90 "CP queued once with workers absent" queued_once
ip_queued_once() {
  jq --arg bean "${BROKER_MBEAN//.CP/.IP}" '.mbean=$bean' "${PRIVATE_DIR}/broker-request.json" >"${PRIVATE_DIR}/ip-request.json"
  curl --fail --silent --show-error --max-time 10 --config "${PRIVATE_DIR}/broker.curl" -H 'Content-Type: application/json' --data-binary "@${PRIVATE_DIR}/ip-request.json" http://127.0.0.1:18161/console/jolokia/ >"${PRIVATE_DIR}/ip-stats.json"
  jq -e '.status == 200 and .value.MessageCount == 1 and .value.MessagesAdded == 1 and .value.ConsumerCount == 0 and .value.DeliveringCount == 0' "${PRIVATE_DIR}/ip-stats.json" >/dev/null
}
await_until 90 "IP queued once with workers absent" ip_queued_once
LOSS_STAGE=after-dispatch
replace_coordinator
# Retain the broker unchanged in this second failure. Startup recovery must not republish DISPATCHED work.
dispatched && queued_once && ip_queued_once && identity_unchanged || fail "Second coordinator restart duplicated or changed dispatched work"
[[ $(sql "select count(*) from analysis_task_completion where operation_id='${OPERATION}'") == 0 ]] || fail "Coordinator restart fabricated a completion"

# Hold the operation row on a test-owned PostgreSQL connection. A delivered worker blocks
# before computation; force its loss only after Artemis reports an unacknowledged delivery.
kubectl exec --namespace "${NAMESPACE}" "${PG_POD}" -- env PGAPPNAME=taxonomy-artemis-smoke-lock sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD" exec psql -X -U taxonomy -d taxonomy -v ON_ERROR_STOP=1 -Atc "$1"' sh \
  "BEGIN; SELECT id FROM analysis_cluster_run WHERE id='${OPERATION}' FOR UPDATE; SELECT pg_sleep(1200); COMMIT;" >"${PRIVATE_DIR}/row-lock.log" 2>&1 &
LOCK_PID=$!
locked() { [[ $(sql "select count(*) from pg_stat_activity where application_name='taxonomy-artemis-smoke-lock' and wait_event='PgSleep'") == 1 ]]; }
await_until 30 "test-owned operation row lock" locked
kubectl scale deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --replicas=1
delivering() { broker_stats && jq -e '.value.DeliveringCount == 1' "${PRIVATE_DIR}/broker-stats.json" >/dev/null; }
await_until 600 "one unacknowledged CP delivery behind the row lock" delivering
cp "${PRIVATE_DIR}/broker-stats.json" "${EVIDENCE_DIR}/broker-before-worker-loss.json"
LOST_POD=$(kubectl get pod --namespace "${NAMESPACE}" --selector "${WORKER_SELECTOR},taxonomy.io/worker-set=cp" -o jsonpath='{.items[0].metadata.name}')
LOST_UID=$(kubectl get pod "${LOST_POD}" --namespace "${NAMESPACE}" -o jsonpath='{.metadata.uid}')
LOST_IP=$(kubectl get pod "${LOST_POD}" --namespace "${NAMESPACE}" -o jsonpath='{.status.podIP}')
[[ "${LOST_IP}" =~ ^[a-fA-F0-9:.]+$ ]] || fail "Lost worker has no valid pod IP"
kubectl scale deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --replicas=0
kubectl delete pod "${LOST_POD}" --namespace "${NAMESPACE}" --grace-period=0 --force --wait=true --ignore-not-found=true
# API deletion alone does not prove process death. Keep the row lock until both
# broker consumption and every database connection from the lost pod have gone.
lost_worker_disconnected() {
  local broker_disconnected=false database_disconnected=false
  # Sample both resources independently. Keep only the latest bounded counters,
  # without SQL text, credentials, or arbitrary broker response/error fields.
  if broker_stats && jq -e '
      .value | {ConsumerCount, DeliveringCount, MessageCount, MessagesAcknowledged, MessagesAdded}
      | select(all(.[]; type == "number" and . >= 0))
      | {observed: true, observedAt: (now | todateiso8601), value: .}
      ' "${PRIVATE_DIR}/broker-stats.json" >"${EVIDENCE_DIR}/worker-loss-broker.json"; then
    if jq -e '.value.ConsumerCount == 0 and .value.DeliveringCount == 0' \
        "${EVIDENCE_DIR}/worker-loss-broker.json" >/dev/null; then broker_disconnected=true; fi
  else
    jq -n '{observed: false, observedAt: (now | todateiso8601)}' >"${EVIDENCE_DIR}/worker-loss-broker.json"
  fi
  if sql "select json_build_object(
      'sessionCount', count(*),
      'lockWaitCount', count(*) filter (where wait_event_type='Lock'),
      'activeCount', count(*) filter (where state='active'),
      'clientConnectionCheckInterval', current_setting('client_connection_check_interval'))
      from pg_stat_activity where client_addr='${LOST_IP}'::inet" >"${PRIVATE_DIR}/worker-loss-database.json" &&
      jq -e 'select([.sessionCount, .lockWaitCount, .activeCount] | all(.[]; type == "number" and . >= 0))
        | {observed: true, observedAt: (now | todateiso8601), sessionCount, lockWaitCount,
           activeCount, clientConnectionCheckInterval}
        ' "${PRIVATE_DIR}/worker-loss-database.json" >"${EVIDENCE_DIR}/worker-loss-database.json"; then
    if jq -e '.sessionCount == 0' "${EVIDENCE_DIR}/worker-loss-database.json" >/dev/null; then
      database_disconnected=true
    fi
  else
    jq -n '{observed: false, observedAt: (now | todateiso8601)}' >"${EVIDENCE_DIR}/worker-loss-database.json"
  fi
  [[ "${broker_disconnected}" == true && "${database_disconnected}" == true ]]
}
await_until 120 "lost CP worker disconnected from broker and database" lost_worker_disconnected
locked || fail "Acceptance row lock expired before the lost worker disconnected"
[[ $(sql "select count(*) from analysis_cluster_work where operation_id='${OPERATION}' and root_code='CP' and state='QUEUED' and result_json is null and delivery_attempts=0") == 1 ]] \
  || fail "Lost CP worker committed a start or result before lock release"
[[ $(sql "select count(*) from analysis_task_completion where operation_id='${OPERATION}'") == 0 ]] \
  || fail "Completion effect appeared before replacement workers started"
cp "${PRIVATE_DIR}/broker-stats.json" "${EVIDENCE_DIR}/broker-disconnected-worker.json"
release_lock
kubectl scale deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --replicas=2
kubectl scale deployment/taxonomy-artemis-worker-ip --namespace "${NAMESPACE}" --replicas=1
kubectl rollout status deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --timeout=10m
kubectl rollout status deployment/taxonomy-artemis-worker-ip --namespace "${NAMESPACE}" --timeout=10m
durable_completed() { [[ $(sql "select state||':'||completed_roots||':'||total_roots from analysis_cluster_run where id='${OPERATION}'") == COMPLETED:2:2 ]]; }
await_until 900 "same durable operation completed after both coordinator losses and worker redelivery" durable_completed
curl --fail --silent --show-error --max-time 30 --config "${PRIVATE_DIR}/web.curl" \
  "http://127.0.0.1:18081/api/analysis-runs/${OPERATION}/result?workspaceId=${WORKSPACE}" >"${EVIDENCE_DIR}/result.json"
jq -e '.status == "SUCCESS" and .provider == "Mock" and (.rawScores | length > 0)' "${EVIDENCE_DIR}/result.json" >/dev/null
[[ $(sql "select state||':'||completed_roots||':'||total_roots from analysis_cluster_run where id='${OPERATION}'") == COMPLETED:2:2 ]] || fail "Durable roots did not complete"
[[ $(sql "select count(*) from analysis_cluster_work where operation_id='${OPERATION}' and settled=true and state='COMPLETED' and root_code in ('CP','IP')") == 2 ]] || fail "Expected two settled root effects"
[[ $(sql "select count(*) from analysis_task_completion where operation_id='${OPERATION}'") == 2 ]] || fail "Completion ledger did not retain exactly one effect per root"
[[ $(sql "select count(*) from analysis_cluster_work where operation_id='${OPERATION}' and root_code='CP' and delivery_attempts>=2") == 1 ]] \
  || fail "Replacement CP work did not record an actual broker redelivery"
[[ $(sql "select count(*) from analysis_cluster_input where operation_id='${OPERATION}'") == 2 ]] || fail "Frozen selected-root inputs were not retained"
[[ $(sql "select count(*)=max(event_revision) and count(*)=count(distinct event_revision) from analysis_cluster_event where operation_id='${OPERATION}'") == t ]] || fail "Progress revisions are not contiguous and unique"
[[ $(kubectl get deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" -o jsonpath='{.status.readyReplicas}') == 2 ]] || fail "CP did not scale independently"
[[ $(kubectl get deployment/taxonomy-artemis-worker-ip --namespace "${NAMESPACE}" -o jsonpath='{.status.readyReplicas}') == 1 ]] || fail "IP replica count changed"
cp_acknowledged_once() {
  broker_stats && jq -e '.value.DeliveringCount == 0 and .value.MessageCount == 0 and .value.MessagesAdded == 1 and .value.MessagesAcknowledged == 1' "${PRIVATE_DIR}/broker-stats.json" >/dev/null
}
await_until 90 "one CP message acknowledged after redelivery" cp_acknowledged_once
cp "${PRIVATE_DIR}/broker-stats.json" "${EVIDENCE_DIR}/broker-after-worker-loss.json"
curl --fail --silent --show-error --config "${PRIVATE_DIR}/web.curl" \
  "http://127.0.0.1:18081/api/analysis-runs/${OPERATION}?workspaceId=${WORKSPACE}" >"${EVIDENCE_DIR}/observation.json"
jq -e '.transport == "artemis" and .status == "COMPLETED" and .cluster.completedRoots == 2' "${EVIDENCE_DIR}/observation.json" >/dev/null
sql "select root_code,state,settled,delivery_attempts from analysis_cluster_work where operation_id='${OPERATION}' order by ordinal_number" >"${EVIDENCE_DIR}/durable-work.txt"
kubectl get pod,deployment,service,networkpolicy,resourcequota,limitrange --namespace "${NAMESPACE}" -o json >"${EVIDENCE_DIR}/cluster-resources.json"
REPLACEMENT_UIDS=$(kubectl get pod --namespace "${NAMESPACE}" --selector "${WORKER_SELECTOR},taxonomy.io/worker-set=cp" -o json | jq '[.items[].metadata.uid]')
jq -e --arg lost "${LOST_UID}" 'index($lost) == null and length == 2' <<<"${REPLACEMENT_UIDS}" >/dev/null
jq -n --arg source "${SOURCE_SHA}" --arg tree "$(git -C "${ROOT_DIR}" rev-parse "${SOURCE_SHA}^{tree}")" \
  --arg image "${IMAGE_REFERENCE}" --arg imageId "${IMAGE_ID}" --arg started "${STARTED_AT}" --arg completed "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --arg operation "${OPERATION}" --arg lostPod "${LOST_POD}" --arg lostUid "${LOST_UID}" --argjson replacementUids "${REPLACEMENT_UIDS}" \
  '{schemaVersion:1,result:"passed",source:{commit:$source,tree:$tree},image:{reference:$image,localImageId:$imageId},startedAt:$started,completedAt:$completed,
    scenario:"coordinator loss before and after dispatch, then delivered worker loss before provider computation",provider:"MOCK",operationId:$operation,
    topology:{coordinator:1,cpWorkers:2,ipWorkers:1,sharedDatabase:"PostgreSQL",externalBroker:"Artemis TLS"},
    assertions:{quota:true,brokerTls:true,allowedDatabaseAndBrokerEgress:true,deniedBrokerManagementEgress:true,coordinatorLossBeforeDispatch:true,coordinatorLossAfterDispatch:true,unchangedFrozenAdmissionAndRevision:true,scopedResultRecovery:true,workerLossRedelivery:true,oneCommittedEffectPerRoot:true,contiguousProgress:true,independentWorkerScaling:true},
    loss:{pod:$lostPod,uid:$lostUid,replacementUids:$replacementUids},startupEvidence:"startup/ (heap/RSS/config; local-heap indexes, fixture only)",
    limits:["No broker durability/HA or DB pod replacement claim; disposable broker replaced only before first dispatch","No external provider or throughput claim","No during-provider-call failure claim"]}' >"${EVIDENCE_DIR}/evidence.json"
echo "Artemis constrained smoke passed; evidence: ${EVIDENCE_DIR}/evidence.json"
