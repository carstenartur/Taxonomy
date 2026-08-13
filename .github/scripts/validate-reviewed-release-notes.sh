#!/usr/bin/env bash
# Validate reviewed, version-bound release notes and optionally compare a GitHub Release body.
set -euo pipefail

RELEASE_VERSION=${RELEASE_VERSION:-}
RELEASE_NOTES_FILE=${RELEASE_NOTES_FILE:-release_notes.md}
RELEASE_BODY_JSON_FILE=${RELEASE_BODY_JSON_FILE:-}

fail() {
  echo "::error::$*" >&2
  exit 1
}

if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  fail "RELEASE_VERSION must use canonical X.Y.Z syntax, got '$RELEASE_VERSION'"
fi
if [[ ! -f "$RELEASE_NOTES_FILE" ]]; then
  fail "Reviewed release notes file is missing: $RELEASE_NOTES_FILE"
fi
if [[ ! -r "$RELEASE_NOTES_FILE" ]]; then
  fail "Reviewed release notes file is not readable: $RELEASE_NOTES_FILE"
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
  'TODO' \
  'TBD'; do
  if grep -Fq "$placeholder" "$RELEASE_NOTES_FILE"; then
    fail "Reviewed release notes still contain placeholder text: $placeholder"
  fi
done
if grep -Fxq 'Initial release' "$RELEASE_NOTES_FILE"; then
  fail "Reviewed release notes still contain generated placeholder text: Initial release"
fi

if ! python3 - "$RELEASE_NOTES_FILE" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
data = path.read_bytes()
try:
    data.decode("utf-8")
except UnicodeDecodeError as error:
    print(f"::error::Reviewed release notes are not valid UTF-8: {error}", file=sys.stderr)
    raise SystemExit(1)
if b"\r" in data:
    print("::error::Reviewed release notes must use canonical LF line endings", file=sys.stderr)
    raise SystemExit(1)
if not data.endswith(b"\n"):
    print("::error::Reviewed release notes must end with a line feed", file=sys.stderr)
    raise SystemExit(1)
PY
then
  exit 1
fi

if [[ -n "$RELEASE_BODY_JSON_FILE" ]]; then
  if [[ ! -f "$RELEASE_BODY_JSON_FILE" ]]; then
    fail "GitHub Release body JSON is missing: $RELEASE_BODY_JSON_FILE"
  fi
  if ! python3 - "$RELEASE_NOTES_FILE" "$RELEASE_BODY_JSON_FILE" <<'PY'
from hashlib import sha256
import json
from pathlib import Path
import sys

notes_path = Path(sys.argv[1])
body_path = Path(sys.argv[2])
expected = notes_path.read_bytes()
try:
    payload = json.loads(body_path.read_text(encoding="utf-8"))
except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
    print(f"::error::Cannot read GitHub Release body JSON: {error}", file=sys.stderr)
    raise SystemExit(1)
body = payload.get("body")
if not isinstance(body, str):
    print("::error::GitHub Release body JSON must contain a string field named 'body'", file=sys.stderr)
    raise SystemExit(1)
actual = body.encode("utf-8")
if actual != expected:
    print(
        "::error::GitHub Release body differs from the reviewed release notes "
        f"(expected sha256={sha256(expected).hexdigest()}, "
        f"actual sha256={sha256(actual).hexdigest()})",
        file=sys.stderr,
    )
    raise SystemExit(1)
PY
  then
    exit 1
  fi
fi

echo "Reviewed release notes are valid for Taxonomy ${RELEASE_VERSION}: ${RELEASE_NOTES_FILE} (${byte_count} bytes)."
