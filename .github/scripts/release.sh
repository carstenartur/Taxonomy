#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${METADATA_HELPER:?METADATA_HELPER is required}"
: "${TOOLING_JAR:?TOOLING_JAR is required}"
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

run_release_plan_check() {
  local state=$1
  local require_clean=${2:-true}
  local current_version
  current_version=$(./mvnw -q -DforceStdout help:evaluate \
    -Dexpression=project.version)
  java -jar "$TOOLING_JAR" check-release-plan \
    --root . \
    --current-version "$current_version" \
    --release-version "$RELEASE_VERSION" \
    --next-development-version "$NEXT_VERSION" \
    --state "$state" \
    --require-clean "$require_clean"
}

check_version_state() {
  java -jar "$TOOLING_JAR" check-version-state --root . "$@"
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
java -jar "$TOOLING_JAR" compare-versions \
  --release-version "$RELEASE_VERSION" \
  --next-development-version "$NEXT_VERSION"

materialize_release_notes() {
  RELEASE_VERSION="$RELEASE_VERSION" \
    RELEASE_NOTES_COMMIT="$RELEASE_COMMIT" \
    RELEASE_NOTES_FILE=release_notes.md \
    "$RELEASE_NOTES_VALIDATOR"
}

restore_release_notes_checkout() {
  # The reviewed notes may deliberately differ from the already-advanced main
  # checkout. Restore the current HEAD after GitHub has consumed the immutable
  # file so the workflow can subsequently checkout the release tag safely.
  git restore --source=HEAD --worktree -- release_notes.md
}

validate_release_notes() {
  local notes_file=release_notes.md
  if [[ "$(git rev-parse HEAD)" != "$RELEASE_COMMIT" ]]; then
    fail "Reviewed release notes must be validated at release commit $RELEASE_COMMIT"
  fi
  git ls-files --error-unmatch "$notes_file" >/dev/null \
    || fail "$notes_file must be tracked by the exact release commit"
  git diff --quiet HEAD -- "$notes_file" \
    || fail "$notes_file differs from the exact release commit"
  materialize_release_notes
}

collect_release_artifacts() {
  rm -rf target/release-artifacts
  mkdir -p target/release-artifacts
  find . -path './target/release-artifacts' -prune -o \
    -path '*/target/*.jar' -type f \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    ! -name 'original-*' \
    ! -name 'taxonomy-tooling-*.jar' \
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
  git show origin/main:pom.xml \
    | java -jar "$TOOLING_JAR" read-pom-version --stdin
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
  check_version_state --mode development --expected-version "$NEXT_VERSION"
elif [[ "$CURRENT_VERSION" == "${RELEASE_VERSION}-SNAPSHOT" ]]; then
  check_version_state --mode development \
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

run_release_plan_check "$RELEASE_CHECK_STATE" true

if [[ "$STATE" == "new" ]]; then
  ./mvnw -B versions:set -DnewVersion="$RELEASE_VERSION" -DgenerateBackupPoms=false
  python3 "$METADATA_HELPER" "$RELEASE_VERSION" --release
  check_version_state --mode release --expected-version "$RELEASE_VERSION"
  # Validate the actual release-state reactor before committing it. The clean-check
  # is disabled only for this deliberate, uncommitted transition; the subsequent
  # canonical release verification runs from the clean immutable commit.
  run_release_plan_check release false
  stage_version_metadata
  git commit -m "Release version $RELEASE_VERSION"
  RELEASE_COMMIT=$(git rev-parse HEAD)
else
  RELEASE_COMMIT=$(git rev-parse "${TAG_NAME}^{commit}")
  git checkout --detach "$RELEASE_COMMIT"
  check_version_state --mode release \
    --expected-version "$RELEASE_VERSION" --tag "$TAG_NAME"
fi

validate_release_notes

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
  check_version_state --mode development --expected-version "$NEXT_VERSION"
else
  git checkout --detach "$RELEASE_COMMIT"
  ./mvnw -B versions:set -DnewVersion="$NEXT_VERSION" -DgenerateBackupPoms=false
  python3 "$METADATA_HELPER" "$NEXT_VERSION"
  check_version_state --mode development --expected-version "$NEXT_VERSION"
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
  materialize_release_notes
  gh release create "$TAG_NAME" \
    --verify-tag \
    --draft \
    --title "Release $RELEASE_VERSION" \
    --notes-file release_notes.md
  restore_release_notes_checkout
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
    materialize_release_notes
    gh release edit "$TAG_NAME" \
      --notes-file release_notes.md \
      --draft=false \
      --latest
    restore_release_notes_checkout
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
