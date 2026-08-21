#!/usr/bin/env bash
set -euo pipefail

for part in .github/tmp/materialize-decision-report-parts/part-*.sh; do
  # shellcheck source=/dev/null
  source "$part"
done
