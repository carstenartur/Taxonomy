# ONNX test-process model provisioning

## Failure observed on 28 September 2026

The local-ONNX workflow run `36426566210`, job `108941866682`, was associated
with PR head `65b8cea61c7b933c198439b3bcfe3a916a4c75ea`. Its artifact
`10974147507` has SHA-256
`4335c5efc57e436120eb1b681fe669c496ab2daff451e739a117a6375ab49461`.
The original workflow checked out a synthetic merge ref; the head association
must not be mistaken for the exact source revision inside its application JAR.

The initialize phase successfully downloaded and verified the pinned
`BAAI/bge-small-en-v1.5` model. The failure was later, in Surefire:
`OnnxEmbeddingServiceTest` had four errors and `OnnxRestEndpointTest` had five
failures because no local model directory reached their Spring test processes.
Downloads were correctly forbidden there. The application-module summary was
2102 tests, five failures, four errors, zero skipped. Failsafe and the new real
retrieval evaluation were not reached. The eight new reference-contract unit
tests passed, which is not evidence that inference ran.

## Correction

The application module now extends the existing `onnx` profile with both test
plugin configurations. Surefire and Failsafe receive the same absolute model
path already used by the `local-onnx-model` profile, and inference-time downloads
remain disabled. `./mvnw -B verify -Ponnx` works without requiring an undocumented
second profile. The existing root initialize-phase provisioning is unchanged.

Failsafe also explicitly forwards `${runOnnxTests}` into its forked JVM.
A Maven project property alone does not enable an `@EnabledIfSystemProperty`
condition. This was a latent skip risk after the earlier Surefire failure, not
an observed successful execution of the integration tests.

The supplemental workflow checks out the requested PR head, falling back to the
manual-dispatch SHA, to make measurement provenance unambiguous. Canonical CI
keeps its own merge verification. Existing `check-junit-reports` tooling requires
positive, failure-free and skip-free evidence from the two embedding/REST suites,
the nine-test pipeline suite (including real reference evaluation) and the browser
suite. Both Surefire and Failsafe reports are retained even on failure. POM and
model-provisioning changes now trigger this workflow as well.

No reference answers, ranking thresholds, production behavior, model version,
timeouts, test selectors or read-only permissions are relaxed.

## Verification boundary

`OnnxProfileWiringTest` adds four JUnit build-policy tests. Its dependency-free
contract failed on the original configuration and passed after the change.
Ten supplementary Java 21 wiring checks, including eight deliberate broken
configurations, passed. XML/YAML parsing, original-POM reconstruction and
`git diff --check` also passed.

These checks do not execute Maven, JUnit or ONNX inference. The local recovered
subset has no Maven wrapper; the attempted full ONNX command stopped before
execution. The corrected exact-head workflow must still establish real model,
index, REST and browser success. No successful ONNX quality measurement is
claimed from the failed original run or these structural checks.
