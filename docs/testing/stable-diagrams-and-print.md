# Stable analysis diagrams and full-sheet printing

## Behavior and boundaries

Live score updates retain the reading state of SVG Tree, Canvas Tree, Decision
and Sunburst. State belongs to an analysis, view and selected tree taxonomy.
SVG and Canvas share the tree context. The retained state includes the selected
node, expanded branches, zoom, pan, scroll offsets and focused controls. A
surviving ancestor anchors the viewport when filtering removes a selected node.
New analyses and explicitly restored drafts start a new context.

List, Tabs, Sunburst, Tree, Decision and Summary use the same relevance selector.
Positive-only projections retain context ancestors but exclude unassessed and
zero siblings. Hide-zero retains pending product evidence until its family is
assessed. Saved architecture elements, relationships and scores are not modified
by Summary filtering. Search can explicitly reveal a hidden result by switching
the visible selector back to All.

Progress snapshots, checkpoint replay, durable observation and terminal SSE
events carry recorded explanations. The live preview retains whole reasons
within a 1 MiB UTF-8 budget; final evidence is not limited by that preview budget.
An absent preview reason cannot erase an earlier nonblank reason. A late score
or reason callback cannot replace an accepted terminal result. No extra model
request is made to open a saved explanation.

The print controls separate grouping from paper size:

| Layout | Output |
|---|---|
| A4 pages | Readable A4 landscape continuation pages |
| One large sheet | One continuous sheet containing every selected taxonomy |
| One sheet per subtaxonomy | One continuous sheet for each retained taxonomy |

Full-sheet modes offer A4 through A0, initially A1. A1 is 594 × 841 mm and A0 is
841 × 1189 mm; orientation is chosen from the measured content. The print tree
includes selected collapsed/offscreen branches and complete saved explanations.
Detached label reflow improves the fit without changing the live hierarchy.
The legacy A4 paginator and the existing SVG-export appendix remain separate.

## Maven verification

Verification used Java 25 and the existing Maven owners. The local browser was
Chromium/ChromeDriver 149.0.7827.55. No dependency, workflow, alternate test runner
or remote provider was added.

| Owner | Observed result |
|---|---:|
| `JavascriptContractTest`, all nonbrowser callbacks | 1,489 passed |
| Focused analysis service/controller/use-case/cluster tests below | 273 passed |
| `BrowserSessionTest` | 14 passed |
| `TaxonomyTreeLayoutIT` | 28 passed |
| `TaxonomyBrowseDecisionUiIT` | 30 passed |
| `TaxonomyDecisionDiagramUiIT` | 14 passed |

All listed executions had zero failures, errors and skipped cases. Reproduction
commands, from the repository root with Java 25:

```bash
./mvnw -B -pl taxonomy-tooling -am \
  -Dtest=JavascriptContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -B -pl taxonomy-analysis -am \
  -Dtest=AnalysisCompletionContractTest,AnalysisProgressRegistryTest,RootScoringFlowTest,AnalysisSseEventMapperTest,StreamRequirementAnalysisUseCaseTest,LlmServiceBranchCoverageTest,ClusterAnalysisDetailedProgressTest,ClusterAnalysisRecentProjectionTest,DurableClusterAnalysisObservationTest,RootAssessmentProtocolTest,RootClarificationRecoveryTest,ClusterAnalysisBackupRestoreTest,ProductDemoReplayTest,AnalysisScopeFlowTest,DemoReferenceEvaluatorTest,ReconcilePromptCompactionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

./mvnw -B -pl taxonomy-app -am -Ptest-local \
  -Dtest=BrowserSessionTest \
  -Dit.test=TaxonomyTreeLayoutIT,TaxonomyDecisionDiagramUiIT,TaxonomyBrowseDecisionUiIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dfailsafe.failIfNoSpecifiedTests=false verify
```

The execution environment used cached dependencies in offline mode and explicit
browser paths through the documented `test-local` properties. A Mockito startup
agent supplied by the cached test dependency replaced unavailable dynamic JVM
attachment. Test selection and assertions remained Maven-owned.

The branch was rebased onto main `908b5aee` after root-clarification handling
merged in #1318. Verification includes the combined transport of saved reasons
and UNKNOWN root coverage, compatible callback constructors, and coverage-only
updates to filtered List/Tab headings without replacing their focused elements.
An independent review checked this integration separately from the original
viewport, relevance-filter and print review.

The PDF cases inspect actual Chrome PDFs with PDFBox: physical A1/A0 dimensions,
one combined page or one page per retained taxonomy, complete selected reasons,
excluded reasons absent, continuous edges, and unchanged source/viewport state.
The deliberately verbose Decision fixture retains 26 nodes and 24 edges; A1
prints at at least 80% text scale and A0 at the original 14 px size. These are
fixture measurements, not a guarantee that every possible tree fits at that size.

