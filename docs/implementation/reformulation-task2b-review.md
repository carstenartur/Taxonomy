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
