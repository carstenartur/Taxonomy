# Called inside constrained-smoke.sh while it owns this namespace and kubeconfig.
measure_small_search() {
  local directory="${EVIDENCE_DIR}/small-search" started ready pod uid node runtime_id
  local query count results samples group previous_pod=${POD}
  source "${CHART_DIR}/small-provider-fault.sh"
  install_small_provider
  local -a values=(--values "${CHART_DIR}/values-constrained-smoke.yaml"
    --values "${CHART_DIR}/values-small.yaml"
    --set-string "image.repository=${IMAGE_REPOSITORY}" --set-string "image.tag=${IMAGE_TAG}"
    --set-string config.TAXONOMY_REQUIRE_PASSWORD_CHANGE=false
    --set-string config.CUSTOM_LLM_URL=https://small-provider:8443/v1/chat/completions
    --set-string config.CUSTOM_LLM_MODEL=small-fixture
    --set-string 'config.JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/run/small-provider/trust.jks -Djavax.net.ssl.trustStoreType=JKS'
    --set-json 'extraVolumes=[{"name":"provider-trust","configMap":{"name":"small-provider-trust"}}]'
    --set-json 'extraVolumeMounts=[{"name":"provider-trust","mountPath":"/run/small-provider","readOnly":true}]'
    --set-string resources.requests.ephemeral-storage=64Mi --set-string resources.limits.ephemeral-storage=512Mi)
  mkdir -p "${directory}"
  rm -f "${directory}/evidence.json"
  helm template "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" "${values[@]}" >"${directory}/rendered.yaml"
  # The quota admits either profile individually. Release the original pod before resizing.
  kubectl scale "deployment/${DEPLOYMENT}" --replicas=0 --namespace "${NAMESPACE}"
  kubectl wait --namespace "${NAMESPACE}" --for=delete "pod/${previous_pod}" --timeout=2m
  started=$(date +%s)
  helm upgrade "${RELEASE}" "${CHART_DIR}" --namespace "${NAMESPACE}" "${values[@]}" --wait --timeout 12m
  kubectl rollout status "deployment/${DEPLOYMENT}" --namespace "${NAMESPACE}" --timeout=2m
  ready=$(date +%s)
  kubectl get pods --namespace "${NAMESPACE}" --selector "${RESOURCE_SELECTOR}" -o json |
    jq -e '[.items[] | select(.metadata.deletionTimestamp == null)] | select(length == 1) | .[0] |
      {pod:.metadata.name,uid:.metadata.uid,node:.spec.nodeName,resources:.spec.containers[0].resources,
       image:.status.containerStatuses[0].imageID}' >"${directory}/pod.json"
  pod=$(jq -er .pod "${directory}/pod.json"); uid=$(jq -er .uid "${directory}/pod.json")
  node=$(jq -er .node "${directory}/pod.json")
  runtime_id=$(jq -er '.image | split("://") | last' "${directory}/pod.json")
  # CRI image status supplies the configuration digest independently of the tag.
  docker exec "${node}" crictl inspecti "${runtime_id}" |
    jq -e --arg expected "${IMAGE_ID}" '.status | select(.id == $expected) |
      {configDigest:.id, repositoryDigests:.repoDigests}' >"${directory}/runtime-image.json"
  jq -e '.resources.requests.cpu == "100m" and .resources.requests.memory == "768Mi" and
    .resources.limits.cpu == "500m" and .resources.limits.memory == "1536Mi" and
    .resources.limits["ephemeral-storage"] == "512Mi"' "${directory}/pod.json" >/dev/null

  # DNS is permitted only on its declared ports. Prove the other listener is live first.
  kubectl get --raw /api/v1/namespaces/kube-system/services/kube-dns:metrics/proxy/metrics --request-timeout=10s >/dev/null
  kubectl exec --namespace "${NAMESPACE}" "${pod}" -- getent hosts small-provider >/dev/null
  local probe_status
  if kubectl exec --namespace "${NAMESPACE}" "${pod}" -- bash -c 'timeout 4 bash -c "exec 3<>/dev/tcp/kube-dns.kube-system.svc/9153"'; then
    fail "Small profile reached the undeclared DNS metrics port"
  else
    probe_status=$?
    [[ "${probe_status}" == 124 ]] || fail "Expected a denied egress timeout, received ${probe_status}"
  fi
  small_search() {
    kubectl exec --namespace "${NAMESPACE}" "${pod}" -- sh -c '
      curl -fsS --connect-timeout 2 --max-time 10 --user "admin:$TAXONOMY_ADMIN_PASSWORD" \
        "http://127.0.0.1:8080/api/search?q=$1&maxResults=5"
    ' sh "$1" | jq -er 'select(type == "array" and length > 0 and length <= 5 and
      all(.[]; (.code | type == "string" and length > 0))) | length'
  }
  # Async catalogue import can outlast probe readiness. Await the real search index.
  for ((count=0; count<60; count++)); do
    if small_search communication >/dev/null 2>&1; then break; fi
    sleep 3
  done
  ((count < 60)) || fail "Small profile search index did not become ready"
  sample_resources() {
    local counters bytes restarts
    counters=$(kubectl exec --namespace "${NAMESPACE}" "${pod}" -- sh -eu -c '
      cd /sys/fs/cgroup
      awk "\$1 == \"usage_usec\" || \$1 == \"nr_throttled\" || \$1 == \"throttled_usec\"" cpu.stat
      awk "\$1 == \"oom_kill\"" memory.events
      echo "memory_bytes $(cat memory.current)"
      awk "{printf \"ms %.0f\\n\", \$1*1000}" /proc/uptime
    ')
    # Kubelet accounts for pod logs, container writable layers and non-memory emptyDir volumes.
    bytes=$(kubectl get --raw "/api/v1/nodes/${node}/proxy/stats/summary" --request-timeout=10s |
      jq -er --arg uid "${uid}" '[.pods[] | select(.podRef.uid == $uid)] | select(length == 1)
        | .[0]["ephemeral-storage"].usedBytes | select(type == "number")')
    restarts=$(kubectl get pod "${pod}" --namespace "${NAMESPACE}" -o json |
      jq -er --arg uid "${uid}" 'select(.metadata.uid == $uid) | .status.containerStatuses[0].restartCount')
    jq -Rns --argjson bytes "${bytes}" --argjson restarts "${restarts}" '
      [inputs | split("\n")[] | select(length > 0) | split(" ") | {(.[0]):(.[1] | tonumber)}] | add |
      {ms, cpuUsec:.usage_usec, throttledPeriods:.nr_throttled, throttledUsec:.throttled_usec,
       oomKills:.oom_kill, memoryBytes:.memory_bytes, ephemeralBytes:$bytes, restarts:$restarts}' <<<"${counters}"
  }
  rm -f "${directory}/post-fault.ndjson" "${directory}/provider-fault.json"
  samples="${directory}/samples.ndjson"; results="${directory}/searches.ndjson"
  : >"${samples}"; : >"${results}"
  sample_resources >>"${samples}"; sleep 10; sample_resources >>"${samples}"
  for group in 1 2 3; do
    for query in communication command information; do
      count=$(small_search "${query}")
      jq -cn --arg query "${query}" --argjson count "${count}" '{query:$query,results:$count}' >>"${results}"
    done
    sample_resources >>"${samples}"
  done
  jq -se -f "${CHART_DIR}/small-search-budget.jq" "${samples}" >"${directory}/resources.json"
  sample_resources >"${directory}/inference-samples.ndjson"
  exercise_small_provider_fault "${pod}" "${directory}"
  sample_resources >>"${directory}/post-fault.ndjson"
  jq -en --arg sha "${SOURCE_SHA}" --arg tree "$(git -C "${ROOT_DIR}" rev-parse "${SOURCE_SHA}^{tree}")" \
    --arg chart "$(git -C "${ROOT_DIR}" rev-parse "${SOURCE_SHA}:deploy/helm/taxonomy")" \
    --arg rendered "$(sha256sum "${directory}/rendered.yaml" | cut -d' ' -f1)" \
    --argjson readiness "$((ready-started))" --slurpfile parent "${EVIDENCE_DIR}/evidence.json" \
    --slurpfile pod "${directory}/pod.json" --slurpfile image "${directory}/runtime-image.json" \
    --slurpfile inference "${directory}/inference-samples.ndjson" --slurpfile fault "${directory}/provider-fault.json" --slurpfile after "${directory}/post-fault.ndjson" \
    --slurpfile metrics "${directory}/resources.json" --slurpfile searches "${results}" '
      select(($searches | length) == 9 and $parent[0].source.commit == $sha and ($inference | length) == 2
        and all($inference[], $after[0]; .restarts == 0 and .oomKills == 0)) |
      {profile:"values-small.yaml", workload:"HSQLDB catalogue search and in-flight provider cancellation", source:$sha, tree:$tree,
       chartTree:$chart, renderedSha256:$rendered, cluster:$parent[0].cluster, pod:$pod[0], image:$image[0],
       readinessSeconds:$readiness, queries:$searches, searchMeasurements:$metrics[0], providerFault:$fault[0], providerSamples:$inference, afterProviderFault:$after[0],
       egress:{providerDns:true,providerTls:true,deniedDnsMetricsPort:9153}}' >"${directory}/evidence.tmp"
  mv "${directory}/evidence.tmp" "${directory}/evidence.json"
}
