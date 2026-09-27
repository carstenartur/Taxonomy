# Task 2b independent review

Reviewed range: `8e1a1103276600a3127759488fe8bd6c04b463ae` to
`03e4208bbd8086c1649240ccb37e2cc2b0d6b76b`.

Reviewer: fresh, read-only `review_lineage` agent. No reported suites rerun.
Verdicts: **specification issues found; task quality needs fixes**.

## Important findings for fix round 1

1. `ReformulationResponseParser.java:22–27,45`: the rejected-wording guard reads
   only current direct and child statements, not rejected statements in
   `inheritedDecisionContext`. A fresh offer after adoption begins without those
   historical statements; the model can repeat previously rejected wording and
   pass parsing. Feed applicable inherited rejections into the mechanical guard,
   with a regression covering the adopted new-offer path.
2. `ReformulationEvidenceCodec.java:149–167` and
   `ReformulationPromptBuilder.java:38–49`: all ancestor statements, questions,
   and answers are projected into every node call without filtering by node or
   decision scope. A branch-local rejected decision can influence an unrelated
   branch or consume its provider budget. Resolve applicable inherited records
   deterministically for each call, retaining global decisions where applicable.
3. `ReformulationEvidenceRoundTripTest.java:241–276,516–528`: the new-offer tests
   inject persisted fixture analysis snapshots and stop at offer creation. They
   do not verify the brief's post-adoption reanalysis/checkpoint path with only
   outbound LLM replies replaced. Add that bounded integration regression; the
   separate prompt unit tests do not cover this transition.

No Critical or Minor findings. The reviewer identified the strict source/target
and closure validation, archive/projection separation and protected adopted
source provenance as strengths.

## Controller disposition

All three findings accepted for one bounded fix round; no change to the product
specification or deferral of the genuine reanalysis transition. The later full
civilian/browser flow remains Task 4.

The complete tree at the review head was independently read back from the remote
branch, fetched and matched to the clean local worktree by the controller. Focused
test commands and distinct run counts are recorded in the implementer report;
these were not presented as a single final broad run. Root's integrated gate and
real-provider language-quality gate remain open.

Next: original implementer fixes the three findings with focused RED/GREEN
evidence and remote checkpoints. Re-review is limited to these findings and new
breakage in the fix diff.

## Scoped re-review of fix round 1

Reviewed range: `03e4208bbd8086c1649240ccb37e2cc2b0d6b76b` to
`606936c3616697ac8c96a956301e1fa19feb2103`. The same independent reviewer
inspected only the three findings and new breakage in that fix diff; no suites
were rerun by the reviewer.

- Finding 1 — **ADDRESSED**: applicable inherited rejected wording reaches both
  the parser and engine guard. Regressions cover fresh adopted offers and output.
- Finding 2 — **NOT ADDRESSED**: `InheritedDecisionContext.java:143–155` treats
  generic scope `local` as unmappable. Actual statement-edit questions use
  `Key(statementId, "edit", "local")` with an affected statement
  (`ReformulationService.java:144`). An A-node call therefore selects a B-only
  historical edit question and then pulls its affected B statement into the
  A prompt through the dependency closure at lines 88–92. Existing tests use
  concrete A/B scopes, not this reachable generic-local case.
- Finding 3 — **ADDRESSED**: the new real-reanalysis integration test adopts,
  runs the actual analysis operation, selects the persisted snapshot, creates
  the next offer and commits a checkpoint. Only outbound LLM replies are replaced.
- New Critical/Important breakage: none beyond the remaining scope defect.

Controller checked the reachable `local` producer and selector. The remaining
finding is accepted for bounded fix round 2/5. The other two findings stay closed.
Covering run at the reviewed head: 41/41, no failures/errors/skips; this does not
make the outstanding scope case correct. The complete reviewed tree is remote.
