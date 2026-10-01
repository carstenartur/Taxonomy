#!/usr/bin/env bash
# Packaging only. The canonical verification job remains the test authority.
set -euo pipefail

attempt_log=$(mktemp)
trap 'rm -f "$attempt_log"' EXIT

for attempt in 1 2; do
  arguments=(-B -ntp -DskipTests)
  if (( attempt == 2 )); then
    # Maven caches failed transfers. A fresh process with -U checks missing releases again.
    arguments+=(-U)
  fi

  set +e
  ./mvnw "${arguments[@]}" package 2>&1 | tee "$attempt_log"
  statuses=("${PIPESTATUS[@]}")
  set -e

  result=${statuses[0]}
  # Preserve Maven failures, and fail a successful build if its log pipe failed.
  if (( result == 0 )); then exit "${statuses[1]}"; fi
  if (( statuses[1] != 0 )); then exit "$result"; fi
  if (( result >= 128 )); then exit "$result"; fi

  # Alternate-repository misses may accompany the Central 502. A retry is still
  # allowed for mixed resolution failures, but its final result always wins.
  if (( attempt == 2 )) \
      || ! grep -Eq '^\[ERROR\].*Could not resolve dependencies' "$attempt_log" \
      || ! grep -Eq '^\[ERROR\].*from/to central.*status code: 502' "$attempt_log"; then
    exit "$result"
  fi
  echo '::warning::Maven Central returned HTTP 502 during dependency resolution; retrying packaging once with -U.'
done
