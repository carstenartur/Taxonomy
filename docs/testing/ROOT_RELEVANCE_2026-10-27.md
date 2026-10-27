# Root relevance verification — 2026-10-27

## Scope and result

Verified the root-relevance changes on main base
`6ee7e17e5d2e319080b1009902c4afd1e4619254`, using Java 25. The focused reactor
`package -Ptest-local` run and the separate JavaScript tooling run passed.
No provider limit, CI gate or timeout was changed; existing retry and admission
policies remain in force.
The [design](../superpowers/specs/2026-10-27-root-relevance-design.md) describes the
selected contract and its compatibility constraints.

| Final run | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| `taxonomy-analysis` | 461 | 0 / 0 / 0 |
| `taxonomy-portfolio` | 42 | 0 / 0 / 0 |
| `taxonomy-app` | 21 | 0 / 0 / 0 |
| **Java total** | **524** | **0 / 0 / 0** |
| Separate `taxonomy-tooling/JavascriptContractTest` | 377 | 0 / 0 / 0 |

The 377 tooling cases execute the selected JavaScript contracts; they are reported
separately from the 524 Java application/module tests. Raw local logs:
`target/root-relevance-work/final-verification.log` and
`target/root-relevance-work/final-javascript-verification.log`.

## Verified behavior

All eight roots share the same generative typed `status`, `score`, `reason`,
`question` contract. Legacy numeric responses remain accepted; the local ONNX
similarity path does not invent typed generative decisions.

| Status | Score | Meaning |
| --- | --- | --- |
| `RELEVANT` | Finite value in `(0, 100]` | Supported relevance, including a necessary service already available. |
| `IRRELEVANT` | `0` | Assessed irrelevance; an explicitly isolated offline requirement can support this result. |
| `UNRESOLVED` | Explicit `null` in the typed reply | A requirement decision is missing; a nonempty reason and clarification question are required. |

Every typed assessment requires a nonempty reason; resolved assessments carry no
question. Contradictory combinations are rejected.

Unresolved roots have no numeric-map entry. Coverage remains `UNKNOWN` with a null
score and a `CLARIFICATION:` reason; `ROOT_UNRESOLVED:` transports the clarification
through the existing diagnostics. Valid sibling evidence survives. Journal replay,
explicit retry/leave-open, local/streamed/distributed results and the DE/EN root
container preserve this distinction. Tests cover legacy replies, malformed typed
combinations, positive decimals, replay and partial-result retention.

The actual product paths run with authored demo-provider replies in
`ProductDemoEndToEndTest`; outbound provider HTTP is forbidden by that test:

1. Vague R0 produces **A0 `PARTIAL`**. CO is unknown with no numeric score, while
   other assessed nodes remain available.
2. Five visible decisions, rejection of the surveillance addition, protected
   regeneration and explicit adoption produce R1. The authored online case
   supports positive connectivity through the real CO catalogue, including
   `CO-1038`; it does not choose an IP version or VPN implementation.
3. R1 produces **A1 `SUCCESS`** and the independent reference comparison **`PASS`**.
4. A second reformulation on adopted R1 **completes**, retains inherited decisions,
   does not repeat the questions and does not reintroduce the rejected addition.

Reference tests preserve unassessed missing scores/relationships when a partial
analysis or relation search is incomplete. Demo authority tests reject changed
scenario versions/fingerprints and R0-to-R1 retry target mismatches before job
mutation. The worker's existing failure path persists late admission failures.

## Reconciliation budget regression: RED → GREEN

The real second R1 reformulation originally failed in `RECONCILE`, without a repair
attempt, at **123,379 codepoints** against the unchanged **120,000** limit. The
selected original (2,749 codepoints) was also copied verbatim into the current
source statement's `wording` and full source span's `exactText`.

The narrow encoder uses `wordingRef` / `exactTextRef` pointing to
`baseline.originalText` only for an exactly matching `ORIGINAL` or `ADOPTED_SOURCE`
statement and matching full UTF-16 span. It operates on the chosen prompt copy;
source, IDs, provenance, spans, review state, domain objects, historical decisions
and catalogue evidence remain complete. The existing savings check includes the
264-codepoint instruction in both codepoint and UTF-8 measures. Unprofitable
encodings retain the original representation. Input encoding identity is now
`reformulation-input-inherited-budgeted-v7`.

| Exact captured-request calculation | Before | After references plus instruction | Unchanged limit |
| --- | ---: | ---: | ---: |
| Codepoints | 123,379 | 118,057 | 120,000 |
| UTF-8 bytes | 123,802 | 118,370 | 262,144 |
| Estimated input tokens | 30,845 | 29,515 | 30,000 |

These sizes come from the captured rejected request and its lossless transformation;
full decoding reproduces identical JSON. They are not measured provider token
billing. Evidence: `target/root-relevance-work/budget-diagnostic.log` and
`taxonomy-app/target/root-relevance-budget/rejected-1.txt`.

Before the fix, `ReconcilePromptCompactionTest` ran six tests with two expected
assertion failures and no errors (`reconcile-source-reference-red.log`). After
implementation, all six passed. The new cases cover full round-trip equality,
unchanged domain/history, Unicode and small-input budget accounting, partial or
shifted spans, nonmatching wording, other provenances, and preservation of the
actually selected discovery-context representation. The ten reconciliation-context
checks and existing parser, inherited-context, lineage and node-service tests also
passed, followed by the complete demo E2E. Temporary budget instrumentation was
removed; no fixture evidence was shortened.

## Reproduce the verified runs

