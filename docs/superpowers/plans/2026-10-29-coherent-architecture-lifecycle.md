# Coherent architecture lifecycle implementation plan

Design: [approved lifecycle design](../specs/2026-10-29-coherent-architecture-lifecycle-design.md).

The user authorized implementation, one reviewable PR and fixing its checks. Do not merge or deploy. Product history uses native JGit. Preserve existing verification gates and use actual catalogue identities for full application acceptance.

## Work and verification

- [ ] Canonical realization, exact raw text versions, contribution/related-link retention and strict immutable import.
- [ ] Atomic requirement/review operations, durable no-op receipts, retry results, Git publication proof and explicit reconciliation.
- [ ] Native JGit search, original-object history transfer, real merge/fast-forward ancestry and ref/authority concurrency guards.
- [ ] Configured-provider intake with bounded candidates, validated quotations, exact fallback and explicit manual choices.
- [ ] Consistent six-view navigation, business context, status/evidence presentation, contextual help and protected asynchronous dialogs/drafts.
- [ ] Full twelve-requirement native browser journey, historical commit/revision checks and persistence recovery.
- [ ] Review module dependencies individually, synchronize latest main, run canonical verification and inspect existing CI checks.
- [ ] Independent final review, update QA evidence, publish one PR and resolve failing checks.

## Recovery and evidence discipline

The initial recovery snapshot is on `codex/coherent-architecture-lifecycle`. Source reconstruction is not considered verified merely because an earlier lost version passed. Record newly executed checks, failures and fixes in the QA report. Keep focused tests during diagnosis; canonical verification remains `./mvnw -B verify -Pci -DrunOnnxTests=true` without new exclusions or reduced gates.

Use one Maven reactor at a time. Keep runtime downloads and intermediate evidence outside the original scratch tree. Save coherent source checkpoints to the remote branch while recovery proceeds. A partial checkpoint is not a completion claim or an instruction to merge.
