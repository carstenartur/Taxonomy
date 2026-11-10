#!/usr/bin/env bash
set -euo pipefail
# Java source launch compiles the exact checked-out tooling, including sibling sources.
# Immutable release workflows may explicitly supply their already built exact-source JAR.
if [[ -n "${TOOLING_JAR:-}" ]]; then
  exec java -jar "$TOOLING_JAR" "$@"
fi
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
exec java "$source_root/taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java" "$@"
