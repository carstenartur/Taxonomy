# PR 1135 bounded review fixes

Status: merged-base covering selector passed; independent Task 3 review identified
two P2 omissions and scoped corrections are underway. Final Task 3 acceptance remains
open pending the corrected code, regenerated visual samples, review and CI.

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

Subsequent DE three-page inspection confirmed the same top-edge margin defect and
English metadata/identity keys, with some English history/provenance labels. DE and EN
draft/adoption status and literal markup were readable. Bounded test-first layout,
provenance and DE label checks ran RED: architecture 2 tests/2 semantic failures,
app real frozen graph 1 test/1 semantic failure (`null / vnull` provenance). A first
architecture test compilation error from POI/AssertJ API assumptions was corrected
before semantic RED. The proposed standalone page setup, frozen numeric source
identity and DE label corrections are WIP, not yet GREEN.

Current main advanced with PR 1134 during this work; integrate it via an ordinary
merge after the next clean checkpoint and before the next broad gate.

Main was merged with the preserved second parent and exact remote tree. First merged
covering run: 59 tests selected across modules, 2 failures, 0 errors. Architecture
renderer 4/4, codec 6/6, guard 16/16, history 2/2, ratchet 22/22 and export 1/1
passed. The DE revision test's old English `Analysis snapshot` expectation failed
after correct localization; it now expects `Analyse-Snapshot`. The real reanalysis
remote-only playback did not recognize the newly merged `relation-downwalk-v1`
request. The fixture now explicitly responds to its extraction phase with exact
offered IDs and conservative REJECT decisions; this exercises the real protocol and
does not disable relation search or alter product budgets. Fresh DOCX from this failed
run is not final visual evidence.

Final merged-base covering command used the command above with
`,AdoptedLineageRealReanalysisTest` appended to `-Dtest`. Maven exit 0:
59 tests, zero failures/errors (export 1, architecture 4, codec 6,
app report 7, positive guard 16, history 2, real reanalysis 1, ratchet 22).
The app subtotal is 48. The real reanalysis exercises outbound provider HTTP playback
including the merged relation extraction request and still requires SUCCESS. The JS
download contract passed in the report fixture.

Fresh application-produced DOCX bytes for independent all-page rendering:

- `reformulation-de-adoption.docx`: 5146 bytes, 2026-09-27 18:10:07 +0200,
  SHA-256 `d3ac2437a9f725441f3a45662b3909e1daa90584aeb0e7fee6a80e30c2b572ff`.
- `reformulation-en-graph-answered.docx`: 31567 bytes, 2026-09-27 18:09:32 +0200,
  SHA-256 `f7521c7b22d48d59c2348522237a34a57e854d206505f0c00d5b2c8e9a93f4df`.

Both are under `/workspace/scratch/38625e9262ff/reformulation-task3-qa/`.
The representative fixtures are saved through actual application services and
rendered by the production DOCX exporter. They are not a remote-only E2E language
quality proof. CI on the final published commit and fresh visual inspection remain
separate gates. Task 4 civilian browser/restart acceptance and opt-in real-model
comparison remain outstanding.

Root independently inspected all eight pages of the pre-round1 exact-SHA documents
(EN five, DE three) with LibreOffice/Poppler. Page margins, provenance coordinates,
graph/tables, labels and status were accepted visually. The exact files, PDFs and PNGs
are preserved in `Taxonomy-PR1135-DOCX-QA.zip`; this approval precedes the origin-context
change below and fresh rendering is required after it.

Independent Task 3 review is in `reformulation-task3-review.md`. Two P2 findings:
snapshot summary's requirement-version number can disagree with frozen source version;
merged origin questions omit saved state, complete answer schema, consequences and
dependencies in DOCX. Narrow test-first checks now tamper the summary number and
require nontrivial origin context in EN/DE. These tests have not run in this checkpoint.
