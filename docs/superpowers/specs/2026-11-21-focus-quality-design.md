# Architecture focus quality follow-up

The user requested high architecture quality and explicitly asked for the architecture lens to be checked. This follow-up implements the already established focus-lens workflow, preserving its revision-bound preview, adoption and undo contract.

## Findings and approach

Four reproduced session defects require direct repairs: the private literal `__shared__` workspace transport pin is lost during context copying; a successful assessment can leave older local fallbacks actionable; a duplicate fallback can suppress a newer assessed result; removing unavailable queued work leaves its node permanently queued. Fix these in the session boundary and retain its existing UI/API contracts.

Candidate quality and frozen-source evidence are a second task. Type compatibility alone is insufficient evidence for an architectural link. Local suggestions must expose an actual basis, distribute the bounded candidate set over counterparts, and retain the available requirement contributions and their limits. The exact algorithm will be documented after the session repairs have been reviewed.

Alternatives considered were broad replacement of the editor state manager, patching only visible labels, and targeted state transitions. Targeted transitions preserve the existing journal/API contracts and address the reproduced causes. A rewrite or label-only correction would add risk or retain the defects.

## Invariants

- A literal workspace pin remains part of request routing and local identity without becoming an unsupported enumerable JSON Context field.
- Every response remains bound to its request's semantic context, source hash and epoch.
- A new assessed result can replace unaccepted fallback suggestions from its own investigation. Existing accepted history, independent investigations and source scope remain identifiable.
- A local or failed fallback never silently downgrades an assessed suggestion.
- Removing queued work restores its eligibility; completed and accepted work is preserved.
- Pause, per-session budget, one request at a time, exact preview/adopt and operation-based undo remain intact.
- Hover, keyboard investigation, overlays and review updates do not move the diagram or steal the current review selection.
- Test outcomes distinguish Node fixtures, browser DOM fixtures and the real application HTTP/editor workflow.

## Acceptance

Reproduce the four session failures before changing production behavior. Run the owning Maven/Node tests and relevant browser fixtures. Then run the actual editor flow under the configured servlet context, including investigation, selected review, adoption, replay and undo, plus a source-snapshot investigation. Retain fresh screenshots with source identity, inspect them, and run the broader repository gates without weakening their scope.
