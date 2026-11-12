# Return only the two identities from one complete administrative observation.
parse_quarantined_permit() {
  jq -Rser '
    def uuid: test("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    split("\n") as $lines |
    select(any($lines[]; startswith("Inspection truncated=false;"))) |
    [$lines[] | select(contains("\t")) | split("\t")] |
    select(length == 1) | .[0] |
    select(length == 3 and (.[0] | uuid) and (.[1] | uuid) and (.[2] | test("^[1-9][0-9]*$"))) |
    .[:2] | join(" ")'
}

# Runs after the existing MOCK acceptance in the same fixture namespace.
exercise_artemis_provider_capacity() {
  set -euo pipefail
  local directory="${EVIDENCE_DIR}/provider-capacity" group=smoke-fault pod broker_uid new_broker_uid claim_uid
  mkdir -p "${directory}"
  source "${CHART_DIR}/small-provider-fault.sh"

  # The existing fixture credentials have explicit broker administration rights.
  admin_cli() {
    local category=$1
    shift
    kubectl exec --namespace "${NAMESPACE}" "${pod}" -- java -Xmx72m -XX:+UseSerialGC \
      -Dloader.path=/app/features "-Dloader.main=com.taxonomy.composition.analysis.artemis.ArtemisProvider${category}Provisioner" \
      -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher "$@"
  }
  pod=$(kubectl get pods --namespace "${NAMESPACE}" -l "${WEB_SELECTOR}" -o jsonpath='{.items[0].metadata.name}')
  admin_cli Permit "${group}" 1 >"${PRIVATE_DIR}/seed-permits.txt"
  admin_cli Quota "${group}" 10 0 10 >"${PRIVATE_DIR}/seed-quota.txt"
  install_small_provider
  local -a values=(--values "${CHART_DIR}/values-artemis-smoke.yaml" --values "${CHART_DIR}/values-artemis-provider-fault.yaml"
    --set-string "image.repository=${IMAGE_REPOSITORY}" --set-string "image.tag=${IMAGE_TAG}")
  helm template "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" "${values[@]}" >"${directory}/rendered.yaml"
  # Avoid transient surge against the namespace budget while reconfiguring the drained roles.
  kubectl scale deployment --namespace "${NAMESPACE}" -l "app.kubernetes.io/instance=${RELEASE}" --replicas=0
  roles_stopped() {
    kubectl get pods --namespace "${NAMESPACE}" -l "app.kubernetes.io/instance=${RELEASE}" -o json |
      jq -e '.items | length == 0' >/dev/null
  }
  await_until 180 "original application roles drained" roles_stopped
  helm upgrade "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" "${values[@]}" --wait --timeout=12m
  pod=$(kubectl get pods --namespace "${NAMESPACE}" -l "${WEB_SELECTOR}" -o jsonpath='{.items[0].metadata.name}')

  local slot_queue="taxonomy.analysis.provider-permits.${group}" quota_queue="taxonomy.analysis.provider-quotas.${group}"
  local slot_bean="org.apache.activemq.artemis:broker=\"taxonomy-analysis\",component=addresses,address=\"${slot_queue}\",subcomponent=queues,routing-type=\"anycast\",queue=\"${slot_queue}\""
  jq -n --arg bean "${slot_bean}" '{type:"read",mbean:$bean,attribute:["MessageCount","DeliveringCount","ConsumerCount"]}' \
    >"${PRIVATE_DIR}/slot-request.json"
  slot_delivery() {
    local measurement=$1 expected=$2
    curl --fail --silent --show-error --connect-timeout 3 --max-time 10 --config "${PRIVATE_DIR}/broker.curl" \
      -H 'Content-Type: application/json' --data-binary "@${PRIVATE_DIR}/slot-request.json" \
      http://127.0.0.1:18161/console/jolokia/ |
      jq -ce 'select(.status == 200) | .value | {MessageCount,DeliveringCount,ConsumerCount} |
        select(all(.[]; type == "number"))' >"${PRIVATE_DIR}/slot-state.json" || return 1
    cp "${PRIVATE_DIR}/slot-state.json" "${directory}/${measurement}.json"
    jq -e --argjson count "${expected}" '.MessageCount == 1 and .DeliveringCount == $count' "${PRIVATE_DIR}/slot-state.json" >/dev/null
  }
  local quota_bean="org.apache.activemq.artemis:broker=\"taxonomy-analysis\",component=addresses,address=\"${quota_queue}\",subcomponent=queues,routing-type=\"anycast\",queue=\"${quota_queue}\""
  jq -n --arg bean "${quota_bean}" '{type:"exec",mbean:$bean,operation:"browse(int,int,java.lang.String)",arguments:[1,1,""]}' \
    >"${PRIVATE_DIR}/quota-request.json"
  quota_state() {
    curl --fail --silent --show-error --connect-timeout 3 --max-time 10 --config "${PRIVATE_DIR}/broker.curl" \
      -H 'Content-Type: application/json' --data-binary "@${PRIVATE_DIR}/quota-request.json" \
      http://127.0.0.1:18161/console/jolokia/ |
      jq -ce 'select(.status == 200 and (.value | length) == 1) | .value[0].text | fromjson | .debits'
  }
  [[ $(quota_state) == '[]' ]] || fail "New provider group has existing rate debits"
  capture_started_debit() {
    slot_delivery permit-during-http 1 || fail "Physical HTTP must own one broker permit delivery"
    quota_state | jq -ce 'select(length == 1) | .[0] | select(.started == true and (.id | test("^[a-f0-9-]{36}$"))) | .id' \
      >"${PRIVATE_DIR}/started-debit.json" || fail "In-flight HTTP request lacks its unique consumed rate debit"
  }
  exercise_small_provider_fault "${pod}" "${directory}" capture_started_debit
  # Cancellation settles the coordinator independently of the worker's uncommitted JMS delivery.
  # Retire that worker and observe rollback at the broker before using the administrative browser.
  local cp_selector="${WORKER_SELECTOR},taxonomy.io/worker-set=cp" old_worker new_worker
  old_worker=$(kubectl get pods --namespace "${NAMESPACE}" -l "${cp_selector}" -o json |
    jq -er '[.items[] | select(.metadata.deletionTimestamp == null)] | select(length == 1) | .[0].metadata.uid')
  kubectl scale deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --replicas=0
  worker_gone() {
    kubectl get pods --namespace "${NAMESPACE}" -l "${cp_selector}" -o json |
      jq -e '.items | length == 0' >/dev/null
  }
  await_until 180 "provider worker terminated" worker_gone
  await_until 90 "broker rolled back the single worker permit delivery" slot_delivery permit-after-worker-loss 0
  receipt() {
    admin_cli Permit --inspect "${group}" >"${PRIVATE_DIR}/permit-observation.txt" &&
      parse_quarantined_permit <"${PRIVATE_DIR}/permit-observation.txt"
  }
  inspect_quarantine() { receipt >"${PRIVATE_DIR}/quarantine-before.txt"; }
  await_until 90 "one unavailable permit after cancellation and TLS disconnect" inspect_quarantine
  retained_debit() {
    quota_state | jq -e --slurpfile id "${PRIVATE_DIR}/started-debit.json" '
      length == 1 and .[0].started == true and .[0].id == $id[0]' >/dev/null
  }
  retained_debit || fail "Provider loss dropped or duplicated the conservative rate debit"

  # A replacement worker must be available to receive any accidental re-dispatch.
  kubectl scale deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --replicas=1
  kubectl rollout status deployment/taxonomy-artemis-worker-cp --namespace "${NAMESPACE}" --timeout=10m
  new_worker=$(kubectl get pods --namespace "${NAMESPACE}" -l "${cp_selector}" -o json |
    jq -er --arg old "${old_worker}" '[.items[] | select(.metadata.deletionTimestamp == null)] |
      select(length == 1) | .[0] | select(.metadata.uid != $old) | .metadata.uid')
  broker_uid=$(kubectl get pod -l app.kubernetes.io/name=taxonomy-smoke-broker --namespace "${NAMESPACE}" -o jsonpath='{.items[0].metadata.uid}')
  claim_uid=$(kubectl get pvc smoke-broker-data --namespace "${NAMESPACE}" -o jsonpath='{.metadata.uid}')
  kubectl rollout restart deployment/smoke-broker --namespace "${NAMESPACE}"
  kubectl rollout status deployment/smoke-broker --namespace "${NAMESPACE}" --timeout=5m
  new_broker_uid=$(kubectl get pods --namespace "${NAMESPACE}" -l app.kubernetes.io/name=taxonomy-smoke-broker -o json |
    jq -er --arg old "${broker_uid}" '[.items[] | select(.metadata.deletionTimestamp == null)] | select(length == 1) | .[0] |
      select(.metadata.uid != $old and any(.spec.volumes[]; .persistentVolumeClaim.claimName == "smoke-broker-data")) | .metadata.uid')
  [[ $(kubectl get pvc smoke-broker-data --namespace "${NAMESPACE}" -o jsonpath='{.metadata.uid}') == "${claim_uid}" ]] ||
    fail "Broker replacement used another claim"
  start_broker_forward
  recovered_worker_consuming() {
    broker_stats && jq -e '.value.ConsumerCount == 1 and .value.MessageCount == 0 and .value.DeliveringCount == 0' \
      "${PRIVATE_DIR}/broker-stats.json" >/dev/null
  }
  await_until 120 "replacement CP worker consuming the drained task queue on the recovered broker" recovered_worker_consuming
  await_until 90 "same single consumed rate debit after broker replacement" retained_debit
  await_until 90 "one durable permit on the replacement broker without a live delivery" slot_delivery permit-after-broker-loss 0
  receipt >"${PRIVATE_DIR}/quarantine-after.txt"
  cmp "${PRIVATE_DIR}/quarantine-before.txt" "${PRIVATE_DIR}/quarantine-after.txt" ||
    fail "Broker replacement changed the exact quarantined slot or attempt"
  kubectl exec --namespace "${NAMESPACE}" deployment/small-provider -- test ! -e /tmp/provider-attempts
  jq -en --arg oldWorker "${old_worker}" --arg newWorker "${new_worker}" --arg previous "${broker_uid}" --arg replacement "${new_broker_uid}" --arg claim "${claim_uid}" \
    --arg receipt "$(sha256sum "${PRIVATE_DIR}/quarantine-after.txt" | cut -d' ' -f1)" \
    --arg debit "$(sha256sum "${PRIVATE_DIR}/started-debit.json" | cut -d' ' -f1)" \
    --slurpfile fault "${directory}/provider-fault.json" '{
      operation:$fault[0].operation, priorProviderAttempts:$fault[0].observedAttemptsBeforeLoss,
      previousWorkerPodUid:$oldWorker, replacementWorkerPodUid:$newWorker, replacementWorkerConsuming:true,
      previousBrokerPodUid:$previous, replacementBrokerPodUid:$replacement, retainedClaimUid:$claim,
      quarantinedSlots:1, permitAndAttemptSha256:$receipt, conservativeRateDebits:1, debitIdSha256:$debit,
      rpm:10, rpd:10, replacementReceivedInference:false}' >"${directory}/evidence.json"
}
