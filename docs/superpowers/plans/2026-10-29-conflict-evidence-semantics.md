# Conflict evidence semantics

The product assessment identified misleading confidence presentation. The current
conflict detector assigns fixed weights to text rules; it does not estimate or
calibrate the probability that a conflict exists. Human review is a separate state.

## Scope and contract

- Keep existing detection, stored values, ordering and review decisions unchanged.
- Add an explicit `UNCALIBRATED_RULE_WEIGHT` basis to the conflict response. Preserve
  the existing numeric `confidence` field for clients and retained data.
- Show a rule weight on a 0–100 scale with a plain explanation in both the conflict
  card and its review dialog. Unknown or invalid values remain unavailable.
- Copy the rendered assessment through an explicit DOM hook; translated words must
  never determine which evidence reaches the review dialog.
- Keep a confirmed/rejected/resolved human decision visibly separate from the rule
  weight. Do not infer that confirmation calibrated the original score.

## Verification plan

1. Add a response test that initially fails because the basis is absent; exercise
   proposed and reviewed conflicts with real entities.
2. Add Maven-owned JavaScript contracts that exercise the production renderers in
   English/German, explicit zero, missing/invalid values and localized dialog data.
3. Implement the response and UI changes, run focused Maven tests, then run the
   owning module's broader tests and relevant existing evidence contracts.

This is an independent main-based change. Snapshot adoption, inventory criteria
and real-model evidence are separate changes with no dependency on this branch.
