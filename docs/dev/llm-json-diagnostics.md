# LLM JSON failures and diagnostic visibility

The score parser already validates the extracted JSON on the server. A second
browser-side parser is not the authority: diagnostic responses may be truncated
by the existing retention limits, and valid model answers may be wrapped in a
Markdown code block. A failed `JSON.parse` while formatting a preview must not
turn an otherwise successful call into a failed analysis.

## This change

- A Jackson syntax error becomes a concise `Invalid JSON in LLM response` message
  with the available line and column **of the extracted JSON**, not of the entire
  Markdown-wrapped raw reply. The parser exception remains the cause for server
  diagnostics. Its source-reference boilerplate and model text are not copied
  into the main user-facing error. No missing braces are appended, no partial
  inner object is salvaged, and no retry or extra model call is introduced.
- The existing detailed-call path retains the original model answer before
  parsing. Its scoped diagnostic endpoint keeps that answer and the error after
  a partial result. Oversized diagnostic fields are still explicitly truncated;
  this is not unlimited or durable log storage.
- Every collapsed live-log call shows the recorded server start in an explicitly
  labelled UTC date/time (including milliseconds), plus the call ID, provider,
  node, status and duration. The log also identifies the operation. Polling does
  not create replacement timestamps. Missing or invalid times are labelled as
  unknown; they are not inferred from browser reception or duration.
- The cancellation button remains a native disabled button after completion.
  A neutral outline and a status label distinguish completion, pending
  cancellation and unavailable observation from an active red cancel action.
  Disabled text retains full opacity. Log error backgrounds and text use the
  existing Bootstrap theme variables instead of a light-only error surface.
- Prompt substitution now matches only placeholders in the original template.
  Literal placeholders, dollar signs, backslashes, braces, code fences and
  quotes inside inserted requirements or taxonomy descriptions are preserved.
  This covers scoring, independent products, justification and regulation
  mapping. Repeated template tokens and the existing unknown-token behavior
  remain supported. This fixes accidental recursive substitution; it is not a
  claim of complete protection against model-level prompt injection.

## Scope boundaries

This is diagnostic and data-preservation hardening, not a new score model.
Syntax validity alone does not prove that every expected child is covered or
that the classification is correct. Independent product scoring retains its
existing key/value checks. Legacy category normalization and missing-value/zero
compatibility remain unchanged; consumers must continue to honor the error and
PARTIAL status rather than treating a failed call's placeholder scores as a
verified negative result. The broader shared child-assessment migration is
separate work in issue #1111 and PR #1113.

Provider-side structured output, finish-reason/token-limit metadata, strict
category coverage and bounded retry policy are not delivered by this change.
An incomplete JSON object is not automatically described as a provider token
limit. The exact original BR/CP provider reply and its termination metadata are
still needed to attribute the production failure to a particular cause.

When integrating with #1113, retain its `ObjectReader` strict duplicate-key
check inside the diagnostic try/catch; do not replace the shared assessment
contract with another validator.

## Regression coverage

`LlmJsonValidationTest` covers the missing outer brace before a Markdown end,
other malformed input, concise location reporting, cause preservation, shared
product syntax handling and correctly quoted unusual text. `PromptLiteralDataTest`
checks single-pass substitution in each affected prompt family. The ordinary
`LlmDiagnosticTest` entry point also exercises malformed JSON through the real
service, scoped registry and diagnostic DTO with only the remote provider replaced.

`.github/scripts/analysis-llm-log.test.mjs`, already owned by
`test:analysis-session-startup`, executes the real monitor and DOM renderer. It
checks timestamps, missing-time handling, identities, terminal/cancelling button
states and absence of false validation errors on truncated previews, alongside
the existing raw-response, formatting and isolation regressions.
