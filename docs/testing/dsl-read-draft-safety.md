# Draft-safe DSL formatting and loading

Base: `9ce74f9a7c3cbbd43343225dab0acabae733e234` (merged version comparison).
This is an independent eight-file repair. It does not add DSL groups, folding,
Preferences changes or diagram reading-state persistence.

## Behavior

Formatting, loading the current architecture and opening a stored DSL version
use one cancellable, latest-request-wins read path through TaxonomyApiClient.
A response applies only to the same EditorView, unchanged immutable document,
workspace, analysis generation and selected branch/context. New edits, another
read, another editor or page departure invalidate it. The original human draft
is retained even when an obsolete request ignores abort.

HTTP errors, HTML login/error pages, wrong response media types and invalid
text shapes are rejected before replacing the document. Formatting a nonempty
source to an empty success cannot erase it. Explicitly loading an empty stored
version remains valid. A valid result uses the existing EditorView dispatch;
optional reading-tools integration preserves presentation when installed but
is not required by this repair. Commit/materialization/merge writes are unchanged.

Two feedback messages are registered through the existing shared MessageSource
and browser translation controller in English and German. No dependency version,
security policy, request transport or existing verification gate is relaxed.

## Tests and actual evidence (22 September 2026)

- `npm run test:dsl-text-read` in `.github`: 19 passing tests, no skips.
  Seventeen exercise the production editor script's real read/format functions,
  including a newer draft, reversed response order, workspace/generation/editor
  replacement, page departure, HTTP/media-type rejection, empty text, both load
  paths and fallback without optional reading tools. Two assert delivery wiring.
- Regression tests first reproduced stale response replacement before the repair.
  Controlled response objects are test inputs, not proof of full browser acceptance.
- Direct Java 21 compilation/execution of the actual I18nConfig and
  I18nApiController confirms both new keys resolve in both locales and German
  UTF-8 text is intact. This is not a full Spring HTTP or JUnit application run.
- Syntax checks, exact file hashes and whitespace checks are required before upload.

Both Maven-owned `verify:ui` and `verify:ui-contracts` retain their existing
commands and also execute these regressions. The ordinary complete CI and review
remain required before merge. This slice introduces no new browser test fixture
or claim that the complete local application workflow has passed.

## Boundaries

The canonical local `./mvnw verify -DexcludedGroups="real-llm"` cannot download
the pinned Maven distribution and stops before compilation. Local Node is 22,
not the repository-pinned Node 24. The broader local UI contract chain also
encounters the unavailable axe dependency; network installation fails DNS.
These limitations are not a green build and are not reasons to weaken CI.

This protects responses that are still pending; it does not add a general
unsaved-document confirmation dialog, persistent drafts or optimistic write
transactions. Existing legacy status timers and unrelated editor actions are
outside this repair. Self-review by the author is not independent approval.
