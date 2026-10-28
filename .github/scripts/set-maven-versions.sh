#!/usr/bin/env bash
set -euo pipefail

version=${1:?A target project version is required}

./mvnw -B versions:set \
  -DprocessAllModules=true \
  -DnewVersion="$version" \
  -DgenerateBackupPoms=false

# Inventory plugins are independently built and are not modules of the root reactor.
# Use the same Versions Plugin version as the root release transition.
for plugin in taxonomy-leanix-plugin taxonomy-alfabet-plugin; do
  ./mvnw -B -f "plugins/$plugin/pom.xml" \
    org.codehaus.mojo:versions-maven-plugin:2.21.0:set \
    -DprocessAllModules=true \
    -DnewVersion="$version" \
    -DgenerateBackupPoms=false
done
