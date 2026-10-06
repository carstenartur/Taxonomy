# Preferences state investigation — 2026-10-06

The investigation starts at `1de21a0a202b4f7f92ce0dbcee6afeb8237697b7`,
which already includes [PR #1174](https://github.com/carstenartur/Taxonomy/pull/1174).
The original [issue #1100](https://github.com/carstenartur/Taxonomy/issues/1100)
reported completed architecture → Preferences → node limit 50 → 150 → Save,
followed by an empty working UI and an offer to restore a draft. That cause
remains unconfirmed. The newly reproduced defect below loses a newer edit in
the Preferences form; it does not establish why the reported analysis disappeared.

## Existing evidence and remaining investigation

The merged browser scenario already covers 50 → 150, failed preference writes,
pending draft autosave, a pending initial draft read, navigation to Architecture
and Analyze, reload, restored persisted reject/session-apply decisions and Git
Undo. Its full working-state comparison includes distinct raw/effective scores,
reasons, graph elements and hypotheses. It provisions a disposable workspace,
checks actual draft/review authority and restores the original workspace. These
cases are existing coverage, not newly discovered gaps.

| Candidate | Observed evidence in the current source and contracts | Remaining boundary |
|---|---|---|
| Preferences Save submits a page form or reloads the document | Save and Reset are `type="button"`; the Preferences pane is outside a form. Their handlers only request the Preferences API and update Preferences controls/status. | Does not identify the code served during the original report. |
| Preferences navigation clears derived analysis | `activatePage` changes pane visibility, layout and hash, then dispatches `taxonomy:page-activated`. The Preferences listener only loads runtime preferences. | A deployed asset mismatch or external navigation requires evidence from that deployment. |
| Draft GET races an autosave and regresses its revision | Production transport queues draft GET/PUT/DELETE/reset operations. Existing serialization contracts exercise delayed reads/writes and locally acknowledged versus genuine cross-tab conflicts. | The isolated draft-module fixture alone would not prove production transport behavior. |
| A late initial restore incorrectly discards local input | Existing draft contracts hold the restore barrier and preserve a local-versus-saved choice when input changes during the read. The merged browser gates the real initial GET while Preferences remain usable. | A restore offer alone does not establish lost server data or authorize silently choosing a saved state. |
| Workspace selection changes the visible draft | Workspace switching has its own successful-switch reload path and remembered tab workspace. Preferences Save never invokes it; existing startup/base-path contracts verify pinned resolution. | No observed workspace switch is linked to the original sequence. |
| A pending Preferences response overwrites a newer field edit | Reproduced for GET, PUT and reset POST in the actual inline Preferences script. The response unconditionally populated every field. | This explains loss of a Preferences edit, not loss of scores/architecture. |

## Reproduced defect and correction

Start a Preferences request, edit the node-limit field again before its response,
then complete that response. Before the correction, a newer local `175` reverted
to the response's `50` or `150`. All three new regressions failed on that exact
value comparison; all fifteen prior Preferences cases passed in the same RED run.

Independent review then reproduced an additional edit-history case: begin a
reset with the field at `150`, edit it to `175` and back to `150`, then complete
the reset response containing `50`. Comparing only the current value with its
request-start snapshot still discarded that intervening edit. Six further RED
cases cover number inputs and checkbox controls edited away and back during GET,
PUT and reset POST. All six failed before the review correction while the existing
nineteen cases passed.

Each request now captures both field values and their per-field input/change edit
revisions. Its successful response still updates the acknowledged `currentPrefs`
authority, but hydrates only fields whose value and edit revision are unchanged
since that capture. Revision tracking preserves an intervening edit even when it
returns to the initial value; value comparison also protects a newer programmatic
change without an event. A retained value that differs from the acknowledged
server state leaves Save enabled; an equal value correctly needs no further save.
Untouched controls still receive the response normally. The fix does not call
draft restoration, invalidation, inference or workspace switching.

The expanded unit fixture checks distinct raw `91` and effective `77` scores.
A reduction case sets the node limit to `1` while an existing graph contains two
elements and a rejected hypothesis. It verifies unchanged complete local and
persisted draft evidence, no draft mutation and no analysis request.

The real browser fixture now includes an independently gated Preferences PUT:
submit `149`, type `175` while that request is pending, release it, then require
acknowledged server value `149`, visible unsaved value `175` and an enabled Save
action. It also explicitly reduces `150` to `1` beneath the existing graph size.
The additional graph elements use identities discovered by the existing real
catalogue/materialized-hypothesis fixture. It checks full working-state equality,
the authoritative unpruned graph and visible persisted manual decisions before
restoring the limit. The existing inference-request observer and workspace cleanup
remain active across these steps.

## Verification

| Command / evidence | Result |
|---|---|
| `node --test taxonomy-app/src/test/js/preferences-analysis-workspace.cjs` before the production fix | 18 cases: 3 intended newer-edit failures, 15 prior cases passed. |
| Same Preferences selection after the initial fix and reduction regression | 19 passed; no failures or skips. |
| Preferences selection before the review correction | 25 cases: 6 intended edit-history failures, 19 prior cases passed. |
| Same Preferences selection after edit-revision correction | 25 passed; no failures or skips. |
| `node --test taxonomy-app/src/test/js/preferences-analysis-workspace.cjs .github/scripts/analysis-session-draft.test.mjs .github/scripts/analysis-session-draft-serialization.test.mjs .github/scripts/analysis-session-startup.test.mjs .github/scripts/ui-primary-session-basepath-contract.test.mjs` | Final combined run: 69 passed; no failures or skips. Existing startup failure-path cases print their expected workspace-unavailable warnings. |
| Preferences plus browser fixture/base-path contracts after browser changes | 36 passed; no failures or skips. |
| `node --check .github/scripts/ui-primary-preferences-workflow.mjs` | Passed. |
| `npm run verify:ui-contracts` | Stopped at `document-template-local-edit-fixtures.test.mjs`: `spawn jar ENOENT` because Java tools were not on the initial PATH. |
| Same complete contract command with the known JDK 21 `bin` prepended | Passed the document-template prerequisite, then stopped at `ui-primary-evidence.test.mjs`: `ERR_MODULE_NOT_FOUND` for `@axe-core/playwright` in this checkout. This is a missing dependency, not a completed full-contract gate. |
| `./mvnw -f .github/ui-verification-pom.xml -Pcontracts test`, with the final edit-revision correction | Maven installed the pinned dependencies and all 832 reported Node contract cases passed; zero failures. This supersedes the earlier missing-dependency attempts. |
| Focused Java 21 reactor package including `PreferencesUiContractTest`, logging, authorization and SARIF suites | 168 Java tests passed across 13 classes; zero failures, errors or skips. The packaged template was byte-compared with the final source. |
| Maven-owned `run-authoritative-ui-shard` execution, `primary-admin-chromium`, using the already installed pinned Chromium | Passed: 37 checks, including 13 axe audits, zero violations. Includes the pending-save/newer-edit case, 150 → 1 reduction, working-state preservation, reload and restored decisions. One application start; 99.6 seconds of measured browser/application work. |

The complete shard lifecycle initially stopped while installing OS libraries:
the local runtime rejected `sudo`'s `setresuid`. The existing pinned browser was
therefore used through Maven's unchanged execution for the focused run above.
This is focused browser evidence, not completion of the full shard lifecycle.
The local timing metadata has null source-commit and application-JAR hash fields;
the recorded template byte comparison is narrower than a commit/JAR-bound UI
shard certificate. The [final local QA report](2026-10-06-remaining-qa.md) preserves
this distinction and the remaining canonical CI requirements.
The complete reactor, UI shard matrix, databases and security gates are reported
separately. No coverage threshold, timeout, selector or security baseline is
weakened by these Preferences edits.

The separate deployment check retrieved
[Delivery run 37346907940](https://github.com/carstenartur/Taxonomy/actions/runs/37346907940),
whose status job `111889835297` reported "Render deployment disabled" and whose
deployment job was skipped. Its artifact `11360813270`
(`qa4-render-delivery-status.zip`) records `deploymentState` and `result` as
`disabled`, expected commit `ce78dad9d2f9a52211fd7a1459364b1d8f7f08b5`, target
`https://taxonomy-analyzer.onrender.com` and completion
`2026-10-05T17:19:25+00:00`. Public login and actuator-info checks returned HTTP
503 on the current check. These are delivery/availability facts: a disabled
delivery does not establish which older version was serving the original report,
and the unavailable endpoints cannot identify current frontend assets. The
observed form race must not be relabeled as issue #1100's root cause or used to
justify automatic restoration or closure of that report.
