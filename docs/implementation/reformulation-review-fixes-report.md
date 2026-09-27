# PR 1135 bounded review fixes

Status: five reported findings and the valid older-source regression pass focused and
covering selectors. Task 3 visual acceptance remains open for concrete layout and
provenance issues found in independent rendered-page inspection.

Five review findings are isolated in focused tests: imported ancestry baseline/physical scope;
local adoption preview versus current target; readable DOCX origin discovery fields;
saved gaps in the empty-graph branch; frozen identity iteration order.

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
frozen architecture model.

Focused GREEN after bounded edits: Maven reactor selector, 5 tests, 0 failures/errors
(export 1, architecture 2, portfolio 1, app 1), exit 0. Before a broader gate,
review identified a valid older-source adoption: the preview's sourceVersionId/originalText
refer to the proposal baseline, while currentRequirement.currentVersion refers to the
intervening active version. The initial guard edit conflated them. A real adoption/review
regression ran RED: 1 test, HTTP 409 instead of expected 200, zero errors. The guard
now binds the source to the saved proposal baseline, the preview's previous active
version to the receipt's previous version, and the final text to the current target
hash. The amended guard and target-hash denial selectors passed together: 2 tests,
0 failures/errors. A locally run real post-adoption reanalysis diagnostic also reached
SUCCESS (1 test, 0 failures/errors). The earlier MSSQL CI PARTIAL was not reproduced;
its terminal diagnostic remains in the test for the next CI run, with SUCCESS required.

Separate MSSQL CI follow-up: the real post-adoption reanalysis test now waits for
terminal operation state and emits operation, snapshot summary, and analysis warnings
in its still-strict SUCCESS assertion. This is diagnosis only, not an acceptance change.

Covering command:
`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dreformulation.docx.qa.dir=/workspace/scratch/38625e9262ff/reformulation-task3-qa '-Dtest=ReformulationReportTest,ReformulationReportHistoryTest,ArchitectureContextDependencyRatchetTest,ReformulationEvidenceCodecBoundaryTest,ReformulationPositiveReviewGuardTest,ReformulationReportDocxReviewTest,FrozenReformulationArchitectureOrderTest' -Dsurefire.failIfNoSpecifiedTests=false test`.
Maven exit 0: 56 tests, 0 failures/errors (export 1, architecture 2,
portfolio codec 6, app report 7, positive guard 16, history 2, architecture ratchet 22).
The app subtotal is 47. The existing JS download contract also passed from the report
fixture. Fresh app-authored files:
`/workspace/scratch/38625e9262ff/reformulation-task3-qa/reformulation-de-adoption.docx`
and `/workspace/scratch/38625e9262ff/reformulation-task3-qa/reformulation-en-graph-answered.docx`.

Independent EN six-page render identified pages beginning too near the top edge, missing
explicit section margins, and a provenance table with null project/requirement/version
despite known frozen values. Standalone renderer layout and evidence binding are the next
bounded fixes. DE page inspection is pending. No Task 3 visual acceptance claim.
