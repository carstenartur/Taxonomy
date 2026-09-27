# PR 1135 bounded review fixes

Status: test-only WIP checkpoint; regression selectors have not run yet.

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

Next targeted command after durable checkpoint publication:
`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am '-Dtest=ReformulationEvidenceCodecBoundaryTest#ancestryRejectsBaselineOutsidePhysicalProposalAndReceipt,ReformulationPositiveReviewGuardTest#mismatchedLocalPreviewCannotAuthorizePositiveReview,ReformulationReportDocxReviewTest,FrozenReformulationArchitectureOrderTest' -Dsurefire.failIfNoSpecifiedTests=false test`.
