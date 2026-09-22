# Inspectable LLM analysis failures

## User-visible contract

A reply such as `please provide details` is not a score object. It remains a failed
call and an incomplete analysis; the repair does not manufacture scores, retry a
provider request or claim to explain why a live model returned prose.

The live log separates the error, response and prompt. The response comes first,
independently of prompt length. Valid JSON has an indented display plus the exact
retained raw text, including when the reply was already formatted. Plain text and
malformed JSON retain their line breaks. All external strings are inserted as
text, never interpreted as HTML.

The existing user/workspace/branch/repository authorization and bounded lifetime
are unchanged. This is a live diagnostic buffer, not a durable transcript:
at most 32 call entries per run, 8192 characters per prompt and response, and ten
minutes of terminal retention. The new error field is bounded to 1024 characters.
Original prompt/response lengths make truncation explicit. Missing responses are
labelled as absent from the buffer, not presented as successful empty replies.

JSON extraction scans nested objects while respecting strings and escapes.
Braces and Markdown fence text inside reasons remain intact. An incomplete outer
object must fail rather than being replaced by a valid-looking inner object.
Existing category normalization and independent-product scoring stay unchanged.

## Persisted partial-result diagnostics

A PARTIAL job item retains the analysis error, or the first meaningful warning
when no explicit error exists. The aggregate job summary includes that reason.
A genuinely unknown cause remains absent; SUCCESS does not acquire a stale
failure message. No missing explanation is invented.

Snapshot, item and job diagnostic previews respect the existing 2000-character
metadata columns. Longer previews carry an explicit truncation marker without
splitting a Unicode surrogate pair. The complete original analysis JSON, including
its error, warning list and partial scores, remains unchanged. This prevents a
long error from rejecting a snapshot insert without adding a transcript store.

## Regression tests

`LlmDiagnosticTest` registers six executable production-boundary checks in the
normal analysis JUnit suite. They cover a failed provider reply through the actual
service and progress registry, scope denial, bounded evidence and lengths,
transport failure, actionable non-JSON failure, quoted JSON and truncated objects.
Only the remote provider reply is a deterministic fixture; no cloud credentials
or network requests are needed.

`node --test .github/scripts/analysis-llm-log.test.mjs` registers nine monitor and
renderer cases: seven standalone cases and two preformatted-JSON loop cases.
The existing `test:analysis-session-startup` script includes the file, preserving
all prior checks and their Maven/CI ownership. Coverage includes already-open
pending calls, post-completion inspection, late cross-generation responses, and
an explicit raw-response section for both preformatted objects and empty arrays.

`PortfolioPartialDiagnosticsTest` registers eight transactional persistence cases:
explicit error, warning fallback, long error, long warning, Unicode boundary,
exact column boundary, SUCCESS and genuinely unknown cause. It invokes
`PortfolioPartialDiagnosticsChecks` with real Spring services and database writes
and reads. Analysis results are stored-result fixtures, not live LLM outputs.
Checks include metadata bounds, full payload preservation, source version identity
and foreign-workspace denial.

## Evidence history and verification limits

For the initial feature, all six Java diagnostic checks failed before the repair
and passed with compiled production overlays. Four initial renderer cases failed
before the UI repair. The later raw-view review repair added two cases that first
failed; the resulting nine-case renderer suite then passed. These are historical
runs, not a claim that every later commit has passed complete CI. A separate
Chromium fixture verified pre-wrap styling and literal markup, not authenticated
full-application acceptance.

For persistence commit `63d8380`, six of the eight new checks failed against
unchanged production, including real SQL rejection of long snapshot errors.
All eight passed after compiling the two changed production classes. Fresh Java
21 compilation and a repeat run exited zero with
`PORTFOLIO_PARTIAL_DIAGNOSTICS_OK`. The JUnit wrapper itself was not run locally.
The checks used verified earlier runtime artifact 10689969513 with compiled
overlays and a partial source checkout, not a complete rebuilt new-head app.
Maven was unavailable locally; no full local Maven/HTTP/browser pass is claimed.

The older architecture API failure had an explicit MEMORY_PRESSURE cause and
received a separate deterministic success/pressure test repair. At predecessor
`06f2442`, PostgreSQL and Oracle passed, but SQL Server lane 106734443130 failed in
its ordinary HSQL application suite: civilian verification pass two was PARTIAL
with no item/aggregate explanation. Its cause is not established from that
summary. The new diagnostic persistence does not itself prove this acceptance
failure fixed. The unchanged acceptance test still requires SUCCESS.

Fresh complete current-head CI and independent review are required before merge.
A skipped CodeQL job is not an executed security analysis. The user's original
provider reply and deployed runtime have not been inspected.
