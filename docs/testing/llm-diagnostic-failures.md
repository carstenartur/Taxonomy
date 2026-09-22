# Inspectable LLM analysis failures

## User-visible contract

A reply such as `please provide details` is not a score object. It remains a failed
call and an incomplete analysis; the repair does not manufacture scores, retry a
provider request or claim to explain why a live model returned prose.

The live log separates the error, response and prompt. The response comes first,
independently of prompt length. Valid JSON has an indented display plus the exact
retained raw text; plain text and malformed JSON retain their line breaks. All
external strings are inserted as text, never interpreted as HTML.

The existing user/workspace/branch/repository authorization and bounded lifetime
are unchanged. This is a live diagnostic buffer, not a durable transcript:
at most 32 call entries per run, 8192 characters per prompt and response, and ten
minutes of terminal retention. The new error field is bounded to 1024 characters.
Original prompt/response lengths make truncation explicit. Missing responses are
labelled as absent from the buffer, not presented as successful empty replies.

JSON extraction now scans nested objects while respecting strings and escapes.
Braces and Markdown fence text inside reasons remain intact. An incomplete outer
object must fail rather than being replaced by a valid-looking inner object.
Existing category normalization and independent-product scoring stay unchanged.

## Regression tests

`LlmDiagnosticTest` registers six executable production-boundary checks in the
normal analysis JUnit suite. They cover a failed provider reply through the actual
service and progress registry, scope denial, bounded evidence and lengths,
transport failure, actionable non-JSON failure, quoted JSON and truncated objects.
Only the remote provider reply is a deterministic fixture; no cloud credentials
or network requests are needed.

`node --test .github/scripts/analysis-llm-log.test.mjs` runs seven production-monitor
and renderer cases. The existing `test:analysis-session-startup` script includes
it, preserving all prior checks and their normal Maven/CI ownership. Tests also
cover an already-open pending call, post-completion lazy inspection and rejection
of late responses from another analysis generation.

## Local evidence and limits

All six Java checks failed on the matching main runtime before the repair and
passed after compiling the two changed production classes as overlays. Four of
the seven Node cases failed before the UI repair; all seven pass afterwards.
A separate Chromium fixture using the production JavaScript/CSS verified actual
pre-wrap styling and literal markup. It is not an authenticated full-app test.

The local baseline is a partial source reconstruction from main `9ce74f9` CI
artifacts, with the three production baseline blob hashes verified against GitHub.
Runtime archive 10676855815 was SHA-256 verified. Java 21 was used; local Node is
22, not the required 24. A full Maven attempt cannot run because Maven is absent;
the full UI command cannot run because the partial checkout lacks the earlier
version-comparison test. These are environment limits, not successful full suites.
Fresh complete current-head CI and independent review are required before merge.
The user's original provider reply and deployed runtime have not been inspected.
