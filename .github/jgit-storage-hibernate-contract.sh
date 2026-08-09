#!/usr/bin/env bash
# Consumer-owned jgit-storage-hibernate contract for Taxonomy.
set -euo pipefail

mode=${JGIT_STORAGE_HIBERNATE_CONTRACT_MODE:-candidate}
candidate_version=${JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION:-}
evidence_dir=target/jgit-storage-hibernate-contract
mkdir -p "$evidence_dir"

printf 'bash %s\n' "$BASH_VERSION" | tee "$evidence_dir/bash-version.log"
if (( BASH_VERSINFO[0] < 4 )); then
  echo "Taxonomy's storage contract requires Bash 4 or newer." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "Taxonomy's storage contract requires Python 3." >&2
  exit 1
fi
python3 --version 2>&1 | tee "$evidence_dir/python-version.log"

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

# Keep the exact test authority in the same verification catalogue used by the
# workflow policy. The shell owns only environment checks, Maven orchestration
# and retained evidence; adding/removing contract tests happens in one place.
mapfile -t contract_selectors < <(
  python3 - <<'PY'
import json
from pathlib import Path

catalog = json.loads(Path(".mvn/verification-suites.json").read_text(encoding="utf-8"))
profile = catalog.get("profiles", {}).get("jgit-storage-hibernate-contract")
if not isinstance(profile, dict):
    raise SystemExit("verification catalogue is missing jgit-storage-hibernate-contract")
for key in ("test", "itTest", "excludedGroups"):
    value = profile.get(key)
    if not isinstance(value, str) or not value.strip():
        raise SystemExit(f"jgit-storage-hibernate-contract is missing {key}")
    print(value)
PY
)
if [[ ${#contract_selectors[@]} -ne 3 ]]; then
  echo "Could not resolve the storage contract from .mvn/verification-suites.json." >&2
  exit 1
fi
unit_csv=${contract_selectors[0]}
postgres_it_csv=${contract_selectors[1]}
excluded_groups=${contract_selectors[2]}

set -o pipefail
"${maven[@]}" -B -ntp -nsu \
  -pl taxonomy-app -am \
  -DskipITs=false \
  -DexcludedGroups="$excluded_groups" \
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
