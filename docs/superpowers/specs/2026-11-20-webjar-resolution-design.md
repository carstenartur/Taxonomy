# Deterministic WebJar resolution

## Intent and evidence

The Java CodeQL build for main commit `77eaa2f` failed twice before analysis:
35 direct transitive WebJar version ranges had no resolvable versions. A
successful CodeQL run on the identical tree and the successful main Security
build used the same Maven cache key and archive size. The logs do not establish
the underlying network or cache mechanism.

The verified PR core-test classpath contains 52 NPM WebJars. Its exact artifact
and version map matches the successful local application SBOM. Of 48 active
range targets, two already have managed versions and 46 remain unmanaged;
eleven of the latter are deeper dependencies. Four optional dependencies are
not selected.

## Approach

Extend the existing root dependency management with the 46 already tested
versions. This removes version-discovery metadata from the selected WebJar
closure without upgrading, excluding, or adding dependencies. Existing pins,
optional semantics, runtime assets, security thresholds, and workflow gates
remain unchanged.

Forced metadata refresh or a retry can recover some transient failures but does
not make version selection deterministic. Excluding transitives changes the
packaged dependency and evidence inventory. Neither is the chosen correction.

## Acceptance

A real Maven fixture must fail before the change and resolve after it against
a repository containing exact artifacts and POMs but no WebJar version-list
metadata. The selected artifact/version set must remain identical. A focused
regression must guard the active transitive closure, including deeper ranges,
while respecting optional dependencies. Normal packaging and all applicable
PR checks must pass before merge; main and automatic Delivery are verified
afterward.
