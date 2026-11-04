# One architecture diagram implementation

## Problem and scope

The navigation and source workspace delivered in #1367 are shared, but the source audit of
`521b8cfcdc13523fa8d25c3e86278f6bb6c8cdf9` found three diagram controllers behind shared
drawing helpers. Snapshot and analysis still own selection styling, D3 zoom, fitting,
search, focus and resize logic. The old impact force renderer is also retained behind
an override and an obsolete onboarding wrapper. These are implementation duplicates,
not necessary source distinctions.

Carsten requested a complete implementation, removal of duplicate source code, and
preservation of functionality. This design applies that already authorized cleanup.

## Ownership

`TaxonomyArchitectureDiagram.create(svg, options)` is the sole controller and drawing
entry point. Remove `connect`, the old force impact renderer, its alias/override,
onboarding patch and dead CSS. The real taxonomy Graph Explorer remains a separate
product feature.

The shared engine owns selection, search matching and dimming, neighborhood focus,
context filtering, keyboard/pointer activation, lens overlays, SVG nodes/edges/layers,
D3 zoom, stable measured fitting, resize observation, viewport retention, suspension
and teardown. Its public state is a defensive snapshot, not a second writable store.

Adapters own source identity, authorization/persistence, request lifecycles, domain
normalization, pure layout/presentation mapping, translated details, form actions,
KPIs and source-specific exports. They do not create diagram SVG primitives or own
another viewport controller. Layout returns data, never DOM or rendering callbacks.

## Preserved source contracts

| Source | Geometry and presentation | Limits and focus |
| --- | --- | --- |
| Working | Immutable server positions; compact cards; straight server edge endpoints; fixed 1000 × 420 viewBox | Viewport culling; 200 drawn nodes; 400 edges including up to 32 proposals; focus centers the selected card |
| Snapshot | Immutable server positions and endpoints; relevance cards and layer columns; `.architecture-viewport` retained for printing | No model cap; one-hop focus; full-model server exports retain provenance |
| Analysis | Existing deterministic layer layout, evidence/hotspots, relevance cards | 80 nodes before layout, selected node then anchor/hotspot/relevance priority; one-hop focus |

Differences are declarative presentation, viewport and limit options. Both read-only
adapters supply pure layout functions. Shared label wrapping, color parsing, contrast,
RGBA and edge routing have one implementation.

## Interaction and lifecycle guarantees

- A selection intent may be refused by the editor's dirty-form guard. Selection and
  focus must remain unchanged on refusal. Programmatic selection is idempotent and
  never invokes the guard recursively.
- Search highlights rather than filters. Enter reveals a hidden match, restores
  context/overview as needed and centers it, including under the analysis limit.
- Focus retains the preceding viewport; unfocus restores it. Source refresh/return
  preserves manually chosen viewports. The controller captures/restores sessions with
  both cameras. A focused neighborhood has an explicit anchor independent of the
  currently selected card, so inspecting a neighbor does not silently change scope.
- Hidden views do not fit fallback dimensions. One scheduler waits for measurable,
  stable bounds. User pan/zoom and viewport restoration cancel pending fitting.
- The lens uses displayed nodes and screen-aware radius; proposals, pointer handlers
  and keyboard handlers have one owner. Teardown cancels animation frames, transitions,
  observers and listeners. Inactive sources cannot run pending viewport work.
- Existing DOM classes, accessibility labels, focus restoration, markers and print
  geometry remain supported.
- Selection and focus-panel presentation retain card and child DOM identity so native
  double-click gestures survive reentrant callbacks. Lens assessment styles and the
  proposal overlay can update independently of card geometry.

The existing shared focus investigation engine and its persistence/authorization
adapters are not duplicated algorithms and remain intact. Removing endpoint/source
distinctions would damage read-only evidence scope and editable draft semantics.

## Acceptance

Behavioral tests exercise the actual shared controller for all three configurations,
including combined search/selection, rejected editor selection, fit cancellation,
screen transforms, limits and teardown. Existing native browser and production
workspace integration tests remain the acceptance gate. Replace tests coupled to
deleted private functions with equivalent public behavioral assertions; do not reduce
the functional checks. A final source audit checks ownership and call paths in addition
to test results. Success is reported only against the new verified commit.
