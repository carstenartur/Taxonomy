#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${METADATA_HELPER:?METADATA_HELPER is required}"
: "${VEX_HELPER:?VEX_HELPER is required}"

NEXT_VERSION_INPUT=${NEXT_VERSION_INPUT:-}
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
SOURCE_BRANCH=${SOURCE_BRANCH:-main}
RENDER_DEPLOY_HOOK_URL=${RENDER_DEPLOY_HOOK_URL:-}

TAG_NAME="v${RELEASE_VERSION}"
MAJOR_MINOR=$(echo "${RELEASE_VERSION}" | sed 's/\.[^.]*$//')
MAINTENANCE_BRANCH="maintenance/${MAJOR_MINOR}.x"

if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::release_version must use X.Y.Z without a leading v"
  exit 1
fi

if [[ "$SOURCE_BRANCH" != "main" && "$DRY_RUN" != "true" ]]; then
  echo "::error::Real releases must be dispatched from main, not $SOURCE_BRANCH"
  exit 1
fi

CURRENT_VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$CURRENT_VERSION" != *-SNAPSHOT ]]; then
  echo "::error::Current Maven version must be a SNAPSHOT, but was $CURRENT_VERSION"
  exit 1
fi
if [[ "${CURRENT_VERSION%-SNAPSHOT}" != "$RELEASE_VERSION" ]]; then
  if [[ "$DRY_RUN" == "true" ]]; then
    echo "::warning::Release $RELEASE_VERSION does not match current version $CURRENT_VERSION"
  else
    echo "::error::Release $RELEASE_VERSION does not match current version $CURRENT_VERSION"
    exit 1
  fi
fi

if [[ -n "$NEXT_VERSION_INPUT" ]]; then
  NEXT_VERSION=$NEXT_VERSION_INPUT
else
  IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
  NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"
