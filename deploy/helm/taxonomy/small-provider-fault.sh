# Helpers for the small-profile acceptance fixture, called only in its owned namespace.
install_small_provider() (
  set -euo pipefail
  umask 077
  local private password
  private=$(mktemp -d)
  trap 'rm -rf -- "${private}"' EXIT
  password=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
  printf '%s' "${password}" >"${private}/password"
  keytool -genkeypair -alias small-provider -keyalg RSA -keysize 2048 -validity 2 \
    -dname CN=small-provider -ext SAN=dns:small-provider -storetype PKCS12 \
    -keystore "${private}/server.p12" -storepass:file "${private}/password" >/dev/null 2>&1
  keytool -exportcert -rfc -alias small-provider -keystore "${private}/server.p12" \
    -storepass:file "${private}/password" -file "${private}/provider.pem" >/dev/null 2>&1
  keytool -importcert -noprompt -alias fixture -file "${private}/provider.pem" \
    -storetype JKS -keystore "${private}/trust.jks" -storepass changeit >/dev/null 2>&1
  javac -d "${private}" "${CHART_DIR}/SmallProfileProvider.java"
  kubectl create secret generic small-provider-keys --namespace "${NAMESPACE}" \
    --from-file="${private}/server.p12" --from-file="${private}/password" >/dev/null
  kubectl create configmap small-provider-trust --namespace "${NAMESPACE}" \
    --from-file="${private}/provider.pem" --from-file="${private}/trust.jks" >/dev/null
  kubectl create configmap small-provider-class --namespace "${NAMESPACE}" \
    --from-file="${private}/SmallProfileProvider.class" >/dev/null
  sed "s|image: fixture-image-placeholder|image: ${IMAGE_REFERENCE}|" "${CHART_DIR}/small-provider.yaml" |
    kubectl apply --namespace "${NAMESPACE}" -f -
  kubectl rollout status --namespace "${NAMESPACE}" deployment/small-provider --timeout=2m
)

exercise_small_provider_fault() (
  local pod=$1 directory=$2 operation workspace lost replacement attempts state i http_pid private
  private=$(mktemp -d)
  http_pid=""
  trap 'if [[ -n "${http_pid}" ]]; then kill "${http_pid}" 2>/dev/null || true; wait "${http_pid}" 2>/dev/null || true; fi; rm -rf -- "${private}"' EXIT
  app_request() {
    kubectl exec --namespace "${NAMESPACE}" "${pod}" -- sh -c '
      exec curl --silent --show-error --max-time 120 --connect-timeout 3 \
        --user "admin:$TAXONOMY_ADMIN_PASSWORD" "$@"
    ' sh "$@"
  }
  local api=http://127.0.0.1:8080/api
  workspace=$(app_request --fail -H 'Content-Type: application/json' --data '{"displayName":"Small profile fault"}' \
    "${api}/workspace/create" | jq -er '.workspaceId | select(test("^[A-Za-z0-9_-]+$"))')
  app_request --fail -H 'Content-Type: application/json' --data '{}' "${api}/workspace/provision?workspaceId=${workspace}" |
    jq -e '.status == "READY"' >/dev/null
  operation=$(cat /proc/sys/kernel/random/uuid)
  lost=$(kubectl get pods --namespace "${NAMESPACE}" -l app=small-provider -o jsonpath='{.items[0].metadata.uid}')
  app_request --output /dev/null -w '%{http_code}' -H "X-Analysis-Operation-Id: ${operation}" \
    -H 'Content-Type: application/json' \
    --data '{"businessText":"Evaluate reliable command communications.","provider":"CUSTOM_OPENAI","includeArchitectureView":false,"analysisScope":{"taxonomyRoots":["CP"],"mode":"TAXONOMIES_ONLY"}}' \
    "${api}/analyze?workspaceId=${workspace}" >"${private}/analysis-http" &
  http_pid=$!
  for ((i=0; i<40; i++)); do
    attempts=$(kubectl exec --namespace "${NAMESPACE}" deployment/small-provider -- sh -c \
      'test ! -f /tmp/provider-attempts || cat /tmp/provider-attempts') || return 1
    [[ "${attempts}" == 1 ]] && break
    sleep 1
  done
  [[ "${attempts}" == 1 ]] || fail "No real provider request arrived on the controlled TLS listener"
  sample_resources >>"${directory}/inference-samples.ndjson"
  app_request --fail -X POST "${api}/analysis-runs/${operation}/cancel?workspaceId=${workspace}" |
    jq -e --arg op "${operation}" '.operationId == $op and (.status == "CANCELLING" or .status == "CANCELLED")' >/dev/null
  kubectl rollout restart deployment/small-provider --namespace "${NAMESPACE}"
  kubectl rollout status deployment/small-provider --namespace "${NAMESPACE}" --timeout=2m
  replacement=$(kubectl get pods --namespace "${NAMESPACE}" -l app=small-provider -o json |
    jq -er '[.items[] | select(.metadata.deletionTimestamp == null)] | select(length == 1) | .[0].metadata.uid')
  [[ -n "${lost}" && "${replacement}" != "${lost}" ]] || fail "Expected a replacement provider pod"
  wait "${http_pid}"; http_pid=""
  state=$(app_request --fail "${api}/analysis-runs/${operation}?workspaceId=${workspace}" |
    jq -ce --arg op "${operation}" 'select(.operationId == $op and .status == "CANCELLED") |
      {operationId,status,stopReason,queueWaitMillis,elapsedMillis}')
  kubectl exec --namespace "${NAMESPACE}" deployment/small-provider -- test ! -e /tmp/provider-attempts
  kubectl exec --namespace "${NAMESPACE}" "${pod}" -- curl -fsS --connect-timeout 3 --max-time 10 \
    --cacert /run/small-provider/provider.pem https://small-provider:8443/v1/models |
    jq -e '.data[0].id == "small-fixture"' >/dev/null
  jq -en --argjson operation "${state}" --arg old "${lost}" --arg new "${replacement}" \
    --arg http "$(cat "${private}/analysis-http")" --argjson count "${attempts}" '{
      operation:$operation, analysisHttpCode:$http, provider:"CUSTOM_OPENAI", tlsRecovery:true,
      oldProviderPodUid:$old, replacementProviderPodUid:$new, observedAttemptsBeforeLoss:$count,
      replacementReceivedInference:false}' >"${directory}/provider-fault.json"
)
