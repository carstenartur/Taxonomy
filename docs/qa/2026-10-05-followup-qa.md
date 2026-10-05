# QA follow-up — diagnostic privacy and restored review decisions

This follows the [comprehensive QA](2026-10-05-comprehensive-qa.md) integrated
through [PR #1173](https://github.com/carstenartur/Taxonomy/pull/1173).
The follow-up starts at `ce78dad9d2f9a52211fd7a1459364b1d8f7f08b5`.

## Reproduced defects and corrections

| Area | Reproduced defect | Correction and preserved behavior |
|---|---|---|
| Full-text and semantic search | Backend and inference failures copied the query and exception message into ERROR diagnostics, including synthetic private text and line breaks. | Fixed operation/error codes replace untrusted payloads. Empty-result fallback, readiness policy and predictor cleanup remain unchanged. |
| PDF/DOCX parsing | Successful imports copied the uploaded filename into INFO diagnostics. | Retain page/candidate counts and truncation flags; return the original filename and extracted content to the importing caller as before. |
| Document import | Five parser, registration, AI extraction, mapping and candidate-confirmation failure paths copied filenames or raw exceptions into logs. | Keep severity and fixed error codes without private inputs or throwable payloads. Existing HTTP status and response contracts remain intact. |
| Central exception handling | Validation, conflict, access-denied, generic, MVC and disconnect diagnostics included exception text, request paths or nested causes. | Fixed operation/status/category metadata replaces payloads, including DEBUG/TRACE disconnect diagnostics. Spring's disconnect classifier, response localization and headers remain authoritative. |
| Restored hypothesis review | Rendering a saved draft ignored accepted/rejected status and the session-applied flag, reoffered completed actions and lost Git Undo. The live bulk action also stayed visible after the last eligible decision. | Render stored decisions, reinstall the existing Undo command and guard repeated review/apply requests. Unsupported statuses remain read-only. Refresh bulk eligibility after live decisions and Undo without replacing row-level pending/error outcomes. |
| Local browser verification | The documented `-Pui-tests -DskipTests` command returned `BUILD SUCCESS` while the frontend plugin silently skipped both required verification executions. | Those two frontend executions explicitly disable their generic `skipTests` shortcut; `taxonomy.ui.skip` continues to govern browser/transport execution independently of Java test selection. |

## Verification approach

The privacy regressions capture actual logging events, arguments and throwable
proxies. Search/backend/provider failures use deterministic boundary doubles;
the PDF and DOCX cases parse real documents. No remote model call is needed.

Before production edits, the search suite ran four cases with three intended
privacy failures and one passing blank-query case. The app suite ran eighteen
cases with eighteen intended failures and no execution errors. A test compilation
error was corrected before that app RED run and is not counted as defect evidence.

The review-state regressions load the real renderer, command adapter, payload
serializer and draft lifecycle. The initial suite failed five restoration cases;
three additional bulk-control cases then exposed inappropriate completed-row
actions. Three further live-decision cases cover sequential reject/apply/Undo,
bulk acceptance and partial pending/error outcomes before any draft rerender;
two failed before the header-only refresh correction. The resulting Preferences
suite passes fifteen cases. Its combined draft, startup and relationship
selection passes sixty-two cases.

Independent Java review additionally found Spring's inherited committed-response
warning and a test logger blind spot. A real-handler regression reproduced that
warning before an early committed-response guard removed it. The inherited HTTP
405 warning similarly exposed a private custom-method token; an override now
routes it through the safe internal handler while preserving the response and
`Allow` header. Both regressions failed for the intended disclosure before their
fixes and now pass. Logger configuration is unchanged.

| Local verification | Result |
|---|---|
| Focused Maven search, document, handler, Preferences and review-contract selection | 111 tests passed, no failures, errors or skips. |
| Final Maven handler selection after the additional 405 correction (`GlobalExceptionHandlerDiagnosticPrivacyTest`, `GlobalExceptionHandlerTest`, `GlobalExceptionHandlerDisconnectTest`, `DecisionReportTemplateExceptionHandlerTest`) | 36 tests passed, including all 14 handler privacy/contract cases; no failures, errors or skips. This overlaps the previous selection and is not an additional 36 unique cases. |
| Actual renderer/adapter/draft JavaScript selection | 62 tests passed, including 15 Preferences/restoration/live-decision cases. |
| Browser fixture/evidence support tests | 7 tests passed. |
| Workspace fixture path, lifecycle, readiness, request-gate and persisted-decision contracts | 17 tests passed; combined with the 15 Preferences/restoration cases, 32 passed. This selection overlaps those cases above. |
| Real workspace materialization/commit contract | 1 Maven integration test passed without mocks or an enclosing test transaction; explicit provisioning, real catalogue identities, complete MVC response and fresh persisted reads. The same test passed while packaging the final browser application. |
| Maven-owned `primary/primary-admin-chromium` browser selection | Passed with pinned Node 24.18.0, Playwright 1.61.1 and Chromium 149.0.7827.55: 822 UI contracts, 35 Admin checks, one actual browser scenario/application start. The identical documented command had previously skipped browser execution; its postcondition failed before the POM correction. |
| Explicit local Chrome 138 diagnostic of the actual full Admin workflow | Passed with the freshly Maven-packaged application, 35 checks and no audit error; includes reject/apply, Preferences 50→150, pending autosave/restore, reload, real Git Undo and cleanup. The final run used normal production transport; the diagnostic clone-consuming control was removed. This is diagnostic evidence, not pinned-browser CI acceptance. |

The browser scenario now materializes two authored hypotheses using identifiers
discovered from the real catalogue and their actual persisted IDs. In a disposable
workspace it drives reject/apply, gated autosave, Preferences success/failure,
navigation, reload and committed Git Undo. It checks distinct raw/effective score
values, restored visible decisions, repeat-command suppression, optimistic Git
headers, commit identity and authoritative draft state. It restores the original
workspace and working state; residual fixture rows expire with the isolated
launcher's database. No remote model inference is required.

The first follow-up CI run exposed a fixture initialization error: it selected an
unprovisioned workspace before waiting for a ready analysis session. Explicitly
pinned non-lifecycle APIs correctly returned HTTP 409. The fixture now provisions
its exact workspace through the authenticated request context before selecting
and reloading it, and requires HTTP 200 plus `READY`. Native lifecycle URLs use
the application's canonical resolver, including deployments under `/taxonomy`.
Navigation waits for the document, visible UI, translations and the exact ready
workspace instead of global network silence; readiness timeouts are unchanged.
The stateful regression executes the actual workflow and reproduced four failures
before the lifecycle correction. It also checks that incomplete provisioning
stops before loading hypotheses and still restores the original workspace.

The second CI attempt completed the other five browser shards while the extended
Admin scenario continued running. Review identified an unbounded request-gate
observation path, without establishing it as the cause of that runtime delay.
Both observations now use the same 30-second readiness budget and identify the
missing request method and pattern on failure. Five focused cases cover missing
GET/PUT requests, early observations, timer cleanup, other methods and release on
disposal. Static phase messages make any subsequent interrupted run diagnosable
without printing request payloads or workspace identifiers.

An explicit local Chrome 138 diagnostic then reproduced the actual hang at
Playwright's `response.json()` for the successful apply-session command. The
application inspects its status without consuming its body; unrelated browser
requests continued normally. A diagnostic control that consumed a clone of that
body immediately released the wait. The fixture now requires the matched POST
and HTTP 200, then verifies the exact persisted hypothesis decision through a
fresh workspace-pinned GET. Five regressions first failed, then passed, including
failed reads, missing/wrong identities and unapplied decisions. Git revision,
ETag and idempotency assertions remain unchanged. This does not change production
transport behavior or establish a backend deadlock.

The released browser flow also exposed the stale live bulk button. A dedicated
header slot now shares the initial renderer's eligibility predicate and is
refreshed after review, session application and Undo. Row badges, recovery/error
messages and Undo listeners are retained. Independent reviews approved both the
fixture correction and the live header correction.

Local Maven uses Java 21, the repository wrapper, a trusted proxy CA and bounded
JVM heaps. Disabling npm's inheritance of Maven proxy command-line arguments
corrected a local Playwright installer argument error. The initial pinned browser
download returned a 195-byte HTML page. Installation subsequently succeeded by
redirecting Playwright's supported Chromium download-host override to Google's
official archives for the exact locked version; Playwright installed the actual
browser and headless-shell artifacts. Its normal FFmpeg fallback also succeeded.

Once the download prerequisite was resolved, the documented browser-only Maven
command exposed the frontend plugin's generic `skipTests` shortcut at the
integration-test phase. A postcondition requiring actual Admin acceptance failed
despite Maven's success exit. The two execution-local overrides above now allow
the same command to run the real transport/UI contracts and selected browser
scenario. The final run completed in 2:15, including 79 seconds for application
startup and browser execution. Java tests were intentionally skipped for that
browser-only command; this is not a substitute for the complete reactor gate.

The associated follow-up PR records the final focused commands, real browser
result and exact-commit CI/database/security gates. Focused tests do not replace
the canonical `./mvnw -B verify -Pci -DrunOnnxTests=true` lifecycle, its six UI
shards and evidence aggregate, or the separate PostgreSQL/SQL Server/Oracle matrix.
No verification selector, security baseline, coverage threshold or timeout is
relaxed for this change.

## Boundaries

- This fixes the listed diagnostic events; it does not certify all application,
  framework, provider or security-audit loggers and does not close issue #857.
- The original Preferences state-loss report (#1100) remains unconfirmed. The
  separately reproduced restoration defect does not establish that report's
  cause or justify closing it.
- Stored decisions and the server's review authority remain distinct: restoring
  a badge does not bypass Git commands, concurrency checks or authorization.
- The browser uses a deterministic fixture and the real draft/review endpoints
  in an isolated test application. It is not evidence of remote model quality
  or verification of an unspecified deployed site.
