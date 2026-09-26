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
- [ ] 5. Execute regression suites, actual local Spring workflow against a loopback-only provider, reload/cancel/ownership and export checks. Record limitations of unavailable full Maven/remote writes. Package patch and evidence.

## Review focus
Scope changes during a response; duplicate retry/cancel; incomplete sibling responses; retained failures after restart; progress/focus overlap. Each belongs to the backend or browser tests above.


## Verification record

Tasks 1–4 have code and executable local evidence. Task 5 remains a release-verification gate: the canonical Maven command cannot download Maven in this environment, and the complete npm contract command stops at the unavailable `@axe-core/playwright` dependency. Native Chromium navigation is blocked by the environment's enterprise URL policy. These gates were not disabled or reported green.

Focused execution: 311 JavaScript tests; five executable JVM contract groups (also wrapped for JUnit); eleven real Chromium component scenarios including CDP 400% pinch zoom. Separate real Spring/HSQLDB acceptance covers persistent failure, exact retry, leave-open, later reassessment, cancellation during provider I/O, CSRF, ownership, export/import, and actual process restart with retained answers. Production classes were compiled with Java 21 and `-parameters`, matching Spring constructor injection requirements. No remote commit or merge is implied by these local results.

The dependency baseline adds eight reviewed class pairs on already-declared module dependencies. Its source/reflection evidence is retained separately; ArchUnit itself was not executed locally. Full current-branch CI remains required before merge.
