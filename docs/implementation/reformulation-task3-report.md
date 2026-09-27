# Task 3 implementation ledger

Status: in progress; not product acceptance.

## Checkpoint 1 — RED tests

- Scope: frozen historical DOCX revision/receipt, without adoption or live architecture changes.
- Seam: export-owned frozen model and DOCX port, app composition binding persisted baseline; architecture module owns POI and graph adapter.
- Added real endpoint/POI-parser revision test and JavaScript DOCX download contract.
- RED: `node taxonomy-app/src/test/resources/reformulation/report-download-contract.cjs taxonomy-app/src/main/resources/static/js/api/portfolio-api.js taxonomy-app/src/main/resources/static/js/portfolio/reformulation-reports.js` exited 1 with `TypeError: Unsupported report format` at the production API adapter.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#docxRevisionContainsFrozenSourceAndLiteralEvidenceWithBinaryDigest -Dsurefire.failIfNoSpecifiedTests=false test` (expected RED: unsupported DOCX format).
- No long test run or implementation edit yet. Root must publish this WIP checkpoint and verify full remote tree before continuing.

## Checkpoint 2 — first implementation slice

- Endpoint RED: focused Maven selector executed one test, failed its HTTP 200 assertion with HTTP 400 (`Supported report formats: json, md, html`); zero test errors. Command is recorded above.
- Added export-owned frozen architecture model and DOCX port, scoped persisted-baseline access, snapshot-only assembler, architecture POI adapter, binary MIME/digest response, and fourth UI choice.
- JS GREEN: exact Node selector above exited 0 with `REFORMULATION_REPORT_DOWNLOAD_OK`.
- Frozen validation/detail, branch distinction, graph and gap tests are still pending; current assembler is not yet verified. No Maven GREEN or visual QA claim.
- Next exact command after remote checkpoint and local ref alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#docxRevisionContainsFrozenSourceAndLiteralEvidenceWithBinaryDigest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 3 — graph/gap RED selectors

- Java first GREEN attempt: 1 test, 1 failure, 0 errors; DOCX returned HTTP 500 because the saved snapshot fixture has no architecture graph and `ArchitectureReportDocument` rejects empty graphs. No passing claim.
- Added explicit unavailable graph section, removed raw catalogue/report JSON dump from DOCX prose, and surfaced captured inherited decision context.
- Added frozen-mapper tests for distinct offer/analysis branches, two directed parallel mappings without duplicate view edges, absent versus empty gaps, tampered version and dangling relation target. These tests have not yet been run; expect current graph multiplicity assertion to fail.
- Next exact command after root remote verification/ref alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 4 — corrected fixture

- Focused report class: 5 tests, 1 assertion failure and 2 fixture deserialization errors; the new ViewContext fixture omitted required primitive booleans. Endpoint revision test and existing JSON test were green in that selector, but no class-level GREEN.
- Fixture now supplies all ViewContext booleans and deliberately uses view relation IDs 99/100 distinct from mapping-row IDs 11/12. This makes semantic multiplicity (rather than incidental ID equality) observable.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 7 — semantic RED and bounded fix

- Valid frozen fixture: 5 tests, 1 expected semantic assertion failure, zero errors. Saved two directed mapping edges were rendered as four (`mapping-11`, `mapping-12`, `view-1`, `view-2`), proving the projection duplication bug.
- Reconciled view relation occurrences against directed source/target/type counts from mapping rows, retaining parallel and reverse occurrences. View identity differs deliberately from mapping-row identity.
- Moved typed frozen snapshot decoding/validation into portfolio-owned implementation, exposed only neutral export model through the already referenced `ReformulationReportService`; app no longer imports portfolio DTO/codec classes for graph assembly. Architecture remains POI adapter.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest,ArchitectureContextDependencyRatchetTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 8 — rename publication and stale class output

- Root's first publisher attempt refused an exact-tree mismatch caused by rename detection omitting the old-path deletion. No remote branch advanced and no source was lost. Explicit delete/add publication with whole-tree verification succeeded; this is a publisher handling issue, not an application outcome.
- Initial combined selector ran 22 ratchet tests (1 failure), 5 report tests (5 context-load errors). Both stem from the deleted app assembler's stale `.class` still present in `taxonomy-app/target/classes`: ArchUnit imports it and Spring sees duplicate bean names. Source tree contains only the portfolio-owned class. This is build-output staleness, not a production dependency ratchet or semantic assertion result.
- Next exact commands after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app clean` then `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest,ArchitectureContextDependencyRatchetTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 9 — core GREEN; real graph and receipt RED selectors

- Targeted clean removed only `taxonomy-app/target` and succeeded. Combined focused gate: 27 tests, 0 failures/errors (5 report, 22 architecture ratchet), Maven exit 0. No ratchet baseline edit.
- Added tests-first real persisted graph endpoint (two catalogue nodes, one directed saved relation, figure, canonical `edge-<mappingId>`, historical branch and explicit empty gaps) and separate exact adoption-receipt DOCX status versus unchanged proposal revision. Neither new selector has been run yet.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#realSavedGraphDocxUsesCanonicalEdgeReferenceAndEmbedsFigure+exactAdoptionReceiptIsBinaryDocxAndDoesNotRetroactivelyAdoptRevision -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 10 — authored graph fixture correction

