#!/usr/bin/env bash
# Focused review regressions, invoked by the existing Maven setup contract.
set -euo pipefail
CHART=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
TEST_CHART=$CHART
COMMON=(--set image.tag=sha-0123456789abcdef0123456789abcdef01234567 --set existingSecret=taxonomy-secrets)
OIDC=(--set authentication.mode=keycloak --set config.KEYCLOAK_CLIENT_ID=taxonomy-app --set-string config.KEYCLOAK_ISSUER_URI=https://identity.example.invalid/realms/taxonomy)
COUNT=0
FAILED=0
accept() {
  local name=$1
  shift
  COUNT=$((COUNT + 1))
  if helm template taxonomy "$TEST_CHART" "${COMMON[@]}" "$@" >"$TMP/rendered" 2>"$TMP/error"; then
    echo "PASS: $name"
  else
    echo "FAIL: $name (valid configuration rejected)" >&2
    FAILED=$((FAILED + 1))
  fi
}
reject() {
  local name=$1 expected=$2
  shift 2
  COUNT=$((COUNT + 1))
  if helm template taxonomy "$TEST_CHART" "${COMMON[@]}" "$@" >"$TMP/rendered" 2>"$TMP/error"; then
    echo "FAIL: $name (invalid configuration accepted)" >&2
    FAILED=$((FAILED + 1))
  elif ! grep -Fq -- "$expected" "$TMP/error"; then
    echo "FAIL: $name (unrelated validation failure)" >&2
    FAILED=$((FAILED + 1))
  elif grep -Fq 'fixture-secret-never-print' "$TMP/error"; then
    echo "FAIL: $name (credential exposed in diagnostic)" >&2
    FAILED=$((FAILED + 1))
  else
    echo "PASS: $name"
  fi
}
accept 'existing configuration remains valid'
accept 'HTTPS issuer and derived JWK URL' "${OIDC[@]}"
accept 'HTTPS issuer with port and trailing slash' "${OIDC[@]}" --set-string config.KEYCLOAK_ISSUER_URI=https://identity.example.invalid:8443/realms/taxonomy/
accept 'explicit HTTPS JWK endpoint' "${OIDC[@]}" --set-string config.KEYCLOAK_JWK_SET_URI=https://keys.example.invalid/keys
accept 'IPv6 HTTPS issuer' "${OIDC[@]}" --set-string 'config.KEYCLOAK_ISSUER_URI=https://[::1]:8443/realms/taxonomy'
for endpoint in KEYCLOAK_ISSUER_URI KEYCLOAK_JWK_SET_URI; do
  for url in 'http://identity.example.invalid/realm' 'http://localhost:8080/realm' \
      'https://user:fixture-secret-never-print@identity.example.invalid/realm' \
      'https://identity.example.invalid/realm?token=fixture-secret-never-print' \
      'https://identity.example.invalid/realm#fragment' 'https://:443/realm' \
      'https://identity example.invalid/realm' 'https://identity.example.invalid\evil/realm'; do
    # --set-string consumes backslash escapes; --set-file preserves the exact hostile input.
    printf '%s' "$url" >"$TMP/endpoint-value"
    reject "unsafe $endpoint" "$endpoint" "${OIDC[@]}" --set-file "config.$endpoint=$TMP/endpoint-value"
  done
done
for name in ADMIN_TOKEN CUSTOM_ACCESS_TOKEN customApiKey CUSTOM_PRIVATE_KEY CUSTOM_CREDENTIALS Custom_PassWd; do
  # Helm reports propertyNames errors with the offending key, not the containing config path.
  reject "schema rejects ordinary $name" "propertyName '$name'" --set-json "config={\"$name\":\"fixture-secret-never-print\"}"
done
accept 'metadata is not a credential' --set-json 'config={"CUSTOM_TOKEN_URI":"https://identity.example.invalid/token","PASSWORD_MIN_LENGTH":"16","CUSTOM_API_KEY_FILE":"/private/key"}'
accept 'custom credential uses Secret reference' --set-json 'secretEnv.CUSTOM_ACCESS_TOKEN={"key":"CUSTOM_ACCESS_TOKEN","optional":false}'
accept 'extraEnv Secret reference remains supported' --set-json 'extraEnv=[{"name":"CUSTOM_ACCESS_TOKEN","valueFrom":{"secretKeyRef":{"name":"other-credentials","key":"token"}}}]'
reject 'extraEnv literal credential' 'secretKeyRef' --set-json 'extraEnv=[{"name":"CUSTOM_ACCESS_TOKEN","value":"fixture-secret-never-print"}]'
reject 'extraEnv ConfigMap credential' 'secretKeyRef' --set-json 'extraEnv=[{"name":"CUSTOM_ACCESS_TOKEN","valueFrom":{"configMapKeyRef":{"name":"ordinary","key":"token"}}}]'
# Prove template-level protection as well: removing JSON Schema must not expose secrets.
cp -R "$CHART" "$TMP/no-schema"
rm "$TMP/no-schema/values.schema.json"
TEST_CHART="$TMP/no-schema"
for name in ADMIN_TOKEN CUSTOM_ACCESS_TOKEN customApiKey CUSTOM_PRIVATE_KEY CUSTOM_CREDENTIALS Custom_PassWd; do
  reject "template rejects ordinary $name without schema" 'Secret' --set-json "config={\"$name\":\"fixture-secret-never-print\"}"
done
reject 'template rejects HTTP issuer without schema' 'KEYCLOAK_ISSUER_URI' "${OIDC[@]}" --set-string config.KEYCLOAK_ISSUER_URI=http://identity.example.invalid/realm
accept 'Secret transport without schema remains valid' --set-json 'secretEnv.CUSTOM_ACCESS_TOKEN={"key":"CUSTOM_ACCESS_TOKEN","optional":false}'
# Custom provider URLs use the same endpoint restrictions, with HTTP only on exact loopback hosts.
for schema in present absent; do
  if [[ "$schema" == present ]]; then TEST_CHART=$CHART; else TEST_CHART="$TMP/no-schema"; fi
  reject "Keycloak extraEnv cannot restore local bootstrap ($schema schema)" 'TAXONOMY_ADMIN_PASSWORD' "${OIDC[@]}" \
    --set-json 'extraEnv=[{"name":"TAXONOMY_ADMIN_PASSWORD","valueFrom":{"secretKeyRef":{"name":"local-admin","key":"password"}}}]'
  accept "Keycloak still permits unrelated Secret refs ($schema schema)" "${OIDC[@]}" \
    --set-json 'extraEnv=[{"name":"CUSTOM_ACCESS_TOKEN","valueFrom":{"secretKeyRef":{"name":"other-credentials","key":"token"}}}]'
  for mode in existing local keycloak; do
    custom=("${OIDC[@]}" --set "authentication.mode=$mode" --set config.LLM_PROVIDER=CUSTOM_OPENAI --set config.CUSTOM_LLM_MODEL=fixture-model)
    for url in 'https://ai.example.invalid/v1/chat/completions' 'http://localhost:11434/v1/chat/completions' 'http://127.0.0.1:11434/v1/chat/completions' 'http://[::1]:11434/v1/chat/completions'; do
      printf '%s' "$url" >"$TMP/endpoint-value"
      accept "custom endpoint ($schema schema, $mode)" "${custom[@]}" --set-file "config.CUSTOM_LLM_URL=$TMP/endpoint-value"
    done
    for url in 'https://user:fixture-secret-never-print@ai.example.invalid/v1' 'https://ai.example.invalid/v1?token=fixture-secret-never-print' 'https://ai.example.invalid/v1#fragment' 'http://ai.example.invalid/v1' 'http://localhost.evil.invalid/v1' 'https://:443/v1' 'https://ai.example.invalid\evil/v1' 'file:/private/ai'; do
      printf '%s' "$url" >"$TMP/endpoint-value"
      reject "unsafe custom endpoint ($schema schema, $mode)" 'CUSTOM_LLM_URL' "${custom[@]}" --set-file "config.CUSTOM_LLM_URL=$TMP/endpoint-value"
    done
  done
  reject 'custom endpoint may not bypass validation through extraEnv' 'CUSTOM_LLM_URL' \
    --set-json 'extraEnv=[{"name":"CUSTOM_LLM_URL","value":"https://user:fixture-secret-never-print@ai.example.invalid/v1"}]'
done
echo "Setup Helm security regressions: $COUNT cases, $FAILED failures"
test "$FAILED" -eq 0
