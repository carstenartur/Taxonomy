# ONNX reference review follow-up

This bounded follow-up implements the two review findings that the owner explicitly
moved out of PR #1141 when merging it as `28064803b32058ab2b1470e22d85d1945437abb9`.
It changes only test-side evaluation and its regression tests, not production
search, model settings, reference questions, dependencies or CI thresholds.

## Node readiness

The evaluator accepts READY, PARTIAL and INDEXING_RELATIONS, matching the states
in which LocalOnnxIndexInitializer permits node retrieval. All four boolean guards
(enabled, available, modelAvailable, semanticReady) and a positive integral node
count remain required. Missing/unknown states and invalid guards remain rejected.
The initial index state is retained in the report; usable nodes do not imply that
relation indexing succeeded.

## Catalogue evidence

Report schema 2 uses the existing `TaxonomyDataFingerprint.sha256` implementation
for `catalogueSha256`, including its version framing, level, analysisRole and
inherited parent identity. The same bounded, validated DTO hierarchy is checked
before and after retrieval. No second canonical hash algorithm is introduced.
`catalogueFingerprintAlgorithm` names the implementation, and the recorded source
revision identifies its version.

The former six-field text projection is retained separately as
`catalogueProjectionSha256`, including German text checks. Both digests must remain
unchanged. Historical schema-1 reports are not rewritten or relabelled. These are
catalogue snapshot checks, not a new claim to hash the actual Lucene index or to
provide transactional consistency during concurrent catalogue edits.

## Regression verification

The four original evaluator/test files and the canonical DTO/fingerprint sources
were checked against the merged base by Git blob SHA before modification.
The existing eight focused JUnit tests passed before changes. New readiness tests
failed against the former READY-only implementation; new HTTP-contract tests
exposed missed level, role and inherited-parent mutations and the noncanonical
reported hash. After correction, all 21 focused JUnit 6.0.3 tests pass with zero
skips. They also cover incomplete flags, malformed canonical fields, duplicate
codes, translation changes and harmless hierarchy reordering.

The HTTP fixtures are synthetic evaluator-contract tests, not ONNX inference or
quality measurements. Compilation uses Java 21 with `-Xlint:all -Werror` and the
existing Jackson 3.1.6 dependencies. No project dependency was added.
The full `./mvnw -B -ntp verify -Ponnx` attempt failed before execution because the
pinned Maven distribution could not be downloaded in this environment. Exact-head
CI, including the existing real ONNX integration suite, remains required.