- Two-test selector: adoption receipt positive; graph case did not reach the exporter because the real BP catalogue fixture root has only one immediate child. One test error (`IndexOutOfBoundsException`), zero assertion failures. Not semantic RED.
- Revised graph fixture uses actual BP root and its first child as directed endpoints, still persisted via real snapshot/view/mappings.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am '-Dtest=ReformulationReportTest#realSavedGraphDocxUsesCanonicalEdgeReferenceAndEmbedsFigure+exactAdoptionReceiptIsBinaryDocxAndDoesNotRetroactivelyAdoptRevision' -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 11 — canonical edge semantic RED

- Corrected two-test selector ran 2 tests, 1 failure, 0 errors. Adoption DOCX passed. Persisted graph route produced `mapping-1`, but the saved reformulation contract references `edge-1`; this is a semantic reference failure, not a fixture error.
- Extended the same real-endpoint graph test to persist a linked statement and question, and to demand an internal Word bookmark/hyperlink for the saved edge and an embedded figure. New navigation assertion has not yet been observed RED separately; canonical ID already fails.
- Inspected producer: `PortfolioAnalysisPersistenceService.persistSnapshot` stores analysis payload/gaps and derives element/relation mapping rows directly from the captured `RequirementArchitectureView`, with snapshot summary branch sourced from `ViewContext.basedOnBranch` when present. Test uses that real producer path.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#realSavedGraphDocxUsesCanonicalEdgeReferenceAndEmbedsFigure -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 14 — historical/coordinate red tests and QA capture

- Saved graph navigation selector GREEN: 1 test, 0 failures/errors, real persisted view/mappings, POI parser, embedded figure, internal hyperlink/bookmark; Maven exit 0.
- Added real-process DOCX revision/receipt binary response checks with SHA-256, no-store/nosniff/MIME/filename, parsed historical stability after adoption, current-source edit and fresh process restart; foreign-scope DOCX denial. These have not yet run.
- Extended mapper case to demand rejection of tampered frozen snapshot workspace and analysis branch while accepting offer branch `main` with captured analysis branch `draft`.
- Real graph endpoint now saves an answered EN DOCX; historical process saves an answered DE adoption DOCX when `-Dreformulation.docx.qa.dir=/workspace/scratch/38625e9262ff/reformulation-task3-qa` is supplied. These are app-produced bytes for root's independent visual QA, not handcrafted documents.
- JS download contract now exercises DOCX MIME success and wrong-MIME refusal; direct Node selector exited 0 with `REFORMULATION_REPORT_DOWNLOAD_OK`.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#tamperedFrozenSnapshotAndDanglingDirectedEndpointAreRejected -Dsurefire.failIfNoSpecifiedTests=false test` (expected coordinate RED); then bounded fix and focused history run.

## Checkpoint 13 — saved-reference navigation

- Real graph endpoint after canonical ID fix: 1 test, 1 expected assertion failure, 0 errors. POI parsed the DOCX and found a figure, but saved edge references lacked an internal bookmark/hyperlink.
- Added literal internal Word targets for every frozen graph node and edge and links from saved statement/question discovery references. Unresolved IDs remain visibly labelled unavailable rather than linked to live or invented graph content. The link targets are deterministic and contain no external URL.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#realSavedGraphDocxUsesCanonicalEdgeReferenceAndEmbedsFigure -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 12 — canonical edge correction

- The existing semantic RED names the exact missing behavior; no redundant unchanged-source rerun. Graph mapping rows now expose canonical `edge-<persisted mapping id>`, matching the synthesis/reconciliation references. Parallel-edge expectation updated to the same public ID contract.
- The saved reference hyperlink/bookmark assertion remains unverified; next focused run should expose it (or another genuine downstream defect).
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#realSavedGraphDocxUsesCanonicalEdgeReferenceAndEmbedsFigure -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 6 — frozen catalogue fixture

- Focused class again ran 5 tests, 1 failure / 2 errors: the real BP catalogue has no authored `BP-1`/`BP-2` fixture nodes, so mapper correctly rejected those mappings before reaching multiplicity. Endpoint revision and existing JSON cases passed.
- Synthetic saved catalogue now explicitly contains BP, BP-1 and BP-2 and is embedded consistently in both frozen snapshot bytes and detail. This is only test evidence, not a production fallback.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 5 — complete typed frozen fixtures

- Previous selector still ran 5 tests with 1 failure / 2 errors: the authored empty `GapAnalysisView` JSON and mapping rows lacked required primitive record fields. This is fixture setup, not semantic RED.
- Test fixture now serializes actual typed `GapAnalysisView`, `ElementMappingView` and `RelationMappingView` values before changing the frozen bytes. No production behavior changed.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest -Dsurefire.failIfNoSpecifiedTests=false test`.
