# Single architecture diagram implementation plan

> Execute the authorized cleanup in the isolated `refactor/architecture-single-diagram-engine`
> worktree. Design: `docs/superpowers/specs/2026-11-04-single-architecture-diagram-design.md`.

**Goal:** one complete SVG/controller implementation for working, snapshot and analysis,
with source contracts and existing features preserved.

**Baseline:** `521b8cfcdc13523fa8d25c3e86278f6bb6c8cdf9`. Java 25; Maven-owned
JavaScript and native browser suites; no new runtime dependency.

## Tasks

1. **Remove dead impact implementation.** Delete the old impact force renderer/export,
   onboarding wrapper and dead CSS. Migrate the sole scoring caller directly to
   `TaxonomyImpactMap.render`. Preserve Graph Explorer and semantic palette consumers.
2. **Specify the single engine with behavioral regressions.** Exercise real `create`
   instances rather than disconnected state helpers. Cover shared selection/search,
   guard refusal/reentrancy, hidden fitting/cancellation, focus restoration, lens screen
   geometry, limits and complete disposal. Record the failing baseline.
3. **Implement the shared controller.** Merge drawing, state, fitting, zoom, keyboard,
   pointer, lens, limits and teardown into one owner. Remove `connect` and unused
   parallel state implementations. Retain immutable geometry and public adapter APIs.
4. **Migrate source adapters.** Snapshot and analysis supply pure presentation/layout
   data and consume state changes for HTML UI. Remove local renderers, SVG utilities,
   selection/search controllers, D3 and fit/resize implementations. Preserve source
   retention, request cancellation, details, actions, accessibility and exports.
5. **Update superseded contracts.** Replace legacy-preservation/private-function tests
   with equivalent behavior through the single engine. Preserve all substantive
   layout, relevance, source, print, export and browser acceptance scenarios.
6. **Verify and review.** Run focused Maven contracts, native Chromium component
   tests and production workspace integration. Independently review actual code
   ownership and feature coverage. Fix findings, run required full verification,
   then publish the reviewable branch/PR using the established repository workflow.

## Exact focused gates

Use `JAVA_HOME=/tmp/jdt-pr5614-tools/jdk-25.0.5+8` locally.

```bash
./mvnw -B -pl taxonomy-tooling -am test -Dtest=JavascriptContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.javascript.sources=.github/scripts/architecture-diagram.test.mjs,.github/scripts/taxonomy-impact-map.test.mjs,.github/scripts/architecture-workbench-fit.test.mjs,.github/scripts/architecture-workbench-print.test.mjs,.github/scripts/architecture-workbench-basepath.test.mjs,.github/scripts/architecture-workspace.test.mjs,.github/scripts/architecture-source-lens.test.mjs,taxonomy-app/src/test/js/current-diagram-export.cjs

./mvnw -B -pl taxonomy-tooling -am test -Dtest=JavascriptContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dtaxonomy.javascript.mode=browser \
  -Dtaxonomy.javascript.sources=.github/scripts/architecture-diagram.browser.test.mjs,.github/scripts/architecture-source-lens.browser.test.mjs,.github/scripts/architecture-editor-lifecycle.browser.test.mjs

./mvnw -B -pl taxonomy-app -am verify -Ptest-local \
  -Dtest=TaxonomyDesignTokenContractTest,ArchitectureEditorBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ArchitectureWorkspaceBrowserIT,ArchitectureWorkbenchUiIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Final source audit: exactly one controller entry point and ownership of zoom,
selection/search styling, fit scheduling and lens handling; no legacy impact route;
no domain/persistence functionality silently deleted. Record results and limitations
in a new QA note rather than reusing #1367's completion evidence.
