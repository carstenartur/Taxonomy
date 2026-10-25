#!/usr/bin/env bash
set -euo pipefail

version=${1:?A target project version is required}

./mvnw -B versions:set \
  -DprocessAllModules=true \
  -DnewVersion="$version" \
  -DgenerateBackupPoms=false

# LeanIX is independently built and is not a module of the root reactor.
# Use the same Versions Plugin version as the root release transition.
./mvnw -B -f plugins/taxonomy-leanix-plugin/pom.xml \
  org.codehaus.mojo:versions-maven-plugin:2.21.0:set \
  -DprocessAllModules=true \
  -DnewVersion="$version" \
  -DgenerateBackupPoms=false
