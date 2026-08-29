#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
cd "${ROOT}"

./mvnw -B -pl taxonomy-tooling -am package \
  -DskipTests \
  -Dtaxonomy.ui.skip=true \
  -Dtaxonomy.quality.skip=true

mapfile -t jars < <(find taxonomy-tooling/target -maxdepth 1 -type f \
  -name 'taxonomy-tooling-*.jar' \
  ! -name '*-sources.jar' \
  ! -name '*-javadoc.jar' \
  | sort)

if [[ ${#jars[@]} -ne 1 ]]; then
  printf 'Expected exactly one taxonomy-tooling runtime JAR, found %d\n' "${#jars[@]}" >&2
  printf '%s\n' "${jars[@]:-}" >&2
  exit 1
fi

exec java -cp "${jars[0]}" \
  com.taxonomy.tooling.CatalogueOverlayProposalGenerator \
  --root "${ROOT}" \
  "$@"
