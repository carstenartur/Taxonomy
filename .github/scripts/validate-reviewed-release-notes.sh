#!/usr/bin/env bash
# Materialize and validate the exact reviewed release notes committed with the release source.
set -euo pipefail

RELEASE_VERSION=${RELEASE_VERSION:-}
RELEASE_NOTES_COMMIT=${RELEASE_NOTES_COMMIT:-}
RELEASE_NOTES_FILE=${RELEASE_NOTES_FILE:-release_notes.md}
readonly RELEASE_NOTES_PATH=release_notes.md

fail() {
  echo "::error::$*" >&2
  exit 1
}

if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  fail "RELEASE_VERSION must use canonical X.Y.Z syntax, got '$RELEASE_VERSION'"
fi

source_commit=""
notes_blob=""
if [[ -n "$RELEASE_NOTES_COMMIT" ]]; then
  if ! source_commit=$(git rev-parse "${RELEASE_NOTES_COMMIT}^{commit}" 2>/dev/null); then
    fail "Unable to resolve release-notes source commit: $RELEASE_NOTES_COMMIT"
  fi
  if ! notes_blob=$(git rev-parse "${source_commit}:${RELEASE_NOTES_PATH}" 2>/dev/null); then
    fail "Release commit $source_commit does not contain $RELEASE_NOTES_PATH"
  fi

  mkdir -p "$(dirname "$RELEASE_NOTES_FILE")"
  temporary_file="${RELEASE_NOTES_FILE}.tmp.$$"
  trap 'rm -f "$temporary_file"' EXIT
  git cat-file blob "$notes_blob" > "$temporary_file"

  materialized_blob=$(git hash-object "$temporary_file")
  if [[ "$materialized_blob" != "$notes_blob" ]]; then
    fail "Materialized release notes do not match committed blob $notes_blob"
  fi
  mv "$temporary_file" "$RELEASE_NOTES_FILE"
  trap - EXIT
fi

if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
  fail "Reviewed release notes file is missing: $RELEASE_NOTES_FILE"
fi
if [[ ! -s "$RELEASE_NOTES_FILE" ]]; then
  fail "Reviewed release notes file is empty: $RELEASE_NOTES_FILE"
fi

expected_heading="# Taxonomy ${RELEASE_VERSION}"
first_content_line=$(awk 'NF { print; exit }' "$RELEASE_NOTES_FILE")
if [[ "$first_content_line" != "$expected_heading" ]]; then
  fail "Reviewed release notes must begin with '$expected_heading', got '$first_content_line'"
fi
if [[ $(grep -Fxc "$expected_heading" "$RELEASE_NOTES_FILE") -ne 1 ]]; then
  fail "Reviewed release notes must contain the exact version heading once"
fi
if ! grep -Eq '^##[[:space:]]+[^[:space:]]' "$RELEASE_NOTES_FILE"; then
  fail "Reviewed release notes must contain at least one substantive section"
fi

byte_count=$(wc -c < "$RELEASE_NOTES_FILE")
if (( byte_count < 200 )); then
  fail "Reviewed release notes are implausibly short (${byte_count} bytes)"
fi

for placeholder in \
  'No closed issues found since' \
  'Initial release'; do
  if grep -Fq "$placeholder" "$RELEASE_NOTES_FILE"; then
    fail "Reviewed release notes still contain generated placeholder text: $placeholder"
  fi
done

if [[ -n "$source_commit" ]]; then
  printf 'Reviewed release notes materialized from %s:%s (blob %s).\n' \
    "$source_commit" "$RELEASE_NOTES_PATH" "$notes_blob"
fi
printf 'Reviewed release notes are valid for Taxonomy %s: %s (%s bytes).\n' \
  "$RELEASE_VERSION" "$RELEASE_NOTES_FILE" "$byte_count"
