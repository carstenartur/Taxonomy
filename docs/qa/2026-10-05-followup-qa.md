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
| Restored hypothesis review | Rendering a saved draft ignored accepted/rejected status and the session-applied flag, reoffered completed actions and lost Git Undo. | Render stored decisions, reinstall the existing Undo command and guard repeated review/apply requests. Unsupported statuses remain read-only. Bulk review is offered only when eligible persisted hypotheses remain. |

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
actions. The resulting Preferences suite passes twelve cases. Its combined draft,
startup and relationship selection passes fifty-nine cases.

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
| Actual renderer/adapter/draft JavaScript selection | 59 tests passed, including 12 Preferences/restoration cases. |
| Browser fixture/evidence support tests | 7 tests passed. |
| Maven-owned `primary/primary-admin-chromium` browser selection | Not executed locally: the pinned Chromium download returned a 195-byte HTML “Site Unavailable” page instead of an archive. The required CI browser lane supplies runtime acceptance. |

The browser scenario now materializes two authored hypotheses using identifiers
discovered from the real catalogue and their actual persisted IDs. In a disposable
workspace it drives reject/apply, gated autosave, Preferences success/failure,
navigation, reload and committed Git Undo. It checks distinct raw/effective score
values, restored visible decisions, repeat-command suppression, optimistic Git
headers, commit identity and authoritative draft state. It restores the original
workspace and working state; residual fixture rows expire with the isolated
launcher's database. No remote model inference is required.

Local Maven uses Java 21, the repository wrapper, a trusted proxy CA and bounded
JVM heaps. Disabling npm's inheritance of Maven proxy command-line arguments
corrected a local Playwright installer argument error; the remaining download
failure above is recorded as a missing prerequisite, not a passing browser test.

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
