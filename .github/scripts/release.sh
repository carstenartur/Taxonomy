#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${METADATA_HELPER:?METADATA_HELPER is required}"
: "${VERSION_STATE_HELPER:?VERSION_STATE_HELPER is required}"
: "${VEX_HELPER:?VEX_HELPER is required}"
: "${RELEASE_NOTES_VALIDATOR:?RELEASE_NOTES_VALIDATOR is required}"

NEXT_VERSION_INPUT=${NEXT_VERSION_INPUT:-}
SKIP_TESTS=${SKIP_TESTS:-false}
DRY_RUN=${DRY_RUN:-false}
SOURCE_BRANCH=${SOURCE_BRANCH:-main}
RENDER_DEPLOY_HOOK_URL=${RENDER_DEPLOY_HOOK_URL:-}
DEFER_RELEASE_PUBLICATION=${DEFER_RELEASE_PUBLICATION:-false}

TAG_NAME="v${RELEASE_VERSION}"
MAJOR_MINOR=$(echo "${RELEASE_VERSION}" | sed 's/\.[^.]*$//')
MAINTENANCE_BRANCH="maintenance/${MAJOR_MINOR}.x"
TEMP_BRANCH="release-temp-${RELEASE_VERSION}"
PROTECTED_MAIN_ADVANCE_WORKFLOW="protected-release-main-advance.yml"
RELEASE_NOTES_OUTPUT="${RUNNER_TEMP:-target}/taxonomy-${RELEASE_VERSION}-release-notes.md"

fail() {
  echo "::error::$*"
  exit 1
}

run_maven_release_check() {
  local state=$1
  local profiles=$2
  shift 2
  ./mvnw -B "-P${profiles}" "$@" \
    -DreleaseVersion="$RELEASE_VERSION" \
    -DnextDevelopmentVersion="$NEXT_VERSION" \
    -DreleaseCheckCurrentState="$state"
}

