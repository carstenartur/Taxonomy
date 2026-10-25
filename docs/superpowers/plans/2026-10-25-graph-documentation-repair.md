# Graph entry and documentation repair

> Implement the user-approved findings from the 25 October audit, using the existing test owners.

**Goal:** Make graph entry actions visibly open their result, remove fabricated screenshot success states, and document the affected current UI with traceable captures.

**Approach:** Keep the existing navigation lifecycle and graph renderer. Extend existing JavaScript/browser tests. Reuse the verified application artifact with current resource overlays for real browser journeys; label component fixtures precisely. Keep release/download changes in the separately merged PR #1304.

**Constraints:** No additional Actions workflow, duplicate app, new broad test framework, provider calls, or hidden screenshot substitutions. Do not claim a complete functional census from a static control inventory.

## Tasks

- [x] Graph: add a failing regression in the existing UI test owner for tree/impact-map entry, visible graph tab, selected code and initial upstream result. Cover invalid input, empty/error results and replacement of an older response. Implement navigation before rendering and focus the code input.
- [x] Screenshot integrity: remove fallback result/status injection from ScreenshotGeneratorIT, require actual results, and ensure the existing workflow packages only images captured in its run. Preserve diagnostic failures rather than masking them.
- [x] Documentation: correct graph entry instructions in DE/EN, add current wizard/editor/workbench captures and provenance, correct feature-matrix claims and retain an explicit view/control coverage inventory with honest test/screenshot status.
- [x] Verification: run existing affected JavaScript/JUnit checks, native browser checks where available, verify image hashes and links, review the diff independently and create a focused PR.

## Review focus

- A graph link outside the Graph page must reveal the page before a layout is rendered.
- Empty graphs and backend failures must remain visible and distinguishable.
- A late response for an earlier node must not replace a later selection.
- Screenshot artifacts must exclude stale checked-in images and preserve source provenance.
- Documentation must distinguish actual application captures from component fixtures and unverified historical images.

## Progress

- Baseline: main 5d4a0aaa; existing ux-state-recovery suite passed 30/30 during the audit.
- Ruling: the requested repairs and their rationale were already described and approved in conversation; proceed without another planning approval.

- Graph regression was observed failing before the fix. Existing JUnit-owned UI state tests pass 33/33; existing native Chromium browser tests pass 56/56.
- The real packaged application with current source resources passed the actual node-action/upstream journey. Seven real application screenshots cover Graph, database assistant, editor and persisted workbench in DE/EN; all image/source hashes are recorded and verified. Six decision-export component captures were refreshed separately with their fixture boundary retained.
- Live capture exposed a terminal-analysis refresh without a selected project. A one-line guard and four regressions were added to the existing continuation owner; 29/29 tests pass through its JUnit bridge, and the original live reproduction now has no uncaught page error.
- Existing workflow policy tests pass 11/11 and documentation link tests pass 6/6. Changed Java capture owners compile with Java 21 and their exact dependency versions. A complete Maven/Docker screenshot run has not been executed locally.
- Independent review corrected the screenshot container's missing pinned embedding-model mount/readiness and a graph-node click explanation. All 27 templates have an evidence mapping; uncovered control paths and retired synthetic images remain explicit gaps.
- PR #1304 merged while this repair was in progress. Integrate its current-main workflow changes without restoring old dependency-download commands; the recorded capture baseline remains 5d4a0aaa because its application artifact and overlaid resources are explicitly identified.
- Follow-up review removed four additional unsound catalogue/scope illustrations (#20, #32, #37 and #38) instead of replacing their outputs. Their current prerequisites and historical boundaries are documented.
- The retained proposal-acceptance capture now uses the actual relation-panel rebuild action. A separate running-application probe passed rebuild, proposal creation/acceptance, READY projection readback and visible accepted row without page errors.
- Complete screenshot generation can now be dispatched on a repair branch; publication remains guarded to main. The available GitHub connector cannot dispatch workflows and the browser is not signed in, so this full profile remains pending rather than being claimed successful.
