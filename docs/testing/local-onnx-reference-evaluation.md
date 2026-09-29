# Real local ONNX reference evaluation (#927, follow-up to #1140)

Run the existing Maven suite with `./mvnw -B verify -Ponnx`. The supplemental
`local-onnx-reference.yml` workflow invokes that same command on affected pull
requests (including a stacked PR) or explicit dispatch. The canonical CI command
and existing suite selectors remain unchanged. No credentials or paid inference
are requested. Java 21, Docker and the existing Maven model provisioning are needed.

## What runs

LocalOnnxPipelineIT now mounts the model provisioned under
`models/bge-small-en-v1.5` read-only, explicitly denies runtime model downloads,
and fixes the query prefix. It no longer downloads an unpinned `resolve/main`
model inside the application container. Maven retains its existing pinned model
revision; an explicit local directory override is identified by actual file hashes,
not asserted to be an independently verified upstream revision.

Before the existing DSL mutation tests, a new method runs six independently authored
requirements: payroll, word processing and email, each in English and German.
The versioned resource includes provenance and official expected code/title bindings
chosen from the catalogue before inspecting any ONNX response. These are synthetic
positive references, not expert annotations, held-out evaluation data or an exhaustive
set of every acceptable result. Existing catalogue labels and IDs are unchanged.

The actual application REST endpoints perform semantic KNN and full-text retrieval
with identical requirements, catalogue and top-K=10. Only requirement text is sent
to the search endpoints: expected IDs and titles are never supplied as answers.
There is no response playback or parallel embedding/search implementation.

## Evidence, errors and limits

* Require enabled/available/modelAvailable AND semanticReady, READY index state,
  positive vector-index population and agreement with the catalogue count. A cold
  availability flag alone cannot certify inference. Re-check readiness around each
  search, and re-check catalogue and model-file identities at completion.
* Use the existing ONNX suite's ten-minute index readiness ceiling and 30-second
  HTTP request deadlines. Execute a bounded six cases times two adapters, sequentially,
  without retrying unsuccessful scored queries into success. Model setup and ordinary
  build/image dependencies can require network; evaluation makes no external-provider
  requests. This is not a container-level network isolation claim.
* All case/adapter rows begin NOT_RUN. HTTP/malformed/duplicate/unknown-result failures
  are ERROR or TIMED_OUT with null measurements. An empty semantic result is ambiguous
  because the productive service catches errors into an empty list; it is therefore
  not converted to zero recall. A successful empty lexical result has measured zero
  recall. Unavailability, cancellation and missing artifacts are not successful skips.
* Positive-reference recall@10, first relevant rank, observed IDs and missing required
  IDs are reported per case, language and adapter. There is deliberately no exhaustive
  precision, language pooling, calibrated probability or claim that ONNX wins.
* The initial English regression contract requires the single referenced element
  within ten semantic hits for each English case. German results are explicitly
  observational: misses remain REFERENCE_MISSED and the overall report remains
  MEASURED_WITH_REFERENCE_MISSES even when the English contract passes. A green
  Maven run must not be advertised as proven German-language quality. These initial
  thresholds were chosen before observing model results, not fitted to a green run.

`target/failsafe-reports/local-onnx-reference/` contains JSON, CSV and readable HTML.
Reports record actual application JAR/source identity, model/tokenizer/config hashes,
reference and catalogue-projection fingerprints, query configuration,
attempted search count and timings. Dynamic timing is intentionally separate from
stable case ordering and measurement values; new live runs are not promised to be
byte-identical. No requirement text, auth headers, provider replies or secret values
are copied into the report. Unit-test negative reports use temporary directories.
Model provisioning/container startup failures before the evaluation method remain
Failsafe errors rather than generated inference evidence.

## Initial implementation verification (historical)

Java 21 compilation with `-Xlint:all -Werror` passed for both helper classes against
the application's actual Jackson 3.1.5 dependencies. A hand-computed metric probe
failed before implementation and passed afterward; 15 supplementary metric/schema/
readiness checks passed. A real local application with embeddings DISABLED was
correctly rejected, with twelve NOT_RUN rows and no fabricated quality rates.
That negative probe used placeholder metadata solely to reach the disabled endpoint;
it did not execute a model and is not ONNX quality evidence.

The exact-source-tree application from artifact 10966509390 (run 36414706983) was
also used for the six actual full-text queries: the English references were found
in all three cases; German payroll and word-processing references were not found
in the first ten results, while the German email reference was found. This is only
a lexical baseline on that older recorded application source, not a comparison win
for an unexecuted ONNX model. Case bindings were fixed before these queries.

Full Maven/JUnit, Docker and successful ONNX inference were not executed locally:
the available source is a recovered subset without the Maven wrapper, external
dependency/model downloads are unavailable, and the browser probe is blocked by
local Chromium policy. The workflow must supply exact-head execution evidence.
Do not close #927 or merge based only on these supplementary checks. This slice
does not implement generated relations, reformulation, alternative groups,
hierarchical metrics, general resumable evaluation or the rest of the roadmap.


## Verified CI checkpoint and dependency security correction (2026-09-29)

The exact-head ONNX workflow `36521142846` at `41ae713da8eabd16a04d3f5f0b8484b6de3997b2`
completed successfully. Artifact `11013912386` has SHA-256
`53fe6a11f58c5f5cf9efcd900d9512b76c74ea9574cc14b980cbecf3df683ea4`.
It contains 9 passing pipeline tests, 9 passing browser tests, 4 passing embedding
tests and 5 passing REST tests, with no errors, failures or skips in those suites.
The clean application identifies that exact source commit. All twelve searches
ran: English semantic ranks are payroll 1, word processing 2 and email 1.
German payroll and word processing still miss their references; email is rank 6.
The overall report remains `MEASURED_WITH_REFERENCE_MISSES`. This is not evidence
of general German-language quality.

The independently failing Security Scan `36521142814`, job `109254054398`,
blocks Jackson databind 3.1.5 for HIGH CVE-2026-68497. That is not another ONNX
reference failure. Its SARIF artifact `11012891962` has SHA-256
`f0fc8e9446f31bcd88d6542c17e1f96c7be1d8a6cfde0b5e1228766c97d52289`.
The Jackson 3 BOM is upgraded to the fixed 3.1.6 patch family, with vulnerable
3.x databind ranges blocked by Maven Enforcer independently of the BOM override.
The separately managed Jackson 2 family is unchanged. No scanner exceptions,
severity changes, disabled tests, query changes or altered ONNX thresholds.
The upstream release notes identify the fix:
https://github.com/FasterXML/jackson/wiki/Jackson-Release-3.1.6

`JacksonXmlDatatypeConstraintsTest` exercises actual deserialization with a small
64-character numeric limit, never a resource-exhaustion payload. The two guard
tests fail with 3.1.5 and pass with 3.1.6; ordinary values pass both. All three new
JUnit tests plus eight existing ONNX reference/report contracts pass on the patched
Jackson jars (11 passed, none skipped). Compilation uses Java 21 with warnings as
errors. These are focused tests, not a full Maven/application or new-head scan pass.
The final full Maven command was attempted but the pinned Maven distribution could
not be downloaded locally. Complete exact-head CI and security scanning remain
required after the dependency change. The earlier successful ONNX evidence is not
relabeled as a run of the later dependency patch.
