# Existing architecture export repair

## Root causes and changes

The analysis-page diagram buttons sent only `businessText` to the legacy
`/api/diagram/{format}` endpoints. `ExportFacade.analyzeAndProject` called
`LlmService.analyzeWithBudget` and rebuilt a different, 20-node view. That path
was not connected to the analysis progress UI.

The browser now posts a frozen copy of `TaxonomyState.currentArchView` to
`/api/diagram/current/{formatId}`. The existing registry and raw projection are
reused without scoring, fetching current taxonomy data, a second selection
policy, or workspace/history writes. Missing or stale browser state and invalid
or oversized graphs fail explicitly, without fallback analysis. Legacy
text-driven endpoints remain API-compatible. This is a client-provided working
view, not a certified historical snapshot; project snapshot exports remain the
appropriate historical-provenance route.

Capacity is bounded to 10,000 nodes and 30,000 relationships, without silent
trimming. Null/duplicate nodes, invalid or dangling relationships and non-finite
relevance values are rejected. Format-specific package limits still apply.
The UI displays an indeterminate busy indicator, receiving/download-ready or an
error, suppresses duplicate requests and restores previous control states.
No invented percentage or successful disk-save claim is made.

The two blocking Java CodeQL findings on main (`1b29548`, run 35566429486,
latest artifact 10634821105) concern the spatial-bucket loops in
`VisioPresentation`. Validated integer bounds and widened counters reject
non-finite/out-of-grid coordinates and terminate at `Integer.MAX_VALUE` without
wrapping. No scan, security threshold or baseline exception was weakened.
This is not proof that normal user exports encountered integer overflow.

## Executed supporting verification

- Five Node VM regressions failed before the browser change and pass afterwards:
  current-view payload, busy/error state, missing/stale rejection, preservation
  and duplicate-request protection. The existing `test:state-render-consistency`
  command includes them in both Maven-owned UI contract entrypoints.
- The original grid accepted NaN; the fixed grid rejects invalid geometry and
  terminates for ordinary and integer-boundary data.
- Real projection/controller/registry/Visio/ZIP tests preserve 50/150 nodes and
  49/149 edges without an LLM or architecture derivation service. Seven invalid
  requests fail explicitly. JUnit wrappers use the same assertions as the
  executed plain Java drivers.
- Local Spring HTTP: the new route was 404 before. With compiled changes overlaid
  it returns HTTP 200 and a 150-node VSDX (43 parts), and HTTP 400 for missing/empty
  architecture, without scoring.
- All five modified production-source blobs match the uploaded Git objects and
  compile with Java 21 against downloaded CI application runtime libraries.

The supporting runtime is the application/dependency artifact for PR #1098's
`92e5822` tree, not a fresh full Maven build of main. Full reactor/JUnit-wrapper,
remote CodeQL and complete browser navigation verification remain outstanding.
Local full-browser automation did not complete. No full-browser acceptance or
opening in desktop Visio is claimed.

```sh
./mvnw -B -ntp -pl taxonomy-export,taxonomy-app -am test \
  -Dtest=VisioPresentationGridTest,CurrentDiagramExportTest \
  -Dsurefire.failIfNoSpecifiedTests=false
node --test taxonomy-app/src/test/js/current-diagram-export.cjs
./mvnw verify -DexcludedGroups=real-llm
```

## Preferences reset remains unresolved

Reported sequence: obtain an architecture, open Preferences, change max nodes
50 to 150, save; the working UI becomes empty and offers a saved-architecture
restore. This is not intended behavior.

The checked handler neither resets analysis nor reloads the page. A local
authenticated HTTP test saved an active draft with text, scores and architecture,
changed the preference to 150, then read the identical draft and version. This
only narrows that local server path; it neither reproduces nor dismisses the
reported browser/runtime behavior. Full navigation and deployed lifecycle still
need investigation. Do not add speculative destructive reset/restore logic.
