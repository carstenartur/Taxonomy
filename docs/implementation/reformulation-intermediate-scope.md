# PR 1135 — useful intermediate delivery

The user explicitly authorized merging usable intermediate states on 2026-09-27.
This supersedes the earlier no-automatic-merge instruction for a tested and
independently reviewed intermediate delivery; it does not waive correctness gates.

## Proposed merge boundary

- Tasks 1 and 2: rejected wording safeguards, adopted lineage, inherited decisions,
  scope binding and positive-review protection, including current review fixes.
- Task 3: historical DOCX revision/receipt output using frozen architecture and
  gap evidence, deterministic restart output and independently inspected DE/EN render.
- Covering regression evidence, review of the complete intermediate branch and
  applicable current-head CI must pass before merge.

The older-source/current-version distinction must remain supported: an acknowledged
preview can adopt a proposal based on an older immutable source. Each source,
preview-current and resulting target must be checked against its own coordinates.

## Work retained after this merge

Task 4 in `reformulation-task4.md` remains required: semantic civilian playback,
the complete analysis-to-adoption/reanalysis/export/restart browser scenario,
explicit verification selectors, DE/EN help and the separate real-provider quality
comparison. Existing playback and export tests do not establish actual model
quality or complete acceptance of the original eight-package specification.

No new issue is needed to retain this scope. The plan, progress and next task brief
remain versioned and the continuation will use a new branch/PR after integration.

Ruling: merge Tasks 1–3 once their own full gates pass, then continue Task 4 —
the user authorized useful intermediate delivery — cost if wrong: an additional
integration boundary; no claim that the complete product specification is finished.
