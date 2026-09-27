# Task 2a — bind persisted baseline and guard positive review

Part of completion Task 2. This bounded checkpoint comes before ancestry/schema
extension. Follow the approved specification and existing module/service boundaries.

## Baseline binding

Reading a scoped proposal currently deserializes its frozen baseline. Validate the
serialized identity against the immutable physical proposal identity before exposing
or consuming it: repository, workspace, branch, project, requirement, source version,
and analysis snapshot. Use canonical existing scope encoding, not a new concatenation
format. A mismatch must fail closed (409), without returning foreign evidence.
Keep historical source selection and legitimate central/workspace scopes compatible.
Workspace branch and the analysis payload's historical basedOnBranch are separate
coordinates; do not equate them. The latter is validated by the later frozen-export
adapter, not by replacing the offer's own scope.

## Positive-review guard

The existing generic requirement metadata update must not grant a positive approval
or review state when the EXACT current requirement version has adopted reformulation
evidence with unresolved conflict questions or STRUCTURAL_LOSS/CONFLICT findings.
Apply under the existing requirement aggregate lock. Use both local adoption receipts
and portable imported evidence; bind by tenant scope, project/requirement business
identity, current version number and content hash. Do not globally search by command
ID or block unrelated requirements/versions/workspaces.

Ordinary requirements, clean adopted evidence, and nonblocking OPEN/DEFERRED questions
must remain compatible. Draft saving/adoption still does not constitute approval.
No new approval mechanism, no automatic active-version changes, no DB schema/service
platform/module hierarchy, no broader metadata refactor. Existing original versions,
review evidence, proposal revisions and audit boundaries stay unchanged.

## Verification

- Write/run real API/database RED tests for tampered baseline scope/IDs, using the
  existing ReformulationIsolationTest patterns. Assert no foreign bytes are exposed.
- Write/run RED tests for positive metadata update on local and imported blocking
  current evidence. Also test ordinary/clean/nonblocking/different-version/different-
  scope compatibility and unchanged state on failed updates.
- Implement the narrowest portfolio-owned services/contracts required.
- Run focused Spring tests plus the existing relevant adoption/evidence tests and
  architecture dependency ratchet. Report positive class/count evidence and exact
  limitations. Root owns the final full reactor/CI gate.
- Record evidence/self-review in docs/implementation/reformulation-task2a-report.md.

## Durability and ownership

Same mandatory protocol as Task 1: apply_patch edits; one implementer; no subagents;
checkpoint commit before long tests or at most 10 minutes of editing; message root
and pause new edits until remote full-tree verification is confirmed. Root publishes
through the GitHub connector. Never merge, force-push, extract credentials or modify
another checkout. WIP commits use [skip ci]; the final green checkpoint does not.
Stop new implementation if remote persistence or the execution environment fails.
