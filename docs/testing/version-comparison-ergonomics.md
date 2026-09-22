# Version comparison: readable results and stable dialog lifecycle

Source baseline: `0f1830ff3bfab5ead5466a6591fc5bbc57855fe7`.
This is the first independently publishable slice of the approved UI ergonomics work.
Preferences, diagram navigation/state and DSL folding are separate, not certified by this slice.

## Behavior

History compares the selected commit to the captured branch HEAD. The shared comparison
component validates the document API's counts, detail lists and semantic changes instead
of looking for nonexistent `added`, `removed` and `changed` arrays. A relation-only change
has its own section, before/after values and both commit identities. Empty element
columns become a compact explicit hint instead of hiding the actual change below the fold.

Restore previews run in the opposite direction: captured HEAD to selected target. The
confirmation is disabled while the visible preview is pending and stays disabled on error.
Closing a pending preview cannot reopen it, enable confirmation or perform a write.
A failure is never interpreted as permission to restore without a preview.

The context/variant dialog uses the canonical API client, validates branch choices,
preserves explicit commit IDs and distinguishes loading, selection and failure. Changing
one side or closing the dialog invalidates delayed results. Loading branch choices locks
the selectors. Variant comparison uses the same dialog, not a second population path.

Comparison status colors use Bootstrap text-emphasis colors in both themes. Column minimum
widths cannot exceed the dialog width. Zero and empty old property values remain visible.

## Reproducible checks

`npm run test:version-comparison` in `.github` runs the production JavaScript contract.
It is included in both `verify:ui` and `verify:ui-contracts`, retaining the existing Maven
ownership. The tests cover response consistency, relation-only changes, multiple property
changes for one relation, direction, escaped values, zero values, stale results and loading.

The existing primary ADMIN browser workflow additionally creates two real Git versions
on a QA-only branch, changes one existing relation, opens its history comparison, verifies
both identities and the actual semantic description, and records
`version-comparison-real-relation-change.png`. It verifies and cancels the reverse restore
preview, then injects an HTTP failure and proves restore confirmation remains disabled.
It does not apply a restore or call a live LLM. Existing browser/security gates are unchanged.

## Local evidence and limits (21 September 2026)

- Tests first failed for the missing API adapter; 23 targeted Node tests now pass.
- A browser probe reproduced a delayed result replacing the contents of a closed context
  dialog; after lifecycle invalidation, it no longer does so.
- A pending restore originally had no visible cancellable progress; it now does.
- Visual inspection found three empty element cards preceding a single relation change
  on mobile. A failing regression preceded their replacement with a compact hint.
- 14 Chromium component scenarios pass using production scripts, Bootstrap 5.3.8, the
  actual template fragments and controlled responses. These include real-data rendering,
  identical commits, malformed/failed responses, cancellation, restore direction, changed
  selectors, branch failure, narrow layouts and light/dark contrast. Component screenshots
  were inspected; they are not full-application documentation screenshots.
- The exact main application JAR ran locally in an isolated in-memory HSQLDB. Two real
  versions were stored and compared in both directions. Each had exactly one changed
  existing relation. The application loaded the real taxonomy; no catalogue IDs were invented.
- Full browser navigation to the local server failed with `ERR_BLOCKED_BY_ADMINISTRATOR`.
  Browser component checks and separately exercised real APIs are not an end-to-end claim.
- The canonical `./mvnw verify -DexcludedGroups="real-llm"` was attempted and failed before
  compilation while downloading Maven 3.9.16. Local Node is 22, not the required Node 24.
  The full `npm run verify:ui-contracts` chain stopped at an existing evidence test
  because `@axe-core/playwright` was unavailable. A normal `npm ci` retry failed with
  `EAI_AGAIN` at the npm registry; the dependency versions or checks were not relaxed.
  Fresh exact-head canonical CI and full application browser evidence remain required.

The pre-existing documentation images 48 and 68 include synthetic screenshot-generator
inputs. They must not be treated as evidence that the history comparison works. The new
primary-workflow screenshot is captured only after real versions and UI assertions succeed.
No transfer payload or write-enabled transfer workflow is part of this change.