Run from the repository root with Java 25 configured and the Maven/npm dependencies
already available locally. `-o` deliberately matches the verified offline runs.
These selectors and arguments are copied from
`target/root-relevance-work/final-verify.sh` and `final-js-verify.sh`; local absolute
`JAVA_HOME` and working-directory paths are omitted.

```bash
root_relevance_tests='JavascriptContractTest,AssessmentCoverageTest,RootClarificationRecoveryTest,PartialAnalysisResilienceTest,StreamRequirementAnalysisUseCaseTest,AnalysisSseEventMapperTest,AnalysisCompletionContractTest,ClusterAnalysisBackupRestoreTest,ClusterAnalysisDetailedProgressTest,DemoScenarioCatalogueTest,DemoReferenceEvaluatorTest,AnalysisStoppedEvidenceTest,LlmResponseParserTest,LlmServiceBranchCoverageTest,AnalysisProgressRegistryTest,RootAssessmentProtocolTest,ProductDemoReplayTest,RootScoringFlowTest,LlmResponseRootTest,ProductDemoEndToEndTest,ProductDemoAuthorityRegressionTest,ProjectRequirementAnalysisAsyncDispatchTest,ProjectAnalysisContinuationPolicyTest,ProjectAnalysisBudgetContinuationTest,ReconcilePromptCompactionTest,ReconciliationContextTest,ReconcileResponseParserTest,CrossTaxonomyReconciliationTest,AdoptedLineagePromptTest,InheritedDiscoveryContextTest,NodeReformulationServiceTest,FrozenReformulationBoundaryTest,RecoveryContinuationPayloadTest,AnalyzeRequirementUseCaseTest,ClusterAnalysisStoreTest,FrozenClusterAnalysisFinalizerTest'
root_relevance_js_sources='.github/scripts/analysis-live-progress.test.mjs,.github/scripts/analysis-partial-results.test.mjs,.github/scripts/analysis-recovery-viewport.test.mjs,.github/scripts/analysis-recovery.test.mjs,.github/scripts/product-score-streaming.test.mjs,.github/scripts/subtaxonomy-container-browse.test.mjs,.github/scripts/subtaxonomy-container-metadata.test.mjs,.github/scripts/subtaxonomy-container-onboarding.test.mjs,.github/scripts/subtaxonomy-container-svg-export.test.mjs,.github/scripts/subtaxonomy-container-views.test.mjs,.github/scripts/taxonomy-state.test.mjs'

./mvnw -o -B -ntp -Dmaven.build.cache.enabled=false \
  -pl taxonomy-app -am package -Ptest-local \
  "-Dtest=$root_relevance_tests" -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dtaxonomy.javascript.sources=$root_relevance_js_sources"

./mvnw -o -B -ntp -Dmaven.build.cache.enabled=false \
  -pl taxonomy-tooling -am test -Ptest-local \
  -Dtest=JavascriptContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dtaxonomy.javascript.sources=$root_relevance_js_sources"
```

## Browser evidence and verification limits

Four new Chromium component cases passed: DE/EN at 960/320 pixels, with keyboard
collapse, the complete visible clarification, appropriate ARIA, no manufactured
CO zero and retained sibling evidence. These use the production renderer with
bounded fixtures; they are not full-application screenshots.

The standalone browser runner still fails its existing assertion that a routine
status bar stays visible after scrolling 3,000 pixels. The same failure was
reproduced with the unchanged original script and original JS/CSS at the UI-check
baseline `9a5818910e2ca1416002408b83d8ad6c37c9317e`; the routine feedback uses
`position: static`. No assertion or unrelated layout was changed. Details and
component evidence are recorded in `target/root-relevance/task3-ui-report.md`.

This verification uses authored playback, mocks or deterministic test responses;
it is **not a live-model quality evaluation**. Full CI `verify -Pci` and its ONNX
coverage were **not run here**; Docker was unavailable.
The focused package and browser results do not substitute for that CI gate.

## Full-application captures

Both captures were produced from the freshly packaged application, independently
checked and visually inspected. The real UI created the product demo and analysed
R0; its persisted snapshot `6448bf6c-a4bf-4c6f-8137-75434e91b03b` is `PARTIAL`.
The unchanged snapshot was exported through `/api/scores/export` and loaded in
DE/EN using the native file chooser and `/api/scores/import`. No DOM, response or
resource substitution was used. The capture run made exactly one analysis request;
the two imports did not start another analysis. No browser or HTTP errors occurred.

| Artifact | Verified content |
| --- | --- |
| [German R0 clarification](../images/root-relevance-clarification-de.png) | 955 × 1133; full CO reason/question, no numeric CO value, retained BP 40% / CR 45% / UA 70%. |
| [English R0 clarification](../images/root-relevance-clarification-en.png) | Same persisted evidence and dimensions; English controls preserve the original German authored explanation. |
| [Capture manifest](../images/root-relevance-clarification-capture.json) | Package, source and image hashes; environment, exact UI journey, snapshot and observation limits. |

All eight root headings and the complete CO question are visible without clipping.
The actual CO toggle was operated with Space; `aria-expanded` and the explanation
association were checked. CO is absent from both numeric maps and has `UNKNOWN`
coverage with null score/effective relevance. The screenshots are selected R0
documentation views; the separate R1 behavior is covered by the E2E test above.

The package and its isolated capture copy have identical SHA-256
`90ddfb06968350877bc96da27606fc7dfac0aa608811d5fe4b7278918e86e76a`.
Eleven packaged UI resources and all three demo fixtures exactly match the working
source. The manifest identifies the base commit plus 40 changed-source hashes,
six feature JARs and one plugin, so the uncommitted capture source is not
misattributed to the base commit. Image hashes and dimensions match the manifest.
The isolated local capture application was stopped after the checks.
