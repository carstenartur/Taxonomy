# Draft reload QA follow-up — 2026-10-06

Base: `11d099b858cddbbaa15b6f0b2a69f04a56534ae2`, the merged PR #1175. Its
verified source tree and completed canonical gates are recorded in the
[preceding QA report](2026-10-06-remaining-qa.md#final-canonical-acceptance-of-pr-1175).
This follow-up fixes reproduced draft lifecycle defects. It does not identify
the deployment-specific cause of [#1100](https://github.com/carstenartur/Taxonomy/issues/1100).

## Reproduced defects and corrections

| Defect | Correction and regression protection |
| --- | --- |
| A forced draft reload unconditionally applied its late GET response, replacing requirement text or newer analysis state created while the request was pending. Initial restoration also lost an intervening edit returned to blank. | Capture requirement value/edit revision at request start. Forced reload also captures the comparable analysis payload. Apply only if those captured values still match; otherwise retain the explicit local-versus-saved choice and its autosave barrier. Tests cover typing, clearing, edits away and back, programmatic score changes, and unchanged intentional reload. |
| Further input could replace the unresolved draft choice with stale-analysis actions. Keeping local could leave the resolved choice displayed instead of stale actions. | Stale-status rendering respects the public restoring/conflict state. Keeping local clears only the resolved resume-choice message and exposes existing stale actions when appropriate. Tests exercise both actual legacy and modern input callbacks. |
| File → Save Draft could replace an unresolved choice with `draft-save-failed`, leaving the decision barrier active with no resolution buttons. | Include the menu item in the decision guard and preserve pending restoration/choice feedback when an explicit save completes. Regression proves the blocked save retains the choice, then keeping local permits a successful save. |
| Project/error feedback could also remove unresolved resume or conflict controls. | The common action-alert renderer retains the pending decision and at most one additional feedback alert. Replacing progress with an error preserves the original controls and live-region announcement. |
| Confirmed New Analysis could leave a stale restore barrier after successful reset; a failed reset could prematurely release an existing conflict barrier. | Release restoration/decision state only after the authoritative reset succeeds. Retain the conflict flag until that successful response. Tests cover declined confirmation, successful reset, failed reset from a resume choice, and failed reset from a genuine conflict with no subsequent PUT permitted. |

The server revision is still adopted through the existing serialized transport;
it is not frozen by this change. The existing restoration/decision barrier prevents
unapproved autosave. Choosing local intentionally adopts the read revision before
saving, so optimistic concurrency remains effective against subsequent writes.
No server authorization, compare-and-swap, model, selector, timeout or coverage
threshold is relaxed. Initial option/provider hydration remains unchanged; only
forced reload compares the complete payload so normal startup defaults cannot
spuriously force a resume choice.

## Verification

| Evidence | Result |
| --- | --- |
| New draft-load regressions before the fix | 13 cases: five intended assertion failures, eight passed. |
| Choice/stale regressions before their fix | 29 cases: two intended assertion failures, 27 passed. |
| Review-discovered explicit-save regression before its fix | 30 cases: one intended `draft-save-failed` versus `resume-choice` failure, 29 passed. |
| Additional lifecycle-feedback/reset regressions before their fix | 34 cases: four intended failures, 30 passed. A further genuine-conflict/reset case then failed against the partial correction (35 cases, 34 passed), proving that preserving only the visible choice was insufficient. |
| Final combined draft, serialization, startup, Preferences, base-path and workflow contracts | 110 passed, zero failures/skips. |
| Complete `npm run verify:ui-contracts`, JDK 21 on PATH | 846 passed, zero failures/skips. |
| Independent read-only review | Found the explicit-save defect and the failed-reset/conflict authority gap; both were reproduced and corrected. Final review found no remaining Critical/Important findings; 57 focused cases passed independently. The real browser project interception was also corrected to accept workspace query strings. |
| Real browser regression against the unchanged packaged baseline | Failed specifically with “Late explicit draft reload overwrote newer input or current analysis evidence.” |
| Final rebuilt application/browser run | Passed: 38 checks including 13 axe audits, zero violations. One application start, 85,660 ms measured application/browser work; finished `2026-10-06T10:28:25.274Z`. |

The browser fixture gates the real draft GET during public `reload()`, edits the
requirement, releases the response and compares the complete working snapshot.
It edits again while the decision is pending, invokes the actual stale action and
explicit Save Draft handler, clicks Project → Save as requirement with a controlled
failed project GET, keeps local, verifies the authoritative saved draft,
then reloads the page. Distinct raw/effective scores (91/77), architecture, reasons,
rejected hypotheses and session-applied decisions must survive. It restores the
original completed fixture and continues the existing Git Undo and repeated-save
checks. The disposable workspace and original workspace cleanup are retained.

The final application JAR SHA-256 is
`d2571b48f93f94c02de5d6fbc9e3375769825eb357cd5b518c9e5320e62b9994`.
All changed production JavaScript resources byte-match their packaged
copies. [Machine-readable local evidence](https://github.com/carstenartur/Taxonomy/blob/eaca4233311dc82b6de227d1f402ab5e14c2e07b/docs/qa/evidence/2026-10-06/draft-reload-followup.json)
records those resource/test hashes, RED/GREEN counts, actual browser check names
and original log hashes. Assembly used Java 21 and Maven's application package
goal with exact installed sibling artifacts and build-cache restoration disabled;
`-DskipTests` assembly is not presented as a reactor test run.

The local browser execution uses Maven's existing `run-authoritative-ui-shard`
goal with the pinned installed Chromium, primary suite and
`primary-admin-chromium` profile. It is a focused profile, not the full canonical
shard matrix. Local timing fields for source commit and JAR hash remain null;
separately recorded JAR hashing and packaged-resource byte comparisons are narrower
evidence, not a canonical source-bound UI certificate. External inference is mocked
for this UI flow. Final branch CI is required independently of the predecessor PR.

## Retrieval diagnostic: rejected alternatives

The pinned MiniLM profile, production formatter and all 2,572 real nodes were
used to generate fresh full-document and title vectors. Exact cosine ranking
compared production text, title only, a normalized equal sum of both vectors and
maximum document/title similarity against the same fixed sixteen queries. No
query translation, catalogue-ID boost, fitted coefficient, model download or
external provider was used. The
[machine-readable results](https://github.com/carstenartur/Taxonomy/blob/eaca4233311dc82b6de227d1f402ab5e14c2e07b/docs/qa/evidence/2026-10-06/retrieval-text-ablation.json)
retain all 64 observations.

| Reference | Production rank | Title only | Equal blend | Maximum similarity |
| --- | ---: | ---: | ---: | ---: |
| Original EN documents | 1 | 17 | 1 | 1 |
| Original DE documents | 1 | 26 | 2 | 1 |
| EN document paraphrase | 4 | 43 | 14 | 8 |
| DE payroll paraphrase | 25 | 42 | 23 | 43 |
| EN ambiguity: documents / email | 24 / 1 | 197 / 5 | 62 / 2 | 40 / 4 |
| DE ambiguity: documents / email | 113 / 38 | 505 / 234 | 299 / 116 | 191 / 91 |

All six original production anchors reproduce rank 1. None of the three
alternatives repairs the remaining misses; each worsens other ranks. Both artillery
distractors exclude all three targeted office/payroll references from ten hits in
every mode. These are targeted observations, not calibrated precision or held-out
quality estimates. The alternatives are rejected and production retrieval remains
unchanged. Because the misses occur in exact vector ranking, increasing ANN
exploration alone cannot repair them. Payroll's short catalogue description also
does not support simple document truncation as that query's explanation.

## Deployment boundary

Read-only checks of `/login` and `/actuator/info` at
`https://taxonomy-analyzer.onrender.com` returned HTTP 503 on 2026-10-06. The
latest completed [Delivery run inspected, 37418379710](https://github.com/carstenartur/Taxonomy/actions/runs/37418379710),
for `1de21a0a202b4f7f92ce0dbcee6afeb8237697b7`, explicitly reported Render
disabled in job `112123203347`; deployment job `112123204636` was skipped.
The disabled-status artifact is `11391403525`. Docker/report publication passed,
but no rollout-success check was emitted. These facts do not identify the code
served during the original #1100 report. No deployment was enabled and no issue
was closed during this follow-up.
