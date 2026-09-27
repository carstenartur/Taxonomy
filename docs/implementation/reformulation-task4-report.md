# Task 4 report

## Task 4a — tests-first checkpoint, 2026-09-27

Only the remote-response corpus and contract tests are in scope. Existing flood
fixture and relation playback remain unchanged. Two authored fixtures explicitly
label test assumptions, including terminal-only/no-browser, numeric limits and
unmapped original content. Catalogue IDs reuse established flood bindings.

New contracts exercise NODE, RECONCILE and REWORD without invocation-order dispatch,
full exact source, child and boundary identities, answer values/state, caught
unknown calls and duplicate fixture scopes. REWORD has no separate wire task field:
it uses the production node prompt with affected-section preservation instructions.
Fixtures here are provider replies, not prepared application architectures.

RED evidence at published test tree `9fcc0ac5ae5fd9019c5001fee123b0a267e34794`:
10 tests, 1 failure, 1 error, no skips. New positive dispatch failed with
`Missing requirement`; duplicate reformulation scopes were silently accepted.
The existing relation contracts passed. Log: scratch `task4a-red.log`.

Implementation adds semantic reformulation dispatch, exact source/hash validation,
child/section identities, complete boundary-edge content and answer history/state.
Malformed calls are retained in the shared fatal ledger even if callers catch them;
all three coverage entry points inspect that ledger. REWORD is distinguished using
the actual affected-section preservation contracts. A real production prompt-builder
and response-parser contract covers the wire boundary. No production code changed.

GREEN evidence at published commit `c4bd1b51f9fcef991bcd12e124f664ed79bc245c`,
tree `97289619b4b34d0db6450987ed78b1006a8a95df`: `ScenarioLlmPlaybackTest`
11 tests, zero failures/errors/skips, BUILD SUCCESS, finished 2026-09-27
17:04:18 UTC (Maven printed 19:04:18 +02:00). Scratch log `task4a-green.log`.
Exact focused command used for both RED and GREEN:

