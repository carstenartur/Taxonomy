#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"

ENFORCE=${TAXONOMY_OBSERVABILITY_PERFORMANCE_ENFORCE:-true}
WARMUP_REQUESTS=${TAXONOMY_OBSERVABILITY_PERFORMANCE_WARMUP_REQUESTS:-12}
MEASURED_REQUESTS=${TAXONOMY_OBSERVABILITY_PERFORMANCE_MEASURED_REQUESTS:-80}

exec ./mvnw -B -pl taxonomy-app -am verify \
  -DskipITs=false \
  -Dit.test=ObservabilityPerformanceIT \
  -Dtest=ObservabilityConfigurationTest,TaxonomyObservationConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DexcludedGroups=real-llm,onnx,db-postgres,db-mssql,db-oracle \
  -Dtaxonomy.model.download.skip=true \
  -Dtaxonomy.ui.skip=true \
  -Dtaxonomy.quality.skip=true \
  -Dtaxonomy.observability.performance.enabled=true \
  -Dtaxonomy.observability.performance.enforce="$ENFORCE" \
  -Dtaxonomy.observability.performance.warmup-requests="$WARMUP_REQUESTS" \
  -Dtaxonomy.observability.performance.measured-requests="$MEASURED_REQUESTS"
