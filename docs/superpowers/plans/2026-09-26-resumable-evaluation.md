# Resumable evaluation implementation plan

Goal: complete the approved recovery/unknown-state workflow and keep progress in view.
Architecture: existing evaluator + durable question journal, exact-scope request admission, browser recovery controls; no second search engine.
Tech stack: Java 21 / Spring Boot / JPA / existing Jackson and vanilla browser modules.
Spec: ../specs/2026-09-26-resumable-evaluation.md

## Tasks
- [x] 1. Regression-test unknown evidence and durable question replay/decision transitions. Implement domain coverage and journal state engine.
- [x] 2. Persist run/call state using existing database conventions; wire exact-scoped analysis, GET recovery and cancel with CSRF/owner guards. Add migration and backend recovery tests.
- [x] 3. Wire retry/leave-open/cancel into actual scoring/Copilot; persist coverage and continuation identity, retain known results, gate dependent downstream claims.
- [x] 4. Add visual-viewport progress and accessible failure dialog. Test native browser geometry at desktop, phone, zoom-equivalent and reduced keyboard-height viewports without automatic scrolling.
- [ ] 5. Execute regression suites, actual local Spring workflow against a loopback-only provider, reload/cancel/ownership and export checks. Record exact local and CI results and remaining release gates. Package patch and evidence.

## Review focus
Scope changes during a response; duplicate retry/cancel; incomplete sibling responses; retained failures after restart; progress/focus overlap. Each belongs to the backend or browser tests above.


## Initial implementation verification record

Tasks 1–4 have code and executable local evidence. Task 5 remains a release-verification gate: the canonical Maven command cannot download Maven in this environment, and the complete npm contract command stops at the unavailable `@axe-core/playwright` dependency. Native Chromium navigation is blocked by the environment's enterprise URL policy. These gates were not disabled or reported green.

Focused execution: 311 JavaScript tests; five executable JVM contract groups (also wrapped for JUnit); eleven real Chromium component scenarios including CDP 400% pinch zoom. Separate real Spring/HSQLDB acceptance covers persistent failure, exact retry, leave-open, later reassessment, cancellation during provider I/O, CSRF, ownership, export/import, and actual process restart with retained answers. Production classes were compiled with Java 21 and `-parameters`, matching Spring constructor injection requirements. No remote commit or merge is implied by these local results.

The dependency baseline adds seven class pairs on already-declared module dependencies. The actual CI ArchUnit report counted four (not five) pairs from analysis recovery to workspace services; the reviewed baseline was reduced accordingly. ArchUnit itself was not executed locally. Full current-branch CI remains required before merge.


## PR #1133 integration follow-up (2026-09-26)

Inspected the archived source for merge commit `60ac1984a2e46d824bfded18805dcb9464f25de0`
and the original JUnit/log artifacts for head `02c9022a72d70f5e805d4e95ead4866d1f237639`.
The reformulation report and PostgreSQL run both fail the same strict dependency
ratchet. The corrected baseline matches the complete ArchUnit-produced JSON;
no other dependency edge, allowlist or test gate was changed.

The separate core-contract failure was the explicit ordered-module expectation in
`test-taxonomy-base-path.mjs`. It omitted both new recovery scripts. The production
loader already loads them in the correct order with the deployment prefix.

Fresh local execution:
- Base-path regression reproduced the missing-module assertion before correction
  and passed afterwards.
- 91 targeted analysis/session/Copilot/recovery JavaScript tests passed, with no
  failures or skips (including the 25 recovery/startup tests).
- All 11 native Chromium component scenarios passed with no page errors, including
  320x480, short viewports, focused-control avoidance, unchanged scroll position,
  stable badges, retry/leave-open, and CDP 400% pinch zoom.
- `git diff --check` passed.

Limits: the component run uses installed Chromium 144.0.7559.96 and the installed
Playwright driver with controlled transport, not the pinned full application E2E
stack or a physical iPad. The full Maven verification was attempted through the
wrapper and failed downloading Maven. `npm --prefix .github run verify:ui-contracts`
was attempted and stopped at the unavailable `@axe-core/playwright` dependency.
These full gates are not green, and the new head still requires CI before merge.

## PR #1133 continuation/evidence follow-up (2026-09-27)

Continued on head `fdcb00a76096be2640423437ca8f6fe7d3545276`, rather than adding
another evaluation engine. Its core reactor, UI contracts, all six UI shards,
database compatibility and separate acceptance workflows passed. The overall
CI/CD run `36260572444` was red solely because the final aggregation job stops
intentionally at **Keep draft heads non-mergeable**. That guard is unchanged;
these results do not certify the next commit.

New regressions reproduced three remaining boundary failures before the fixes:

- A paused/stopped/cancelled traversal could leave not-yet-visited roots with
  `NOT_EVALUATED` but no open-evaluation count, losing uncertainty on exchange.
  `INTERRUPTED:<state>` now preserves those scopes, independently of failed KI
  checkpoints. Assessed ancestors remain assessed; excluded descendants remain
  excluded. Graphical exports name interrupted scopes without invented nodes.
  The per-node accessible badge also retains the explicit unassessed label for
  each interruption state, rather than only showing the global warning.
- Imported partial evidence has no owned recovery dialog. The public Copilot
  path now refuses unsupported global gap/pattern/recommendation claims and
  shows an inline explanation; the individual actions no longer silently no-op.
- A successful follow-up whose draft write failed could admit its dependent
  stages. Persistence must now succeed first. Retry saves the cached successful
  result instead of repeating the KI query; a workspace change during the write
  rejects the stale completion.

Fresh local verification of this follow-up:

- Seven new JavaScript regressions failed before their fixes; all 18 recovery
  tests then passed. The focused analysis/session/Copilot suites pass **273**
  tests, zero failures or skips.
- Three modified Java production classes compile with Java 21 and `-parameters`
  against the exact CI-built PR runtime. All **five JVM probe groups** pass.
  The stop/JSON-exchange regression and interrupted diagram scope regression
  were each observed failing against the prior production behavior.
- All **12 Chromium component scenarios** pass, including real CDP 400% pinch
  zoom, narrow/short viewports, visible controls and unchanged scroll position.
- **Eight real Spring HTTP checks** pass with a loopback-only KI fixture and
  file-backed HSQLDB: pause with retained parent evidence, read without calls,
  repeat permanent invalid JSON with the identical prompt, skip and continue
  independent branches, targeted reassessment, then actual Java process restart,
  read of the restored paused run without calls, and retry with cached answers.
  Offered catalogue identities are discovered from the application's real tree.
  No external KI service was contacted. The failed prompt exceeded 10,000
  characters and its full fingerprint was unchanged on retry.
- `git diff --check` passes. The canonical Maven and full npm verification
  commands were both attempted, without weakened flags. Maven cannot download
  its distribution here; npm stops at missing `@axe-core/playwright`.

Limits: local Node 22.16.0 differs from CI Node 24; installed Chromium/Playwright
components are not the pinned native application E2E stack. HTTP execution uses
patched classes/resources ahead of the original CI runtime, not a new full Maven
package or external database matrix. There is no fresh independent reviewer
agent or physical iPad run. Full new-head CI and review remain release gates;
this follow-up neither merges the PR nor marks the project completely defect-free.