The full canonical gate, `./mvnw -B verify -Pci -DrunOnnxTests=true`, requires
Docker and the other prerequisites in [Maven verification](../dev/MAVEN_VERIFICATION.md).
Neither a Docker executable nor a Docker socket was available here, so that full
gate was not run locally. The focused results above do not replace the existing
CI browser matrix, container/database, interoperability or coverage gates.

## CI follow-up regressions

The first complete [PR #1320 CI run](https://github.com/carstenartur/Taxonomy/actions/runs/40540591150)
at `6601e1f8` exposed two gaps in the initial focused verification:

- `DecisionDiagramReadingTest` still called a removed private single-sheet
  helper. The same build-policy failure stopped the core, plugin, document and
  three database workflows. Its contract now uses the actual print entry point
  with explicit A1/A0 options. It checks physical and printable dimensions,
  transformed content containment, all 101 selected nodes and 100 continuous
  edges, complete saved reasons, and the larger usable scale on A0. The former
  balance check for an arrangement of A4 fragments is replaced by checks for
  the requested complete hierarchy.
- The full Admin browser journey revealed that the reduced-motion CSS rule
  introduced a `0.01ms` transition on SVG transforms. D3's saved zoom could
  already reflect the new transform while the browser still rendered its old
  position. Disabling those transitions with `0s` keeps both states synchronous;
  the existing animation rules remain unchanged. The browser regression checks
  the actual rendered transform and selected-node position for Tree and
  Decision, with both reduced motion and ordinary motion enabled.

The print failure and the full Admin journey were reproduced before the fixes.
The print contract remains owned by `DecisionDiagramReadingTest`; the motion
regression is part of `TaxonomyTreeLayoutIT`. No workflow, timeout or existing
position tolerance is relaxed.

Final focused verification of the corrections:

| Maven owner | Passed | Failures / errors / skips |
|---|---:|---|
| `DecisionDiagramReadingTest` | 1 | 0 / 0 / 0 |
| Complete `JavascriptContractTest` inventory | 1,489 | 0 / 0 / 0 |
| `BrowserSessionTest` | 14 | 0 / 0 / 0 |
| `TaxonomyTreeLayoutIT` | 32 | 0 / 0 / 0 |
| `TaxonomyBrowseDecisionUiIT` | 30 | 0 / 0 / 0 |
| `TaxonomyDecisionDiagramUiIT` | 14 | 0 / 0 / 0 |
| `UiScenarioIT`, primary Admin scenario and driver completion | 2 | 0 / 0 / 0 |

The full Admin journey completed 74 workflow checks and 21 accessibility states,
including the exact previously failing growth assertion. The test used the
application packaged from the corrected working tree and pinned Chromium 149.
The new four-case motion regression first failed for both reduced-motion cases
with the old CSS and passed for both ordinary-motion controls; all four then
passed with the corrected CSS.

The focused print and JavaScript owners were invoked through
`test-compile surefire:test` with their named `-Dtest` selectors after the pinned
Node dependencies were provisioned. The browser suites used the existing
`-pl taxonomy-app -am verify -Ptest-local` lifecycle. The full Admin journey used
`TAXONOMY_UI_SUITE=primary` and
`TAXONOMY_UI_PROFILE_FILTER=primary-admin-chromium` with the already prepared
`.github/ui-verification-pom.xml -Pshard failsafe:integration-test failsafe:verify`
owner and the packaged application.

An additional build-policy run during diagnosis passed 445 of its 455 cases;
the ten existing Helm prerequisite checks were skipped because Helm was absent.
The final strengthened print contract was rerun afterward. These local results
remain distinct from the required complete CI run.

A fresh repeat on 28 October 2026 confirmed the print contract, all 1,489
JavaScript contracts, 14 browser-session tests and all 76 diagram browser tests
through Maven with Java 25. Chrome for Testing Headless Shell 149.0.7827.55 was
used with the matching ChromeDriver because this execution environment does not
permit the full Chrome profile's Unix socket. Before applying the CSS change,
the four-case motion regression again failed only in its two reduced-motion
cases; all four passed afterward. The existing CI browser selection is unchanged.

## Illustration provenance

The [German](../images/diagram-print-controls-de.png) and
[English](../images/diagram-print-controls-en.png) toolbar crops show production
JavaScript, CSS and translations with an authored deterministic component
fixture. Their [capture record](../images/diagram-print-controls-2026-10-27.json)
identifies exact source and image hashes. The crops illustrate controls; they do
not establish a complete application session or live-provider acceptance.
Raw logs, PDF outputs and diagnostic captures remain under
`target/diagram-verification/`.
