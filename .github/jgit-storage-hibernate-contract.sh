#!/usr/bin/env bash
# Consumer-owned jgit-storage-hibernate contract for Taxonomy.
set -euo pipefail

mode=${JGIT_STORAGE_HIBERNATE_CONTRACT_MODE:-candidate}
candidate_version=${JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION:-}
evidence_dir=target/jgit-storage-hibernate-contract
mkdir -p "$evidence_dir"

case "$mode" in
  candidate)
    if [[ -z "$candidate_version" ]]; then
      echo "Candidate mode requires JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION." >&2
      exit 64
    fi
    ;;
  baseline)
    ;;
  *)
    echo "Unsupported contract mode: $mode" >&2
    exit 64
    ;;
esac

java -version 2>&1 | tee "$evidence_dir/java-version.log"
java_specification="$({ java -XshowSettings:properties -version; } 2>&1 \
  | sed -n 's/^ *java.specification.version = //p' \
  | tail -n 1)"
if [[ "$java_specification" != "21" ]]; then
  echo "Taxonomy's storage contract requires Java 21, found $java_specification." >&2
  exit 1
fi

docker info > "$evidence_dir/docker-info.log"

if [[ -x ./mvnw ]]; then
  maven=(./mvnw)
else
  echo "Taxonomy's checked-in Maven Wrapper is required." >&2
  exit 1
fi

# The storage contract is deliberately independent of external LLM providers,
# embedding-model downloads and browser suites. PostgreSQL, Flyway, Hibernate
# validation and Taxonomy's own Hibernate Search projection remain mandatory.
export GEMINI_API_KEY=
export OPENAI_API_KEY=
export ANTHROPIC_API_KEY=
export TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD=false
export TAXONOMY_MODEL_DOWNLOAD_SKIP=true

unit_tests=(
  JgitStorageHibernateIntegrationTest
  JgitStorageOptimizedIndexContractTest
  JgitStorageSchemaIndexValidationTest
  JgitStorageSchemaMigrationConfigTest
  CommitIndexHibernateSearchTest
)
postgres_it_tests=(
  JgitStoragePostgresMigrationIT
  TaxonomyPostgresValidateStartupIT
  TaxonomySchemaPostgresMigrationIT
)
unit_csv=$(IFS=,; echo "${unit_tests[*]}")
postgres_it_csv=$(IFS=,; echo "${postgres_it_tests[*]}")

set -o pipefail
"${maven[@]}" -B -ntp -nsu \
  -pl taxonomy-app -am \
  -DskipITs=false \
  -DexcludedGroups=real-llm,onnx,db-mssql,db-oracle \
  -Dtaxonomy.model.download.skip=true \
  -Dtaxonomy.ui.skip=true \
  -Dtaxonomy.quality.skip=true \
  -Dtest="$unit_csv" \
  -Dit.test="$postgres_it_csv" \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  verify 2>&1 | tee "$evidence_dir/maven-storage-contract.log"

required_reports=(
  taxonomy-app/target/surefire-reports/TEST-com.taxonomy.dsl.storage.JgitStorageHibernateIntegrationTest.xml
  taxonomy-app/target/surefire-reports/TEST-com.taxonomy.dsl.storage.JgitStorageOptimizedIndexContractTest.xml
  taxonomy-app/target/surefire-reports/TEST-com.taxonomy.dsl.storage.JgitStorageSchemaIndexValidationTest.xml
  taxonomy-app/target/surefire-reports/TEST-com.taxonomy.dsl.storage.JgitStorageSchemaMigrationConfigTest.xml
  taxonomy-app/target/surefire-reports/TEST-com.taxonomy.dsl.CommitIndexHibernateSearchTest.xml
  taxonomy-app/target/failsafe-reports/TEST-com.taxonomy.dsl.storage.JgitStoragePostgresMigrationIT.xml
  taxonomy-app/target/failsafe-reports/TEST-com.taxonomy.dsl.storage.TaxonomyPostgresValidateStartupIT.xml
  taxonomy-app/target/failsafe-reports/TEST-com.taxonomy.dsl.storage.TaxonomySchemaPostgresMigrationIT.xml
)
for report in "${required_reports[@]}"; do
  if [[ ! -s "$report" ]]; then
    echo "Required Taxonomy storage evidence is missing: $report" >&2
    exit 1
  fi
done
printf '%s\n' "${required_reports[@]}" > "$evidence_dir/required-reports.txt"

"${maven[@]}" -B -ntp -nsu \
  -pl taxonomy-app \
  -Dincludes=io.github.carstenartur \
  -DoutputType=text \
  -DoutputFile="$PWD/$evidence_dir/dependency-tree.txt" \
  dependency:tree

test -s "$evidence_dir/dependency-tree.txt"
if grep -Fq 'jgit-storage-hibernate-benchmarks' "$evidence_dir/dependency-tree.txt"; then
  echo "Benchmark artifacts must not enter the Taxonomy runtime." >&2
  exit 1
fi
if [[ "$mode" == "candidate" ]] \
    && ! grep -Fq ":$candidate_version" "$evidence_dir/dependency-tree.txt"; then
  echo "Taxonomy did not resolve candidate $candidate_version." >&2
  cat "$evidence_dir/dependency-tree.txt" >&2
  exit 1
fi

cat > "$evidence_dir/result.json" <<EOF
{
  "consumer": "Taxonomy",
  "mode": "$mode",
  "candidateVersion": "$candidate_version",
  "java": "$java_specification",
  "contract": "Core schema and Hibernate Search unit contracts plus PostgreSQL Testcontainers migration and validate-startup evidence"
}
EOF

printf 'Taxonomy jgit-storage-hibernate contract passed in %s mode.\n' "$mode"
