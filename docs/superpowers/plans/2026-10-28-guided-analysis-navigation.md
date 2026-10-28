# Guided analysis navigation implementation plan

> Implement inline with `superpowers:executing-plans`; verify behavior with the existing Maven-owned JavaScript and browser contracts.

**Goal:** Make the existing clarification, reformulation and architecture investigation workflows discoverable, provide an explicit Unfocus action, and keep Graph Explorer synchronized with intentional taxonomy selection.

**Design:** Extend the current analysis page and controllers. Use the existing project requirement workflow and architecture editor; do not create a second synthesis or investigation implementation. A proposed next step never claims human approval or starts a model request merely by rendering or following a navigation link.

**Alternatives:** A separate wizard would duplicate existing state and obscure provenance. Menu links alone would not explain partial results. Use persistent entry points plus contextual next-step guidance and the existing actions.

**Constraints:** Preserve explicit adoption, context-path routing, workspace identity, retained analysis evidence, existing graph query mode/hop depth, and keyboard focus. No new dependencies or weakened gates.

## Task 1 — Linked graph selection

- [x] Extend `ux-state-recovery.cjs`, `diagram-keyboard-controls.cjs` and the browser UX contract to fail on stale graph selection, hidden-page fetching and late responses.
- [x] Add a selection entry point to `shared/taxonomy-graph.js`, preserving the last node query and deferring hidden-page requests until activation.
- [x] Connect intentional List/Tabs, Sunburst, Tree/Canvas, Decision and Summary selections. Rendering/restoring a view must not issue a graph request or steal focus.
- [x] Run the selected Maven-owned contracts. Native browser regression is authored; CI execution is pending because local Chromium cannot create sockets.

## Task 2 — Reversible focus

- [x] Add a component regression that focuses a node, then restores the full graph with Unfocus while retaining selection and other filters.
- [x] Toggle the details action and focus toolbar between Focus and Unfocus; provide English/German labels.
- [x] Verify repeated focus/unfocus and unchanged evidence.

## Task 3 — Discoverable next steps

- [x] Add behavior checks for empty, running, partial, stale and completed results, missing saved snapshots and deployment under a context path.
- [x] Add visible Reformulate requirement and Architecture focus lens entry points plus next-step guidance beside analysis results.
- [x] Route open assessments through existing recovery/report controls, reformulation through the exact saved requirement or explicit Save as requirement action, and the lens to the current editable architecture workspace.
- [x] Explain unmet prerequisites; keep navigation separate from synthesis, adoption and model calls.
- [x] Update the existing user guides and run the full Maven JavaScript contract suite. Native-browser execution remains a CI gate.

## Review focus

- Older graph responses must not restore a previously selected node.
- Keyboard and leaf-node selections must work without scrolling/focusing the right pane.
- Page rendering and restoration must not silently trigger graph/model requests.
- Unsaved/current text must not be confused with a historical saved snapshot.
- Links and unavailable actions must remain understandable on mobile and under an application context path.

## Verification record

- Linked graph selection and Unfocus regressions failed on the original behavior and passed after implementation.
- Review findings were reproduced before fixes: Summary selection, live workspace pin, editable-input deduplication, enriched query mode, cancellation, draft preservation, queued save conflict and central-context routing.
- Navigation waits for the latest draft, including edits during an in-flight save. Promotion saves before creation and preserves the analysis; shared drafts with content explain the missing writable workspace.
- Local Chromium cannot launch because socket creation is prohibited. The requested escalation was rejected by the environment policy; native UI validation is delegated to the existing CI lane.
- The initial post-review full JavaScript suite passed 1,557 tests. Final persistence regression verification and browser CI results are recorded in the PR.

Review scope decisions: automatic synthesis/adoption and redesigning the editor are outside this change. The existing explicit review/adoption workflows remain the destination. No deployment or merge is included.