fi
if ! [[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  echo "::error::next_development_version must use X.Y.Z-SNAPSHOT"
  exit 1
fi

verify_metadata() {
  local expected=$1
  local release_mode=$2
  local maven_version
  maven_version=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
  if [[ "$maven_version" != "$expected" ]]; then
    echo "::error::Maven version $maven_version != $expected"
    exit 1
  fi

  EXPECTED_VERSION="$expected" RELEASE_MODE="$release_mode" python3 - <<'PY'
import json
import os
import re
from pathlib import Path

expected = os.environ['EXPECTED_VERSION']
release_mode = os.environ['RELEASE_MODE'] == 'true'

citation = Path('CITATION.cff').read_text(encoding='utf-8')
version_match = re.search(r'^version: "([^"]+)"$', citation, flags=re.MULTILINE)
if not version_match or version_match.group(1) != expected:
    raise SystemExit(f'CITATION.cff version does not match {expected!r}')
has_date_released = bool(re.search(r'^date-released: ', citation, flags=re.MULTILINE))
if release_mode and not has_date_released:
    raise SystemExit('CITATION.cff date-released is missing')
if not release_mode and has_date_released:
    raise SystemExit('CITATION.cff still contains date-released for a development snapshot')

with open('.zenodo.json', encoding='utf-8') as handle:
    zenodo = json.load(handle)
if zenodo.get('version') != expected:
    raise SystemExit(f'.zenodo.json version {zenodo.get("version")!r} != {expected!r}')
has_publication_date = 'publication_date' in zenodo
if release_mode and not has_publication_date:
    raise SystemExit('.zenodo.json publication_date is missing')
if not release_mode and has_publication_date:
    raise SystemExit('.zenodo.json still contains publication_date for a development snapshot')

with open('codemeta.json', encoding='utf-8') as handle:
    codemeta = json.load(handle)
if codemeta.get('version') != expected:
    raise SystemExit(f'codemeta.json version {codemeta.get("version")!r} != {expected!r}')
has_date_published = 'datePublished' in codemeta
if release_mode and not has_date_published:
    raise SystemExit('codemeta.json datePublished is missing')
if not release_mode and has_date_published:
    raise SystemExit('codemeta.json still contains datePublished for a development snapshot')
PY
}

ensure_no_snapshot_poms() {
  local remaining
  remaining=$(grep -R "SNAPSHOT" --include="pom.xml" . \
    | grep -v "target/" \
    | grep -v "\.git/" \
    || true)
  if [[ -n "$remaining" ]]; then
    echo "::error::SNAPSHOT references still found in pom.xml files after release version update:"
    echo "$remaining"
    exit 1
  fi
}

generate_release_notes() {
  echo "Generating release notes..."
  local previous_tag=""
  previous_tag=$(git tag --merged HEAD --sort=-creatordate \
    | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' \
    | grep -vx "$TAG_NAME" \
    | head -n 1 || true)

  if [[ -n "$previous_tag" ]]; then
    echo "Getting closed issues since $previous_tag"
    local previous_date
    previous_date=$(git log -1 --format=%aI "$previous_tag")
    gh issue list --state closed --search "closed:>$previous_date" \
      --json number,title --jq '.[] | "- #\(.number): \(.title)"' > release_notes.md || true
    if [[ ! -s release_notes.md ]]; then
      echo "No closed issues found since $previous_tag" > release_notes.md
    fi
  else
    echo "Initial release" > release_notes.md
  fi
  cat release_notes.md
}

collect_release_artifacts() {
  rm -rf target/release-artifacts
  mkdir -p target/release-artifacts

  find . -path './target/release-artifacts' -prune -o \
    -path '*/target/*.jar' -type f \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    ! -name 'original-*' \
    -exec cp {} target/release-artifacts/ \;

  for f in target/taxonomy-sbom.json target/taxonomy-sbom.xml target/taxonomy-vex.json; do
    if [[ -f "$f" ]]; then
      cp "$f" target/release-artifacts/
    else
      echo "::warning::$f not found"
    fi
  done

  echo "Release artifacts:"
  find target/release-artifacts -type f -maxdepth 1 -print | sort
}

push_release_commit_to_main() {
  local commit_sha=$1
  local temp_branch="release-temp-${RELEASE_VERSION}"
  local temp_pushed=false

  trap '[[ $temp_pushed == true ]] && git push origin ":refs/heads/'"${temp_branch}"'" || true' RETURN
  git push origin ":refs/heads/${temp_branch}" || true
  git push origin "${commit_sha}:refs/heads/${temp_branch}"
  temp_pushed=true

  gh api "repos/${GITHUB_REPOSITORY}/git/refs/heads/main" \
    --method PATCH \
    -f sha="$commit_sha"
}

create_tag_ref() {
  local commit_sha=$1
  local tag_sha
  tag_sha=$(gh api "repos/${GITHUB_REPOSITORY}/git/tags" \
    --method POST \
    -f tag="$TAG_NAME" \
    -f message="Release version $RELEASE_VERSION" \
    -f object="$commit_sha" \
    -f type="commit" \
    --jq '.sha')
  gh api "repos/${GITHUB_REPOSITORY}/git/refs" \
    --method POST \
    -f ref="refs/tags/${TAG_NAME}" \
    -f sha="$tag_sha"
}

create_maintenance_branch_if_missing() {
  local commit_sha=$1
  if git ls-remote --exit-code --heads origin "${MAINTENANCE_BRANCH}" >/dev/null 2>&1; then
    echo "Maintenance branch ${MAINTENANCE_BRANCH} already exists; skipping"
  else
    gh api "repos/${GITHUB_REPOSITORY}/git/refs" \
      --method POST \
      -f ref="refs/heads/${MAINTENANCE_BRANCH}" \
      -f sha="$commit_sha"
  fi
}

git config user.name 'github-actions[bot]'
git config user.email 'github-actions[bot]@users.noreply.github.com'

echo "Release version: $RELEASE_VERSION"
echo "Current version: $CURRENT_VERSION"
echo "Next development version: $NEXT_VERSION"
echo "Dry run: $DRY_RUN"
echo "Skip tests: $SKIP_TESTS"

verify_metadata "$CURRENT_VERSION" false
mvn -B validate

git fetch origin --tags --force
TAG_EXISTS=false
if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
  TAG_EXISTS=true
fi

RELEASE_STATE=$(gh release view "$TAG_NAME" --json isDraft --jq 'if .isDraft then "draft" else "published" end' 2>/dev/null || true)
if [[ -n "$RELEASE_STATE" && "$TAG_EXISTS" != "true" ]]; then
  echo "::error::A GitHub release exists for ${TAG_NAME}, but its tag is missing"
  exit 1
fi

if [[ -n "$RELEASE_STATE" ]]; then
  STATE=$RELEASE_STATE
elif [[ "$TAG_EXISTS" == "true" ]]; then
  STATE=tagged
else
  STATE=new
fi

echo "Release state: $STATE"

if [[ "$STATE" == "new" ]]; then
  mvn -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
  python3 "$METADATA_HELPER" "$RELEASE_VERSION" --release
  verify_metadata "$RELEASE_VERSION" true
  ensure_no_snapshot_poms
  git add pom.xml */pom.xml CITATION.cff .zenodo.json codemeta.json
  git commit -m "Release version $RELEASE_VERSION"
else
  git checkout --detach "$TAG_NAME"
  verify_metadata "$RELEASE_VERSION" true
fi

if [[ "$SKIP_TESTS" == "true" ]]; then
  mvn -B clean package -DskipTests
else
  mvn -B clean verify
fi

python3 "$VEX_HELPER"
collect_release_artifacts

generate_release_notes

PUBLISHED_THIS_RUN=false
if [[ "$DRY_RUN" != "true" && "$STATE" == "new" ]]; then
  RELEASE_COMMIT=$(git rev-parse HEAD)
  push_release_commit_to_main "$RELEASE_COMMIT"
  create_tag_ref "$RELEASE_COMMIT"
  create_maintenance_branch_if_missing "$RELEASE_COMMIT"
  STATE=tagged
fi

if [[ "$DRY_RUN" != "true" && "$STATE" == "tagged" ]]; then
  gh release create "$TAG_NAME" \
    --verify-tag \
    --draft \
    --title "Release $RELEASE_VERSION" \
    --notes-file release_notes.md \
    --generate-notes
  STATE=draft
fi

if [[ "$DRY_RUN" != "true" && "$STATE" == "draft" ]]; then
  mapfile -d '' ARTIFACTS < <(find target/release-artifacts -type f -print0)
  if [[ ${#ARTIFACTS[@]} -gt 0 ]]; then
    gh release upload "$TAG_NAME" "${ARTIFACTS[@]}" --clobber
  else
    echo "::warning::No release artifacts found to upload"
  fi
  gh release edit "$TAG_NAME" --draft=false --latest
  STATE=published
  PUBLISHED_THIS_RUN=true
fi

if [[ "$DRY_RUN" != "true" ]]; then
  IS_DRAFT=$(gh release view "$TAG_NAME" --json isDraft --jq '.isDraft')
  test "$IS_DRAFT" = false
fi

if [[ "$DRY_RUN" != "true" && "$PUBLISHED_THIS_RUN" == "true" ]]; then
  if [[ -n "$RENDER_DEPLOY_HOOK_URL" ]]; then
    curl -fSL --retry 3 "$RENDER_DEPLOY_HOOK_URL"
    echo "Render deployment triggered."
  else
    echo "::notice::RENDER_DEPLOY_HOOK_URL secret not set - skipping Render deploy"
  fi
fi

mvn -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
python3 "$METADATA_HELPER" "$NEXT_VERSION"
verify_metadata "$NEXT_VERSION" false

NEXT_BRANCH="release/prepare-next-${NEXT_VERSION}"
git switch -C "$NEXT_BRANCH"
git add pom.xml */pom.xml CITATION.cff .zenodo.json codemeta.json
if git diff --cached --quiet; then
  echo "No next-development version changes to commit"
else
  git commit -m "Prepare next development version $NEXT_VERSION"
fi

if [[ "$DRY_RUN" != "true" ]]; then
  REMOTE_SHA=$(git ls-remote --heads origin "refs/heads/${NEXT_BRANCH}" | awk '{print $1}')
  if [[ -n "$REMOTE_SHA" ]]; then
    git push --force-with-lease="refs/heads/${NEXT_BRANCH}:${REMOTE_SHA}" origin "HEAD:refs/heads/${NEXT_BRANCH}"
  else
    git push origin "HEAD:refs/heads/${NEXT_BRANCH}"
  fi

  cat > /tmp/next-development-pr.md <<EOF
Automated follow-up after release ${RELEASE_VERSION}.

## Changes
- Bump all Maven modules to ${NEXT_VERSION}
- Update CITATION.cff to ${NEXT_VERSION}
- Update .zenodo.json to ${NEXT_VERSION}
- Update codemeta.json to ${NEXT_VERSION}
- Remove release-only date metadata from the development snapshot

## Release notes

EOF
  cat release_notes.md >> /tmp/next-development-pr.md

  EXISTING_PR=$(gh pr list --base main --head "$NEXT_BRANCH" \
    --state open --json number --jq '.[0].number // empty')
  if [[ -n "$EXISTING_PR" ]]; then
    gh pr edit "$EXISTING_PR" \
      --title "Prepare next development version ${NEXT_VERSION}" \
      --body-file /tmp/next-development-pr.md
  else
    gh pr create \
      --title "Prepare next development version ${NEXT_VERSION}" \
      --body-file /tmp/next-development-pr.md \
      --base main \
      --head "$NEXT_BRANCH"
  fi
else
  echo "Dry run completed; no remote refs, release, deploy hook or PR were changed."
fi