```sh
python .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ScenarioLlmPlaybackTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The class is already explicitly selected by the `civilian-acceptance` profile
and `.mvn/verification-suites.json`; no competing selector was added.
Next step: independent Task 4a review of the published source tree and this report.
Implementation stops here until review. The fixture responses are initial protocol
contracts, not yet the complete application-path corpus: real selected graph IDs,
statement/question preservation and authored score fronts must be bound from actual
application requests in Task 4b without injecting prepared state.

Real application/browser/restart, fixture score
selection, exports, CI and real-provider quality gates remain open (Task 4b+).

## Resumed 2026-09-27

Task 4a accepted source recovered from 696130c8; full approved PR1135 corrections
through 43992e1d merged without discarding earlier Task4 work. The original exact
unapplied Task4b test draft is now applied with AnalysisStatus.SUCCESS corrected
from its unverified COMPLETED assumption. Test-only checkpoint precedes execution.
Expected RED: the sourced flood remote corpus has no application reformulation
responses; observed actual prompt identities will bind authored response cases.
No application memory/provider budgets or source requirements are changed.

### Task 4b application corpus checkpoint

Initial HTTP acceptance RED: normal analysis returned PARTIAL because the original
eight-front flood corpus exhausted the unchanged 24-call relation-search budget
(37 unfinished batches). A separate authored BP/IP score front now runs actual
analysis to SUCCESS under the defaults; the sourced flood text/bindings remain
unchanged. The original corpus remains the authority for the existing broad test.
Independent root-zero response contract: RED 12 tests / 1 error (`Budget mismatch:
BR`), then GREEN 12/12. Sibling budget validation remains enforced. The HTTP test
then reached the real NODE/BP-1060 provider request and failed on its missing reply.

New explicitly scoped BP/IP replies bind only runtime statement/question IDs from
validated input. They author shared and separate numeric decisions without inserting
application/DB/architecture state. Full prompts and fatal unmatched calls are saved
as test artifacts. Proposal execution is under test; no end-to-end pass claimed.
Next: finish the authenticated answer/reword/adoption/export/restart path, then
browser and separate real-provider comparison. Focused command:
`./mvnw -pl taxonomy-app -am test -Dtest=ScenarioLlmPlaybackTest,ReformulationCivilianAcceptanceTest -Dsurefire.failIfNoSpecifiedTests=false`.

### Lifecycle checkpoint

The HTTP driver now creates answers and an explicit deferral, edits a statement,
checks stale If-Match rejection, invokes targeted synthesis, compares the historical
revision and frozen architecture, previews/confirms/replays an adoption command,
downloads four formats, runs actual post-adoption analysis/new offer, checkpoints,
and starts a separate file-database application process for history/lineage checks.
Still under verification: no complete lifecycle success has been recorded yet.
Observed run lists are oldest-first; the driver now waits for all returned runs to
be terminal and asserts the newly returned run ID, never an older completed run.
No model result is injected into the application.

### Post-adoption regression and real-model runner

Actual HTTP evidence now reaches: initial SUCCESS analysis, completed eight-node
walk-up, merged/shared and distinct numeric questions, answer+deferral, completed
targeted rewording, protected manual variant (PARTIAL / MANUAL_DRAFT_PROTECTED with
a retained candidate and unchanged human revision), explicit adoption and duplicate
command replay, four exports, and successful actual reanalysis. The new offer after
adoption fails INPUT_TOO_LARGE_FOR_PROVIDER: inherited question discoveries repeat
the same long catalogue context, including retained origins. A focused lossless
round-trip/budget regression is added before changing prompt encoding. The 120000
character / 262144 byte / 30000 estimated-token default remains unchanged.

A separate `reformulation-real-llm` Maven profile and tagged comparison class now
cover five authored inputs with the same source, real catalogue front, provider and
model for walk-up and one complete prompt. They write actual transport attempts,
available usage, duration, validated outputs and a blank human-quality rubric.
Source/front assumptions are explicit. Missing credentials produce NOT_RUN, not
playback results. Provider environment presence was checked: no standard provider
keys/custom endpoint were configured. No real-provider or human quality pass claimed.

### Lossless inherited-context correction

Focused regression RED: 2 tests, 2 failures, no errors/skips. Node and reconciliation
prompts repeated all historical discovery contexts and exceeded the unchanged
production budget. Both builders now include historical questions and their origins
in the existing request-local discovery dictionary. Input encoding identity advances
to v5 so old step checkpoints cannot masquerade as this prompt encoding. No archive,
source, answer, rejection or unique context is discarded.
The new round-trip/default-budget regressions and existing adopted-lineage and
reconciliation context suites pass locally (BUILD SUCCESS, 2026-09-27 20:13 UTC).
Full authenticated lifecycle is being rerun against this correction.

### Full HTTP lifecycle GREEN; browser/document integration checkpoint

2026-09-27 20:22:46 UTC: ReformulationCivilianAcceptanceTest 1/1 and
ScenarioLlmPlaybackTest 14/14 passed, no skips, BUILD SUCCESS. The first application
created/analyzed/reformulated, answered/deferred, reworded, protected a manual variant,
adopted explicitly, replayed the command, exported JSON/Markdown/HTML/DOCX, performed
real reanalysis/new offer, checked concrete inherited decisions, created a checkpoint,
materialized it and rejected reads in a separately provisioned workspace. A fresh
application process then verified the same decisions, current requirement and all
four historical exports (DOCX paragraph content, excluding ZIP metadata).
Artifact directory: `taxonomy-app/target/reformulation-civilian-acceptance/run-fd0697c5-bb70-446c-9ed9-206977888e6e`.

A separate real-browser lifecycle class is explicitly selected by the civilian Maven
profile: desktop, exact 390px viewport, keyboard answer/focus, live status, unsaved
draft retention, actual JSON download, disabled-before-confirmation preview and
explicit browser adoption. Browser execution is still pending; no local Docker or
Chrome was available in the capability check. CI preserves screenshots and failures.

Independent LibreOffice rendering measured 25 pages for the actual saved revision
and 26 for its receipt. The existing document checker is extended for paired JSON
content, empty-body rejection, page images/hashes and separate measured limits
28/29 (three-page renderer/extra-browser-answer margin); existing 74/12 limits are
unchanged. New page-budget regression failed before the implementation, as intended.
CI also requires positive named JUnit evidence. Database jobs now retain the child
reanalysis log identified as a review follow-up.

### Authored scenario application checkpoint

The time-recording and cross-taxonomy authored fixtures now traverse actual analysis
and offer creation as separately selected JUnit cases. They assert terminal-only /
no-browser and numerical source constraints, explicit unmapped content and real
question generation, without treating authored scores as model-quality evidence.
Verification is pending at this checkpoint; the prior process produced no final
JUnit result. Next command: focused `ReformulationAuthoredScenarioTest`, then the
complete civilian profile in CI and independent final review.

2026-09-27 20:35:57 UTC: authored application scenarios 2/2 passed, no failures,
errors or skips, BUILD SUCCESS. The documented terminal-only/browser exclusion,
numerical and unmapped constraints survive the actual application path. German and
English user/architecture guides, README and the consolidated acceptance document
now describe the implemented lifecycle and distinguish the pending real-model gate.

A local Chrome/ChromeDriver 154.0.8037.57 runtime is now available from Selenium
Manager. The new browser lifecycle is being verified against the real application;
no browser pass is claimed at this checkpoint.

### Independent review corrections

The independent scoped review found two Important test-authority gaps, no Critical
production finding: the application corpus did not bind answer IDs/states/history
strictly enough, and the lifecycle lacked an actual in-flight publication race.
New answer mutation test RED: 15 tests / one failure (a known-but-wrong question ID
was accepted). Strict semantic question/answer/history checks now run for NODE/REWORD
and RECONCILE; the focused suite is GREEN 15/15. It rejects swapped questions,
duplicate events, deferred values, wrong state/scope/disposition/other/rationale and
unknown superseded IDs. Browser rationale input is explicitly replaced, not appended.

The HTTP lifecycle now holds a validated provider response, verifies the exact run
is RUNNING, saves a human revision through HTTP, releases the response and requires
a retained candidate with no published revision and the newer human text unchanged.
Restart also checks the fatal playback ledger; inherited human answers and all three
question IDs/keys/states are checked directly with nonempty source provenance.
The extended lifecycle is pending verification at this checkpoint.

Local Chrome startup was blocked before navigation: process_singleton_posix socket()
returned Operation not permitted. No local browser pass and no policy bypass. The
existing GitHub browser lane is running. The explicit real-model profile wrote
NOT_RUN / NOT_REVIEWED (2026-09-27 20:42 UTC), with one JUnit skip because no generative
provider is configured. A successful launcher build does not mean model quality passed.

### Post-review execution evidence

2026-09-27 20:52:49 UTC: final focused reactor BUILD SUCCESS, 18/18, no failures,
errors or skips: authored scenarios 2, full HTTP/in-flight race/adoption/reanalysis/
export/checkpoint/fresh-process lifecycle 1, semantic playback contracts 15.
Artifacts: `target/reformulation-civilian-acceptance/run-7a238133-f6cf-4205-bc95-2bae39098c6a`.

Actual document-CLI invocation first failed because its new flag was not registered.
The explicit parser regression reproduced that failure (10 tests / one error).
After the minimal flag correction, 10/10 document-tooling tests passed. The exact
CI command then rendered both exports from the final successful lifecycle with
LibreOffice/Poppler: revision 25 pages / 7 content checks; adoption 26 pages / 8
content checks; neither has empty body pages. First/last sample pages were visually
inspected. No page limit or content assertion was relaxed.

Independent final review and focused correction rereviews are accepted; see
`reformulation-task4-final-review.md`. PR #1136 contains the follow-up implementation.
PR #1135 still waits for its current-head canonical Core/Maven verification before
merge; PR #1136 browser and full current-head CI remain open. Real-provider evidence
is explicitly NOT_RUN, with the exact small status report committed alongside this
ledger. No claim of complete eight-package product-quality acceptance is made.

### Intermediate integration

PR #1135 merged at 2026-09-27 21:00:13 UTC after its complete gates succeeded;
confirmed merge SHA `13c0a345f4f8de3d56d52e1de54cb2723e20a8d2`. PR #1136 now targets
main. Its squash-history reconciliation preserves the follow-up tree: main was
verified byte-identical to the already-contained reviewed 43992e1d ancestor before
resolving the mechanical conflicts. No application change was discarded. Current
follow-up browser/full CI remain required separately from local 18/18 evidence.

### Final review edge cases and actual browser failure

Current-head civilian CI on `4ce5649d` completed with 24 tests / one failure:
`ReformulationCivilianBrowserTest` clicked the proposal tab while it was moving
under the fixed navbar (ElementClickInterceptedException, 2026-09-27 21:09 UTC).
The other browser contract (4), HTTP lifecycle (1), authored scenarios (2), playback
(15) and existing civilian lifecycle (1) passed. The new driver now uses the existing
browser contract's native hit-target and stable-geometry wait; it does not invoke
JavaScript clicks, hide the header or increase timeouts. Current-head reexecution is
still required.

The automatic review's child-prompt aggregation finding was not reproduced: the
parent logs the prompt before dispatch, so aggregation would duplicate it. A direct
contract now proves original and adopted prompts are recorded exactly once. Three
other review-summary cases were checked rather than dismissed: small inherited
context encoding grew due to instruction overhead (RED 3 tests / one failure),
blank fixture answer IDs were accepted (RED 17 tests / one failure), and a repeated
actual DOCX render reported 25 pages but retained a synthetic prior page-99 image
(26 image hashes). These are being corrected without changing production budgets.

Prompt encoding v6 selects the dictionary only when its full instruction+payload
improves Unicode-character and UTF-8 byte budgets. The reconciliation rule is reused
unchanged through a shared helper. The answer corpus requires nonblank event IDs.
The work is remotely checkpointed before further verification.

2026-09-27 21:29 UTC: document-evidence regression reproduced a stale success
manifest (11 tests / one failure). The renderer now invalidates that manifest
before starting, clears only the selected document's generated PDF/text/numeric
page images, and requires one image per actual PDF page. Its regression verifies
other documents and unrelated files survive. Tooling is GREEN 12/12, no failures,
errors or skips. The final prompt-budget/playback/application run is in progress;
native browser and full CI are still required on the final published head.

2026-09-27 21:38 UTC: current implementation evidence after the edge corrections:
prompt-budget/round-trip tests 3/3; playback contracts 17/17; document tooling 12/12.
The separate real HTTP lifecycle passed 1/1 at 21:37:48 UTC, including both JVMs.
Fresh run `26badadb-7483-4f05-a799-8830c843cdfc` rendered revision 25 pages/images
and adoption 26 pages/images, with 7/8 content assertions and no empty body pages.
A deliberately stale page-99 image was removed. An earlier interrupted combined
run is not counted as passed.

CI on `15a4c70e` completed the civilian tests with 26 tests / one browser failure;
the other 25 passed. The failure screenshot and HTML show that the Questions tab
had not activated, so the radio control remained in a hidden panel. The browser
driver now uses the repository's existing instant-scroll convention, retains the
stable native hit-target guard, and asserts the selected tab and visible panel
before any answer operation. It also waits for the actual asynchronous comparison
result and records click geometry on failure. No JavaScript click, hidden-header
workaround, timeout increase or omitted assertion is introduced. These browser
corrections require current-head CI execution; no browser success is claimed yet.
