# PR 1135 bounded review fixes

Status: five reported findings have focused GREEN; guard valid older-source regression pending RED.

Five review findings are isolated in focused tests: imported ancestry baseline/physical scope;
local adoption preview versus current target; readable DOCX origin discovery fields;
saved gaps in the empty-graph branch; frozen identity iteration order. No production fix yet.

Execution was restored in isolated `Taxonomy-resume-1135` checkout at `07e8de43`:
Java 21 and task-local Maven repository survived, while the old Maven executable path did not.
Downloaded Maven 3.9.16 from the wrapper's declared distribution URL and verified SHA-256
`5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce`.
The task-local ignored runner points to the restored binary. Runtime restoration did not
change product code.

The user authorizes merging independently reviewed/tested useful intermediate states;
root handles merge/CI. Task 4 remains required after these fixes and Task 3 visual QA.

RED evidence before production edits, all five findings:

- Frozen identity order: 1 test, 1 expected assertion failure, 0 errors (export module).
- DOCX readable origin and empty-graph saved gaps: 2 tests, 2 expected assertion failures, 0 errors (architecture module).
- Ancestry baseline binding: 1 test, 1 expected assertion failure, 0 errors (portfolio module; a first fixture run erred on workspace-scope encoding and was corrected before semantic RED).
- Current local preview target hash: 1 test, 1 expected HTTP assertion failure (200 versus required 409), 0 errors (app module).

Production edits bind baseline coordinates to the proposal/receipt row and encoded scope,
bind preview source and adopted target to the current physical aggregate, render origin
discovery fields and empty-graph gaps, and preserve immutable insertion order in the
frozen architecture model. These changes are not yet verified.

Focused GREEN after bounded edits: Maven reactor selector, 5 tests, 0 failures/errors
(export 1, architecture 2, portfolio 1, app 1), exit 0. Before a broader gate,
review identified a valid older-source adoption: the preview's sourceVersionId/originalText
refer to the proposal baseline, while currentRequirement.currentVersion refers to the
intervening active version. The guard currently conflates them. A real adoption/review
regression is now test-only WIP; run it RED, then bind each stored coordinate correctly.

Next targeted command after durable checkpoint publication:
`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am '-Dtest=ReformulationEvidenceCodecBoundaryTest#ancestryRejectsBaselineOutsidePhysicalProposalAndReceipt,ReformulationPositiveReviewGuardTest#mismatchedLocalPreviewCannotAuthorizePositiveReview,ReformulationReportDocxReviewTest,FrozenReformulationArchitectureOrderTest' -Dsurefire.failIfNoSpecifiedTests=false test`.
