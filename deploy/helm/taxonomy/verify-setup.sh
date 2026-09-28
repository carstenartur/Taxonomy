#!/usr/bin/env bash
set -euo pipefail
CHART_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT
COMMON=(--set image.tag=sha-0123456789abcdef0123456789abcdef01234567 --set existingSecret=taxonomy-secrets)
render() { helm template taxonomy "$CHART_DIR" "${COMMON[@]}" "$@"; }
reject() {
  if render "$@" >"$TMP_DIR/rejected.yaml" 2>"$TMP_DIR/error.log"; then
    echo 'Expected setup validation failure' >&2
    exit 1
  fi
}
render >"$TMP_DIR/existing.yaml"
! grep -q 'local-user-management' "$TMP_DIR/existing.yaml"
render --set authentication.mode=local >"$TMP_DIR/local.yaml"
grep -q 'postgres,kubernetes,production,local-user-management' "$TMP_DIR/local.yaml"
grep -q 'name: TAXONOMY_ADMIN_PASSWORD' "$TMP_DIR/local.yaml"
render --set authentication.mode=keycloak \
  --set config.KEYCLOAK_ISSUER_URI=https://identity.example.invalid/realms/taxonomy \
  --set config.KEYCLOAK_CLIENT_ID=taxonomy-app >"$TMP_DIR/oidc.yaml"
grep -q 'postgres,kubernetes,production,keycloak' "$TMP_DIR/oidc.yaml"
grep -q 'name: KEYCLOAK_CLIENT_SECRET' "$TMP_DIR/oidc.yaml"
! grep -q 'name: TAXONOMY_ADMIN_PASSWORD' "$TMP_DIR/oidc.yaml"
grep -q '/protocol/openid-connect/certs' "$TMP_DIR/oidc.yaml"
render --set database.url=jdbc:postgresql://db:5432/taxonomy --set database.username=taxonomy >"$TMP_DIR/db.yaml"
test "$(grep -c 'name: SPRING_DATASOURCE_URL' "$TMP_DIR/db.yaml")" = 1
! grep -q 'key: "SPRING_DATASOURCE_URL"' "$TMP_DIR/db.yaml"
reject --set authentication.mode=invalid
reject --set authentication.mode=keycloak
reject --set authentication.mode=local --set-string 'config.SPRING_PROFILES_ACTIVE=postgres\,keycloak'
reject --set database.url=jdbc:postgresql://user:password@db/taxonomy
reject --set 'database.url=jdbc:postgresql://db/taxonomy?password=not-a-real-secret'
reject --set database.url=jdbc:postgresql://db/taxonomy --set config.SPRING_DATASOURCE_URL=jdbc:postgresql://other/taxonomy
reject --set config.SPRING_DATASOURCE_PASSWORD=must-use-a-secret
reject --set service.port=70000
reject --set authentication.mode=local --set 'extraEnv[0].name=SPRING_PROFILES_ACTIVE' --set 'extraEnv[0].value=hsqldb'
printf 'Guided Helm setup verification passed.\n'
