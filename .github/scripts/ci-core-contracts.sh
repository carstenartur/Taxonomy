#!/usr/bin/env bash
set -euo pipefail

./mvnw -B -pl taxonomy-tooling -am package -DskipTests
tooling_jar=$(find taxonomy-tooling/target -maxdepth 1 -type f \
  -name 'taxonomy-tooling-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
  | head -n 1)
[[ -n "$tooling_jar" && -f "$tooling_jar" ]] || {
  echo '::error::Java release tooling jar was not produced'
  exit 1
}

python3 .github/scripts/test-generate-quality-site.py
python3 .github/scripts/test-verify-quality-publication.py
python3 .github/scripts/test-verify-deployment.py
node .github/scripts/test-taxonomy-base-path.mjs
bash -n .github/scripts/release.sh
bash -n .github/scripts/install-helm.sh
bash -n .github/scripts/download-embedding-model.sh
bash -n deploy/helm/taxonomy/verify.sh
python3 .github/scripts/check-release-delivery-contract.py
python3 .github/scripts/check-delivery-hardening.py
python3 .github/scripts/check-observability-performance-scope.py

current_version=$(./mvnw -q -DforceStdout help:evaluate -Dexpression=project.version)
if [[ "$current_version" == *-SNAPSHOT ]]; then
  release_version=${current_version%-SNAPSHOT}
  IFS='.' read -r major minor patch <<< "$release_version"
  java -jar "$tooling_jar" check-release-plan \
    --root . \
    --current-version "$current_version" \
    --release-version "$release_version" \
    --next-development-version "${major}.${minor}.$((patch + 1))-SNAPSHOT" \
    --state development \
    --require-clean true
fi
