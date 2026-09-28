# Planning Profiles Implementation Plan

> Execute inline with test-driven development. No remote issue creation.

**Goal:** Editable, versioned standard references and precise go-live goals, exchanged through ReqIF.
**Architecture:** Extend canonical DSL requirement metadata and existing source objects; profile registry is product independent; the existing editor and interop orchestrate acceptance.
**Tech Stack:** Java 21, existing Jackson/ReqIF XML code, existing Spring editor, plain JavaScript.
**Spec:** ../specs/2026-09-26-planning-profiles.md

## Global Constraints
No new dependency/store/service; preserve original requirement text and official taxonomy. Unknown profile/version is retained read-only. No semantic upgrading. No new issues.

## Review Focus
Unknown version; missing metadata on reimport; ambiguous source identity; local changes after export; forged writable profile claims.

## Tasks
- [x] 1. Pure profile registry, exact date/year validation, source-link projection, stable bounded DSL storage and third-profile contract. Tests run red then green.
- [x] 2. Existing command/undo/fingerprint and editor projection/UI; portfolio contribution retains annotations. Test compile, state/diff/reload/undo and UI readonly behavior.
- [x] 3. Bounded transport DTO/JSON and reserved ReqIF STRING attribute, explicit support report. Schema and round-trip tests run red then green.
- [x] 4. Existing interop snapshot/export and reviewed import use canonical values; missing metadata not deletions. Test identity and current-value overlay.
- [x] 5. Run focused regression checks, self-review changes, package patch and reproducible tests with exact verification limits.

## Execution ledger
Base: main 6f7f1e9c02ee6bcbd07186dd3c4ffb688ba4edeb, reconstructed from commit-bound application and coverage-source artifacts. Local git baseline is not upstream ancestry.
No git network/Maven/remote writes currently available. Compile actual changed classes against the exact-main packaged dependencies; executable contract runners plus JUnit wrappers preserve Maven ownership.


## Completion evidence and rulings
- Ruling: use the existing workspace DSL for planning authority, not a second
  portfolio metadata table. ReqIF planning imports compose the existing portfolio
  contribution and typed editor commands; original text versions remain unchanged.
- Ruling: only actual planning entries activate the new portfolio planning path;
  ordinary ReqIF imports retain the existing path.
- Ruling: missing external planning entries are retained locally, but never copied
  into the observed external baseline. Explicit local deletion is separate.
- Ruling: an unknown profile version is preserved; an unknown transport envelope
  schema is rejected, because its safe structure cannot be assumed.
- Task 1–4 verification: 48 + 21 + 14 Java contract assertions and six Node tests
  passed after observed failing tests. All 20 touched/new production classes compile.
- Task 5: reviewed source/identity/malformed data/shared-source/undo paths; exercised
  real HTTP import, editor preview/save/retry/undo/redo, restart and ReqIF omission
  reimport against HSQLDB; browser panel checked separately in DE/EN desktop/mobile.
- Full Maven and connected browser/vendor-product verification remain unperformed,
  not waived. Exact evidence and environment boundaries are in
  `docs/testing/planning-profiles-verification.md`.
- No new issue, remote branch or PR. Delivery is a checked patch plus source bundle.

## Continuation
The follow-up session can use GitHub Git-data writes and has created the feature
branch at the exact baseline. No issue is created. The previous no-write/patch-only
notes are historical. The same 20 production classes and contract runners were
rechecked; a failing schema-number-overflow regression was corrected with an exact
integer-range check. Current focused results: 48 + 22 + 14 assertions and six Node
tests pass. Publish one complete commit only after transferred blobs match the
locally checked files. Full CI and ordinary review remain required before merge.
