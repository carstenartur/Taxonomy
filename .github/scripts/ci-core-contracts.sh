#!/usr/bin/env bash
set -euo pipefail

./mvnw -B -pl taxonomy-tooling -am package -DskipTests
tooling_jar=$(find taxonomy-tooling/target -maxdepth 1 -type f \
  -name 'taxonomy-tooling-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' \
  | head -n 1)
if [[ -z "$tooling_jar" || ! -f "$tooling_jar" ]]; then
  echo '::error::Java release tooling jar was not produced'
  exit 1
fi

# Regression callbacks and repository guard commands are owned by root Maven:
# PythonContractTest, JavascriptContractTest and CoreDeliveryContractsTest.
# No separate test runner may diverge from that JUnit inventory.

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
