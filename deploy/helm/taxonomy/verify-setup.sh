#!/usr/bin/env bash
# Invoked by the Maven-owned guided setup contract, not a second CI authority.
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
CHART="$ROOT/deploy/helm/taxonomy"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
COMMON=(--set image.tag=sha-0123456789abcdef0123456789abcdef01234567 --set existingSecret=taxonomy-secrets)
fail() {
  if helm template taxonomy "$CHART" "${COMMON[@]}" "$@" >"$TMP/error" 2>&1; then
    echo "Expected invalid setup to fail: $*" >&2; exit 1
  fi
}
reject() {
  local expected=$1
  shift
  fail "$@"
  if ! grep -Fq -- "$expected" "$TMP/error"; then
    echo "Setup failed for an unexpected reason; expected: $expected" >&2
    cat "$TMP/error" >&2
    exit 1
  fi
}

helm template taxonomy "$CHART" "${COMMON[@]}" >"$TMP/existing"
grep -q 'value: "postgres,kubernetes"' "$TMP/existing"
helm template taxonomy "$CHART" "${COMMON[@]}" --set authentication.mode=local >"$TMP/local"
grep -q 'value: "postgres,kubernetes,production,local-user-management"' "$TMP/local"
grep -q 'name: TAXONOMY_ADMIN_PASSWORD' "$TMP/local"
helm template taxonomy "$CHART" "${COMMON[@]}" --set authentication.mode=keycloak \
  --set config.KEYCLOAK_ISSUER_URI=https://identity.example.invalid/realms/taxonomy \
  --set config.KEYCLOAK_CLIENT_ID=taxonomy-app >"$TMP/keycloak"
grep -q 'value: "postgres,kubernetes,production,keycloak"' "$TMP/keycloak"
grep -q 'key: "KEYCLOAK_CLIENT_SECRET"' "$TMP/keycloak"
grep -q 'protocol/openid-connect/certs' "$TMP/keycloak"
if grep -q 'name: TAXONOMY_ADMIN_PASSWORD' "$TMP/keycloak"; then
  echo "Keycloak deployment must not require a local password" >&2; exit 1
fi
helm lint "$CHART" "${COMMON[@]}"
fail --set authentication.mode=invalid
fail --set authentication.mode=keycloak
fail --set authentication.mode=local --set-string 'config.SPRING_PROFILES_ACTIVE=postgres\,kubernetes\,keycloak'
fail --set authentication.mode=keycloak --set-string 'config.SPRING_PROFILES_ACTIVE=postgres\,kubernetes\,local-user-management'
fail --set database.url='jdbc:postgresql://db/taxonomy?password=must-not-leak'
fail --set config.SPRING_DATASOURCE_PASSWORD=must-not-leak
fail --set config.OPENAI_API_KEY=must-not-leak
fail --set service.port=70000
fail --set-json 'extraEnv=[{"name":"SPRING_PROFILES_ACTIVE","value":"unsafe"}]' --set authentication.mode=local
fail --set authentication.mode=local --set-json 'extraEnvFrom=[{"configMapRef":{"name":"ambiguous"}}]'

# Separate database Pods are addressed by Services; this does not provision a DB.
PG_URL='jdbc:postgresql://postgres.database.svc.cluster.local:5432/taxonomy?sslmode=verify-full'
MS_URL='jdbc:sqlserver://mssql.database.svc.cluster.local:1433;databaseName=taxonomy;encrypt=true;trustServerCertificate=false'
helm template taxonomy "$CHART" "${COMMON[@]}" --set database.type=postgres \
  --set-string "database.url=$PG_URL" >"$TMP/postgres-service"
grep -Fq "value: \"$PG_URL\"" "$TMP/postgres-service"
grep -q 'value: "postgres,kubernetes"' "$TMP/postgres-service"
helm template taxonomy "$CHART" "${COMMON[@]}" --set database.type=mssql \
  --set authentication.mode=local --set-string "database.url=$MS_URL" >"$TMP/mssql-service"
