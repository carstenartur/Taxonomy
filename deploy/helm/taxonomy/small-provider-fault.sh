# Helpers for the small-profile acceptance fixture, called only in its owned namespace.
render_small_provider() {
  case "${NAMESPACE:-}" in
    taxonomy-smoke|taxonomy-artemis-smoke) ;;
    *) printf '%s\n' 'small-provider requires an owned smoke namespace' >&2; return 1 ;;
  esac
  if ! [[ "${SOURCE_SHA:-}" =~ ^[0-9a-f]{40}$ ]] \
      || ! [[ "${IMAGE_REPOSITORY:-}" =~ ^[a-z0-9][a-z0-9._:/-]*$ ]] \
      || [[ "${IMAGE_REFERENCE:-}" != "${IMAGE_REPOSITORY:-}:sha-${SOURCE_SHA:-}" ]]; then
    printf '%s\n' 'small-provider requires the exact locally built SOURCE_SHA image' >&2
    return 1
  fi
  # Buffer the result until both namespaces and the single image have been
  # replaced. Template drift must fail before any Secret or workload is created.
  awk -v namespace="${NAMESPACE}" -v image="${IMAGE_REFERENCE}" '
    $0 == "  namespace: taxonomy-smoke" { $0 = "  namespace: " namespace; namespaces++ }
    $0 == "          image: fixture-image-placeholder:render-required" {
      $0 = "          image: " image; images++
    }
    { lines[NR] = $0 }
    END {
      if (namespaces != 2 || images != 1) {
        print "small-provider template must contain two namespace slots and one image slot" > "/dev/stderr"
        exit 1
      }
      for (line = 1; line <= NR; line++) print lines[line]
    }
  ' "${CHART_DIR}/small-provider.yaml"
}

install_small_provider() (
  set -euo pipefail
  umask 077
  local private password rendered
  rendered=$(render_small_provider) || return 1
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
  printf '%s\n' "${rendered}" |
    kubectl apply --namespace "${NAMESPACE}" -f -
  kubectl rollout status --namespace "${NAMESPACE}" deployment/small-provider --timeout=2m
)

exercise_small_provider_fault() (
  local pod=$1 directory=$2 observe_started=$3 operation workspace lost replacement attempts state i http_pid private
  private=$(mktemp -d)
  http_pid=""
  trap 'if [[ -n "${http_pid}" ]]; then kill "${http_pid}" 2>/dev/null || true; wait "${http_pid}" 2>/dev/null || true; fi; rm -rf -- "${private}"' EXIT
  rm -f -- "${directory}/analysis-http-status.txt"
  app_request() {
    kubectl exec --namespace "${NAMESPACE}" "${pod}" -- sh -c '
      exec curl --silent --show-error --max-time 120 --connect-timeout 3 \
        --user "admin:$TAXONOMY_ADMIN_PASSWORD" "$@"
    ' sh "$@"
  }
  local api=http://127.0.0.1:8080/api
  # Helm/probe readiness permits HTTP while a replacement web pod is still
  # validating its catalogue. Admission needs this pod's actual catalogue READY.
  local catalogue_deadline=$((SECONDS + 900))
  until app_request --fail --max-time 5 "${api}/status/startup" >"${directory}/catalogue-readiness.json" &&
      jq -e '.initialized == true' "${directory}/catalogue-readiness.json" >/dev/null; do
    (( SECONDS < catalogue_deadline )) || fail "Timed out: catalogue readiness before controlled provider analysis"
    sleep 2
  done
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
  if [[ "${attempts}" != 1 ]]; then
    local analysis_http_code
    analysis_http_code=$(cat "${private}/analysis-http")
    if [[ "${analysis_http_code}" =~ ^[0-9]{3}$ ]]; then
      printf '%s\n' "${analysis_http_code}" >"${directory}/analysis-http-status.txt"
      printf 'Analysis HTTP status before provider observation: %s\n' "${analysis_http_code}" >&2
    fi
    fail "No real provider request arrived on the controlled TLS listener"
  fi
  "${observe_started}"
  app_request --fail -X POST "${api}/analysis-runs/${operation}/cancel?workspaceId=${workspace}" |
    jq -e --arg op "${operation}" '.operationId == $op and (.status == "CANCELLING" or .status == "CANCELLED")' >/dev/null
  kubectl rollout restart deployment/small-provider --namespace "${NAMESPACE}"
  kubectl rollout status deployment/small-provider --namespace "${NAMESPACE}" --timeout=2m
  replacement=$(kubectl get pods --namespace "${NAMESPACE}" -l app=small-provider -o json |
    jq -er '[.items[] | select(.metadata.deletionTimestamp == null)] | select(length == 1) | .[0].metadata.uid')
  [[ -n "${lost}" && "${replacement}" != "${lost}" ]] || fail "Expected a replacement provider pod"
  wait "${http_pid}"; http_pid=""
  state=""
  for ((i=0; i<45; i++)); do
    if state=$(app_request --fail "${api}/analysis-runs/${operation}?workspaceId=${workspace}" |
        jq -ce --arg op "${operation}" 'select(.operationId == $op and .status == "CANCELLED") |
          {operationId,status,stopReason,queueWaitMillis,elapsedMillis}'); then break; fi
    sleep 1
  done
  [[ -n "${state}" ]] || fail "The cancelled operation did not reach its scoped terminal state"
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
