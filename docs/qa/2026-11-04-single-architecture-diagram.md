# Architecture diagram source consolidation

## Scope and baseline

This cleanup starts at `521b8cfcdc13523fa8d25c3e86278f6bb6c8cdf9` (#1367).
The earlier unified workspace still contained parallel diagram controllers and a
complete obsolete impact force renderer. This change removes those implementations;
it does not use the earlier navigation work's green checks as evidence for this code.

## Ownership after cleanup

| Responsibility | Single implementation | Source adapter responsibility |
| --- | --- | --- |
| SVG cards, edges, layers, markers and proposal overlay | `shared/architecture-diagram.js` | Pure geometry, card metadata and layer data |
| Selection, neighborhood/search highlighting, context and focus | Same `create` controller | HTML details, forms, translated labels and KPIs |
| Zoom, keyboard/pointer handling, measured fit, resize and viewport retention | Same controller | Toolbar commands and activation/suspension |
| Lens region and proposal interaction | Same controller | Existing shared focus panel and authorized source identity |
| Analysis data normalization/layout | `shared/taxonomy-impact-map.js` | Deterministic layout and the 80-node ranking policy |
| Persisted snapshot geometry | `architecture-workbench.js` | Immutable server scene, provenance, source lifecycle and exports |
| Working geometry | Existing thin `architecture-editor-renderer.js` adapter | Editor commands, dirty-form guard and server scene |

`TaxonomyArchitectureDiagram.connect` and the unused parallel `createState` are
removed. Analysis and snapshot no longer own D3 zoom, ResizeObserver-based fitting,
SVG drawing loops, selection-class mutation or search implementations.

`renderImpactForceGraph`, its legacy alias/override, the old force-graph onboarding
patch and its CSS are removed. Scoring calls `TaxonomyImpactMap.render` directly.
Graph Explorer's separate `renderForceGraph` feature and semantic color consumers
remain intact. Its renderer body was compared against the baseline unchanged.
The production change removes a net 983 lines; added regression tests and design
documentation are not counted as deleted application code.

## Preserved contracts and regression checks

- Working geometry, read-only snapshot geometry and analysis layer geometry remain
  source-owned data. The engine does not mutate the original scene.
- Analysis retains its 80-node priority policy; working retains viewport culling,
  200 visible nodes and 400 total edges including at most 32 proposals.
- Editor selection refusal precedes a state change. Reentrant programmatic selection
  cannot recurse into its dirty-form guard. Relation activation retains its form intent.
- Focus/unfocus, background/edge exit from focus, context removal of the focused node,
  hidden fit cancellation, manual camera retention, readable initial working frames and
  explicit full-scene fitting below the normal zoom floor for 5,000-node server layouts
  have controller-level behavioral coverage.
- Selection keeps the clicked card and its child elements attached, including reentrant
  editor selection and focus-panel feedback with the lens enabled or disabled. Lens
  assessments update card classes and accessibility labels; the common relationship
  drawing path updates real/proposed edges without replacing cards.
  Six native double-click regressions attach the actual focus panel for all three
  sources and the actual source lens/API adapter for both read-only sources, with
  only HTTP transport replaced in the component fixtures.
- Selecting another node inside a focused neighborhood preserves that neighborhood
  until an explicit focus command. Controller-owned sessions capture both the current
  camera and the overview camera preceding focus. Returning A → B → A and then leaving
  focus restores the original overview, including the snapshot adapter lifecycle.
- Explicit element links override retained snapshot selection after session restoration,
  reveal hidden context/neighborhood members, and survive synchronous workspace selection
  callbacks mutating the incoming source object. Restored small-scale cameras extend the
  zoom range, so the next zoom continues from the restored view.
- The initial ResizeObserver notification does not restart a pending fit. A frame-step
  regression checks the real first-frame/observer/second-frame order and readable cards
  at a 0.3 screen transform. Existing native two-frame readiness, physical readability
  and 1,500 ms performance assertions remain unchanged.
- Lens hit regions are checked with both camera zoom and a nonidentity screen transform.
  Pointer leave/cancel, source suspension, proposal filtering and teardown are covered.
- The editor Focus toolbar explicitly calls `focus()` without passing a browser event
  as an element ID. Its native adapter regression also checks dirty form retention.
- Marker checks require every real/proposed edge to reference a marker inside its own
  SVG and every marker ID to be unique across renderers; different edge styles can use
  distinct markers. Snapshot printing retains `.architecture-viewport`, full local bounds and marker
  references. Server exports retain their original source and provenance contracts.
- Tests formerly extracting private functions now exercise public pure adapters, real
  controller behavior or complete adapter mounts. Localization quantity assertions
  remain; an accidental cross-import no longer registers the same tests twice.
- The Java renderer boundary now includes the actual shared engine, not just the
  editor's thin adapter, in its no-transport/no-storage checks.

## Verification evidence

The initial Maven-owned controller regression run failed as expected: 18 cases,
12 failures, no errors/skips. The missing shared state/lifecycle and pending-fit
overwrites were observed before implementation. Further context/focus and resize
regressions were separately demonstrated failing before their fixes.

The focused Maven-owned diagram/workspace/lens/export contracts passed: 165 cases,
zero failures/errors/skips, before the additional readable-frame regression. The
complete local non-browser JavaScript run passed **1,872 cases**, zero
failures/errors/skips, including all **29 shared-controller cases**. The late
focus-panel regressions failed in both enabled states before the fix, then passed
through the same Maven-owned suite. The Java
design-token and renderer-boundary gate passed all seven checks, including the actual
shared engine's transport/storage boundary.
All five workbench asset contracts also pass. The stale private-focus assertion found
by the first PR run now checks shared-controller ownership and script order; its BFS,
failed-load controls, API, PDF and navigation requirements are retained.

The first PR browser run exposed an initial ResizeObserver notification restarting
the pending fit before the existing readability measurement. This was reproduced as
a failing frame-step regression and corrected in the single scheduler. The two restored
zoom cases were likewise demonstrated failing before correction. Updated native tests
cover explicit retained-snapshot routes and actual zoom continuation after focus and
source return. Full native and production results must come from the updated PR head.

The final combined gate ran with the Maven build cache disabled, as in authoritative
CI, after a local cache restore could not resolve reactor snapshot artifacts. This
rerun executes the same tests; no assertion or verification gate is bypassed.

Local native Chromium cannot start in this execution environment: the operating
system rejects Chrome's process-singleton socket call with `EPERM`, before any page
or application assertion executes. This is recorded as a blocked native run, not a
passing or skipped browser acceptance. Native component, production workspace,
responsive/fullscreen, accessibility and export acceptance remain required in the
existing CI workflow; their source-bound results belong on the pull request.

No test gate or workflow was relaxed to accommodate this environment. The existing
Maven/JUnit ownership is retained, including the real browser and application tests.

### Reproduction

Use Java 25 with its `bin` directory on `PATH` (the Word archive fixtures invoke
`jar`), and the repository's pinned Node runtime provisioned by Maven.

```bash
./mvnw -B -pl taxonomy-tooling,taxonomy-build,taxonomy-app -am test \
  -Dmaven.build.cache.enabled=false \
  -Dtest=JavascriptContractTest,TaxonomyDesignTokenContractTest,ArchitectureEditorBoundaryTest,ArchitectureWorkbenchAssetContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -B -pl taxonomy-tooling -am test \
  -Dtest=JavascriptContractTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtaxonomy.javascript.mode=browser \
  -Dtaxonomy.javascript.sources=.github/scripts/architecture-diagram.browser.test.mjs,.github/scripts/architecture-source-lens.browser.test.mjs,.github/scripts/architecture-editor-lifecycle.browser.test.mjs

./mvnw -B -pl taxonomy-app -am verify -Ptest-local \
  -Dtest=TaxonomyDesignTokenContractTest,ArchitectureEditorBoundaryTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ArchitectureWorkspaceBrowserIT,ArchitectureWorkbenchUiIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

Local non-browser runs can use Maven's `-o` once dependencies and frontend packages
are provisioned. The regular CI lanes remain authoritative for native integration.