grep -Fq "value: \"$MS_URL\"" "$TMP/mssql-service"
grep -q 'value: "mssql,kubernetes,production,local-user-management"' "$TMP/mssql-service"
grep -q 'key: "SPRING_DATASOURCE_PASSWORD"' "$TMP/mssql-service"
test "$(grep -c 'name: SPRING_DATASOURCE_URL$' "$TMP/mssql-service")" -eq 1
if grep -q 'key: "SPRING_DATASOURCE_URL"' "$TMP/mssql-service"; then
  echo "An explicit URL must replace, not duplicate, the URL Secret mapping" >&2; exit 1
fi
# Engine selection must not opt MSSQL into a PostgreSQL migration or destructive DDL.
grep -A1 'name: TAXONOMY_DDL_AUTO$' "$TMP/mssql-service" | grep -q 'value: "validate"'
if grep -Eq 'name: (TAXONOMY_SCHEMA_MIGRATION_ENABLED|SPRING_FLYWAY_ENABLED|SPRING_DATASOURCE_DRIVER_CLASS_NAME)' "$TMP/mssql-service"; then
  echo "Engine selection must use the application profile, not override its migration/driver policy" >&2; exit 1
fi
helm template taxonomy "$CHART" "${COMMON[@]}" --set database.type=mssql >"$TMP/mssql-secret-url"
grep -q 'value: "mssql,kubernetes"' "$TMP/mssql-secret-url"
grep -q 'key: "SPRING_DATASOURCE_URL"' "$TMP/mssql-secret-url"
helm template taxonomy "$CHART" "${COMMON[@]}" \
  --set-string 'config.SPRING_PROFILES_ACTIVE=mssql\,kubernetes' >"$TMP/manual-mssql"
grep -q 'value: "mssql,kubernetes"' "$TMP/manual-mssql"
helm template taxonomy "$CHART" "${COMMON[@]}" --set database.type=mssql \
  --set authentication.mode=keycloak \
  --set-string 'config.SPRING_PROFILES_ACTIVE=production\,oracle\,kubernetes\,keycloak' \
  --set config.KEYCLOAK_ISSUER_URI=https://identity.example.invalid/realms/taxonomy \
  --set config.KEYCLOAK_CLIENT_ID=taxonomy-app >"$TMP/mssql-keycloak"
grep -q 'value: "mssql,production,kubernetes,keycloak"' "$TMP/mssql-keycloak"
if grep -q 'name: TAXONOMY_ADMIN_PASSWORD' "$TMP/mssql-keycloak"; then
  echo "Changing database engine must not re-enable local login in Keycloak mode" >&2; exit 1
fi
reject 'database' --set database.type=mysql
reject 'database' --set database.type=mssql --set-string "database.url=$PG_URL"
reject 'database' --set database.type=postgres --set-string "database.url=$MS_URL"
reject 'does not match the database profile' --set-string "database.url=$MS_URL"
reject 'requires one database profile' --set-string "database.url=$MS_URL" \
  --set-string 'config.SPRING_PROFILES_ACTIVE=postgres\,mssql\,kubernetes'
reject 'database.type conflicts' --set database.type=mssql --set config.SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver
reject 'database.url must not contain credentials' --set database.type=mssql --set-string 'database.url=jdbc:sqlserver://db:1433;userName=must-not-leak'
reject 'database.url must not contain credentials' --set database.type=mssql --set-string 'database.url=jdbc:sqlserver://db:1433;password=must-not-leak'
reject 'extraEnv duplicates managed setting' --set database.type=mssql \
  --set-json 'extraEnv=[{"name":"SPRING_PROFILES_ACTIVE","value":"postgres"}]'
reject 'Guided setup cannot verify extraEnvFrom precedence' --set database.type=mssql \
  --set-json 'extraEnvFrom=[{"configMapRef":{"name":"ambiguous"}}]'
for engine in postgres mssql; do
  helm template taxonomy "$CHART" "${COMMON[@]}" --namespace taxonomy \
    --values "$CHART/values-$engine-service.yaml" >"$TMP/$engine-example"
  grep -q 'kind: NetworkPolicy' "$TMP/$engine-example"
  grep -q 'kubernetes.io/metadata.name: database' "$TMP/$engine-example"
  grep -q "app.kubernetes.io/name: $engine" "$TMP/$engine-example"
  grep -q "value: \"$engine,kubernetes\"" "$TMP/$engine-example"
done
grep -q 'port: 5432' "$TMP/postgres-example"
grep -q 'port: 1433' "$TMP/mssql-example"
echo "Guided setup Helm contracts passed"