stage_version_metadata() {
  local -a tracked_poms=()
  mapfile -d '' tracked_poms < <(
    git ls-files -z -- 'pom.xml' ':(glob)**/pom.xml'
  )
  if [[ ${#tracked_poms[@]} -eq 0 ]]; then
    fail "No tracked Maven POMs found for release version staging"
  fi
  # The checkout was required to be clean before versions:set. Adding every tracked
  # POM therefore stages only files changed by the version transition, including
  # nested reactor modules, while excluding generated and untracked example POMs.
  git add -- "${tracked_poms[@]}" CITATION.cff CITATION.md .zenodo.json codemeta.json
  if [[ -f deploy/helm/taxonomy/Chart.yaml ]]; then
    git add -- deploy/helm/taxonomy/Chart.yaml
  fi
}

if ! [[ "$RELEASE_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  fail "release_version must use X.Y.Z without a leading v"
fi
if [[ "$SOURCE_BRANCH" != "main" && "$DRY_RUN" != "true" ]]; then
  fail "Real releases must be dispatched from main, not $SOURCE_BRANCH"
fi
if [[ "$SKIP_TESTS" == "true" && "$DRY_RUN" != "true" ]]; then
  fail "skip_tests is allowed only for a dry run; published releases require the canonical verification suite"
fi
if [[ "$DEFER_RELEASE_PUBLICATION" != "true" && "$DEFER_RELEASE_PUBLICATION" != "false" ]]; then
  fail "DEFER_RELEASE_PUBLICATION must be true or false"
fi

if [[ -n "$NEXT_VERSION_INPUT" ]]; then
  NEXT_VERSION=$NEXT_VERSION_INPUT
else
  IFS='.' read -r MAJOR MINOR PATCH <<< "$RELEASE_VERSION"
  NEXT_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))-SNAPSHOT"
fi
if ! [[ "$NEXT_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+-SNAPSHOT$ ]]; then
  fail "next_development_version must use X.Y.Z-SNAPSHOT"
fi
RELEASE_VERSION="$RELEASE_VERSION" NEXT_VERSION="$NEXT_VERSION" python3 - <<'PY'
import os
release = tuple(map(int, os.environ['RELEASE_VERSION'].split('.')))
next_version = tuple(map(int, os.environ['NEXT_VERSION'].removesuffix('-SNAPSHOT').split('.')))
if next_version <= release:
    raise SystemExit(
        f"next development version {os.environ['NEXT_VERSION']} must be newer than "
        f"release {os.environ['RELEASE_VERSION']}"
    )
PY

materialize_reviewed_release_notes() {
  RELEASE_VERSION="$RELEASE_VERSION" \
    RELEASE_NOTES_COMMIT="$RELEASE_COMMIT" \
    RELEASE_NOTES_FILE="$RELEASE_NOTES_OUTPUT" \
    "$RELEASE_NOTES_VALIDATOR"
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

  for file in target/taxonomy-sbom.json target/taxonomy-sbom.xml target/taxonomy-vex.json; do
    if [[ -f "$file" ]]; then
      cp "$file" target/release-artifacts/
    else
      echo "::warning::$file not found"
    fi
  done
  find target/release-artifacts -maxdepth 1 -type f -print | sort
}

materialize_commit() {
  local commit_sha=$1
  git push origin ":refs/heads/${TEMP_BRANCH}" >/dev/null 2>&1 || true
  git push origin "${commit_sha}:refs/heads/${TEMP_BRANCH}"
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

remote_main_version() {
  git show origin/main:pom.xml | python3 -c '
import sys, xml.etree.ElementTree as ET
root = ET.fromstring(sys.stdin.read())
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
value = root.findtext("m:version", namespaces=ns)
if not value:
    raise SystemExit("origin/main pom.xml has no project version")
print(value.strip())
'
}

advance_main_via_protected_pr() {
  local next_commit=$1
  local run_title="Advance main to ${NEXT_VERSION} (${next_commit})"
  local advance_run_id=""

  gh workflow run "$PROTECTED_MAIN_ADVANCE_WORKFLOW" \
    --ref main \
    -f release_version="$RELEASE_VERSION" \
    -f next_version="$NEXT_VERSION" \
    -f temp_branch="$TEMP_BRANCH" \
    -f expected_sha="$next_commit" \
    -f expected_base_sha="$ORIGINAL_MAIN"

  for _ in $(seq 1 60); do
    advance_run_id=$(gh run list \
      --workflow "$PROTECTED_MAIN_ADVANCE_WORKFLOW" \
      --event workflow_dispatch \
      --limit 20 \
      --json databaseId,displayTitle,createdAt \
      --jq ".[] | select(.displayTitle == \"$run_title\") | .databaseId" \
      | head -n 1)
    if [[ -n "$advance_run_id" ]]; then break; fi
    sleep 5
  done
  if [[ -z "$advance_run_id" ]]; then
    fail "Could not locate protected-main advance workflow for $next_commit"
  fi

  gh run watch "$advance_run_id" --exit-status

  git fetch origin "refs/heads/main:refs/remotes/origin/main" --force
  if ! git merge-base --is-ancestor "$next_commit" origin/main; then
    fail "Protected-main workflow completed without merging $next_commit into main"
  fi
  if [[ "$(remote_main_version)" != "$NEXT_VERSION" ]]; then
    fail "origin/main does not expose development version $NEXT_VERSION"
  fi
  git checkout --detach origin/main
  echo "main advanced through protected PR from ${RELEASE_VERSION}-SNAPSHOT to $NEXT_VERSION."
}

git config user.name 'github-actions[bot]'
git config user.email 'github-actions[bot]@users.noreply.github.com'

git fetch origin main --tags --force
ORIGINAL_HEAD=$(git rev-parse HEAD)
ORIGINAL_MAIN=$(git rev-parse origin/main)
if [[ "$DRY_RUN" != "true" && "$ORIGINAL_HEAD" != "$ORIGINAL_MAIN" ]]; then
  fail "Checked-out commit $ORIGINAL_HEAD is stale; origin/main is $ORIGINAL_MAIN"
fi
if [[ -n "$(git status --porcelain)" ]]; then
  fail "Release checkout is not clean before version preparation"
fi

CURRENT_VERSION=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
TAG_EXISTS=false
if git rev-parse "${TAG_NAME}^{commit}" >/dev/null 2>&1; then
  TAG_EXISTS=true
fi
RELEASE_STATE=$(gh release view "$TAG_NAME" --json isDraft --jq 'if .isDraft then "draft" else "published" end' 2>/dev/null || true)
if [[ -n "$RELEASE_STATE" && "$TAG_EXISTS" != "true" ]]; then
  fail "A GitHub release exists for ${TAG_NAME}, but its tag is missing"
fi
if [[ -n "$RELEASE_STATE" ]]; then
  STATE=$RELEASE_STATE
elif [[ "$TAG_EXISTS" == "true" ]]; then
  STATE=tagged
else
  STATE=new
fi

MAIN_ALREADY_ADVANCED=false
RELEASE_CHECK_STATE=development
if [[ "$CURRENT_VERSION" == "$NEXT_VERSION" && "$TAG_EXISTS" == "true" ]]; then
  MAIN_ALREADY_ADVANCED=true
  RELEASE_CHECK_STATE=advanced
  python3 "$VERSION_STATE_HELPER" --mode development --expected-version "$NEXT_VERSION"
elif [[ "$CURRENT_VERSION" == "${RELEASE_VERSION}-SNAPSHOT" ]]; then
  python3 "$VERSION_STATE_HELPER" --mode development \
    --expected-version "${RELEASE_VERSION}-SNAPSHOT"
else
  fail "Current version $CURRENT_VERSION is neither ${RELEASE_VERSION}-SNAPSHOT nor finalized $NEXT_VERSION"
fi

echo "Release version: $RELEASE_VERSION"
echo "Current version: $CURRENT_VERSION"
echo "Next development version: $NEXT_VERSION"
echo "Release state: $STATE"
echo "Main already advanced: $MAIN_ALREADY_ADVANCED"
echo "Defer release publication: $DEFER_RELEASE_PUBLICATION"
echo "Dry run: $DRY_RUN"

run_maven_release_check "$RELEASE_CHECK_STATE" release-check validate

if [[ "$STATE" == "new" ]]; then
  ./mvnw -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
  python3 "$METADATA_HELPER" "$RELEASE_VERSION" --release
  python3 "$VERSION_STATE_HELPER" --mode release --expected-version "$RELEASE_VERSION"
  # Validate the actual release-state reactor before committing it. The clean-check
  # is disabled only for this deliberate, uncommitted transition; the subsequent
  # canonical release verification runs from the clean immutable commit.
  run_maven_release_check release release-check validate \
    -DreleaseCheckRequireClean=false
  stage_version_metadata
  git commit -m "Release version $RELEASE_VERSION"
  RELEASE_COMMIT=$(git rev-parse HEAD)
else
  RELEASE_COMMIT=$(git rev-parse "${TAG_NAME}^{commit}")
  git checkout --detach "$RELEASE_COMMIT"
  python3 "$VERSION_STATE_HELPER" --mode release \
    --expected-version "$RELEASE_VERSION" --tag "$TAG_NAME"
fi

materialize_reviewed_release_notes

if [[ "$SKIP_TESTS" == "true" ]]; then
  run_maven_release_check release release-check clean package -DskipTests
else
  run_maven_release_check release release-check,ci clean verify
fi
python3 "$VEX_HELPER"
collect_release_artifacts

PUBLISHED_THIS_RUN=false
if [[ "$DRY_RUN" != "true" && "$STATE" == "new" ]]; then
  materialize_commit "$RELEASE_COMMIT"
  create_tag_ref "$RELEASE_COMMIT"
  create_maintenance_branch_if_missing "$RELEASE_COMMIT"
  STATE=tagged
fi

# Prepare the next snapshot before creating or publishing the GitHub release.
# Protected main is advanced only through a PR whose exact head SHA has passed
# the canonical Maven verification. The immutable release commit remains tagged.
if [[ "$MAIN_ALREADY_ADVANCED" == "true" ]]; then
  git checkout --detach "$ORIGINAL_MAIN"
  python3 "$VERSION_STATE_HELPER" --mode development --expected-version "$NEXT_VERSION"
else
  git checkout --detach "$RELEASE_COMMIT"
  ./mvnw -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
  python3 "$METADATA_HELPER" "$NEXT_VERSION"
  python3 "$VERSION_STATE_HELPER" --mode development --expected-version "$NEXT_VERSION"
  stage_version_metadata
  git commit -m "Prepare next development version $NEXT_VERSION"
  NEXT_COMMIT=$(git rev-parse HEAD)

  if [[ "$DRY_RUN" != "true" ]]; then
    git fetch origin main --force
    REMOTE_MAIN=$(git rev-parse origin/main)
    if [[ "$REMOTE_MAIN" != "$ORIGINAL_MAIN" ]]; then
      fail "origin/main moved from $ORIGINAL_MAIN to $REMOTE_MAIN during the release; refusing to continue"
    fi
    git merge-base --is-ancestor "$ORIGINAL_MAIN" "$NEXT_COMMIT" \
      || fail "Next-development commit is not descended from the original main"
    git push origin "${NEXT_COMMIT}:refs/heads/${TEMP_BRANCH}" --force
    advance_main_via_protected_pr "$NEXT_COMMIT"
  fi
fi

if [[ "$DRY_RUN" != "true" && "$STATE" == "tagged" ]]; then
  materialize_reviewed_release_notes
  gh release create "$TAG_NAME" \
    --verify-tag \
    --draft \
    --title "Release $RELEASE_VERSION" \
    --notes-file "$RELEASE_NOTES_OUTPUT"
  STATE=draft
fi

if [[ "$DRY_RUN" != "true" && "$STATE" == "draft" ]]; then
  mapfile -d '' ARTIFACTS < <(find target/release-artifacts -type f -print0)
  if [[ ${#ARTIFACTS[@]} -gt 0 ]]; then
    gh release upload "$TAG_NAME" "${ARTIFACTS[@]}" --clobber
  else
    echo "::warning::No release artifacts found to upload"
  fi
  if [[ "$DEFER_RELEASE_PUBLICATION" == "true" ]]; then
    echo "Release $TAG_NAME remains a draft until downstream artifacts and final CI succeed."
  else
    materialize_reviewed_release_notes
    gh release edit "$TAG_NAME" \
      --notes-file "$RELEASE_NOTES_OUTPUT" \
      --draft=false \
      --latest
    STATE=published
    PUBLISHED_THIS_RUN=true
  fi
fi
if [[ "$DRY_RUN" != "true" ]]; then
  RELEASE_IS_DRAFT=$(gh release view "$TAG_NAME" --json isDraft --jq '.isDraft')
  if [[ "$STATE" == "draft" ]]; then
    test "$RELEASE_IS_DRAFT" = true
  else
    test "$RELEASE_IS_DRAFT" = false
  fi
fi

if [[ "$DRY_RUN" != "true" && "$PUBLISHED_THIS_RUN" == "true" ]]; then
  if [[ -n "$RENDER_DEPLOY_HOOK_URL" ]]; then
    curl -fSL --retry 3 "$RENDER_DEPLOY_HOOK_URL"
    echo "Render deployment triggered."
  else
    echo "::notice::RENDER_DEPLOY_HOOK_URL secret not set - skipping Render deploy"
  fi
fi

if [[ "$DRY_RUN" == "true" ]]; then
  echo "Dry run completed; no remote refs, release or deploy hook were changed."
fi
