# Independent whole-branch review — intermediate Tasks 1–3

Reviewed `946e5bdb0900ee190ae0ed72da12e3896407e1c1..1cdfac339540e1f6098382bb6b8973d7fb2b4ee6`, including the production diff, covering test changes, Task 1/2a/2b reports and accepted reviews, Task 3 review/rereview, and the authorized intermediate scope. No applicable `AGENTS.md` was present in the checkout or ancestor directories. No product edits, test reruns, commit or publication were performed.

**Verdict: one localized P2 correction required before intermediate acceptance.** No new important rejection-enforcement, lineage/import, positive-review, scope-isolation or frozen-architecture defect was identified. Previously resolved findings remain closed.

## Finding

**P2 — Word drops the current question's answer constraints and dependencies.**
`taxonomy-architecture/src/main/java/com/taxonomy/architecture/report/ReformulationReportDocxRenderer.java:51–56` renders the current question's answer kind/options, but not its unit, minimum/maximum, option meanings, incompatible groups, applicability, or prerequisite/dependent question IDs. The full rendering at lines 67–90 applies only to `question.origins()`, which can legitimately be empty. Neither the remaining question loop nor the saved-answer loop restores these fields; the JSON evidence input is not embedded in Word.

Concrete reproduction by inspection: export a saved revision with an unmerged `NUMBER` question whose wording is “What timeout?”, unit is `ms`, bounds are 10–100, and origins are empty. Word prints `NUMBER` and `[]` without the unit or range. Similarly, a current conditional question loses its saved prerequisite/allowed-answer gating rule. A historical report reader cannot reconstruct the meaning or applicability of its recorded answer. These are existing supported `DecisionQuestion.AnswerSchema` fields, not deferred Task 4 behavior.

Render the current question's complete answer contract and dependency references with the same readable DE/EN treatment already used for origins. Add a focused actual-DOCX text assertion for a current question with empty origins and nontrivial constraints/applicability. This is distinct from the resolved merged-origin omission: that fix is correct, but does not cover the current question.

## Evidence and limits

- Rejection checks are present at parsing and publication, preserve rejected evidence, and exclude rejected statements from active text/coverage. The documented guarantee remains literal wording protection, not semantic paraphrase detection.
- The adopted-source archive is separate from scoped prompt projection; strict portable decoding, source/target binding, ancestry closure, case-insensitive business identity matching and protected source provenance are implemented. Positive review reads matching current-version evidence under the existing aggregate lock, with the legitimate older-source adoption case preserved.
- DOCX uses persisted report/baseline material and validated frozen architecture, including directed parallel mappings and missing-versus-empty gaps; controller output remains binary with response hash and restrictive download headers. The saved revision/receipt distinction is retained.
- The supplied current-head evidence reports eight focused suites, 60 tests passing. I did not rerun them or claim full-reactor success; the controller's final Maven completion, refreshed all-page DE/EN visual inspection and current published-head CI remain separate gates.
- Task 4 remains explicitly future work after this authorized intermediate delivery. This review does not require its browser/semantic/provider-quality acceptance before the Tasks 1–3 merge, or claim byte-for-byte DOCX package determinism.

## Scoped rereview — sole P2 resolved

Reviewed `bc45e2dc..0da2fdbab2454df274525c47f5cb8866ab633762`, limited to the finding above and new breakage in its fix. **Code-review verdict: approved for the authorized intermediate scope; no remaining actionable findings.**

The current question now uses the same complete `answerSchema` helper as merged origins and renders its prerequisite/dependent IDs. The helper preserves the origin rendering's DE/EN labels, numeric units/bounds, options, naturally sorted option meanings, incompatible groups and applicability conditions. This extraction does not alter saved data or remove any origin fields.

The new `currentQuestionsWithoutOriginsRetainCompleteAnswerContractInBothLanguages` test parses actual DOCX text with empty origins, checks numeric and conditional question contracts in both languages, and checks that origin output cannot accidentally satisfy the assertions. The controller reports a meaningful RED of six tests with one assertion failure; focused GREEN was still running when this rereview was requested. I did not rerun tests or count earlier green results as verification of this fix.

The controller's post-fix focused/covering results, refreshed all-page DE/EN visual inspection and published-head CI remain required completion gates. Task 4 remains future work under the authorized intermediate boundary. No product edit, commit or publication was made by this rereview.
