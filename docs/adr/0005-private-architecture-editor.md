# ADR 0005: Private architecture editing over immutable Git context

Date: 2026-09-08. Status: proposed with the implementation in PR #1028.
Tracks [#814](https://github.com/carstenartur/Taxonomy/issues/814); extends [ADR 0003](0003-server-authoritative-architecture-workbench.md).

## Renderer spike and decision

Retain the offline D3 7.9.0/SVG stack behind `ArchitectureEditorRenderer`. Its input is the existing `DiagramScene`; its outputs are selection intents. It cannot call the command API. The session coordinates commands independently of the renderer. A future React Flow adapter must satisfy this same boundary.

| Criterion | Existing D3/SVG adapter | React Flow alternative |
| --- | --- | --- |
| Element/relation editing | Selection plus native forms; command preview owns acceptance. Keyboard operations use the same forms. | Built-in selection, connections and keyboard node movement; each event still needs conversion to semantic intents. |
| Keyboard, zoom and reflow | Explicit keyboard pan/zoom, labeled node/edge buttons, independent controls, paged semantic access and native dialogs. Existing browser matrix exercises effective 200%/400% viewports, forced colors and reduced motion. | Built-in keyboard and ARIA support is useful but cannot establish application form, dialog or zoom accessibility by itself. |
| Large scenes | Viewport filtering and a 200-node/400-edge DOM ceiling; search reaches every element. Adapter measurements cover 309, 1,000 and 5,000 neutral scene nodes with edges. | Visibility filtering and memoized components can bound work; controlled state needs careful subscription design. |
| Layout and exports | Directly reuses `LayeredDiagramLayoutService`, `DiagramScene`, SVG and vector PDF geometry. | Requires mapping server coordinates, measured node sizes and export geometry into a second view representation. |
| Testing | Integrates into existing authenticated role/browser fixtures. Renderer-only budgets are recorded separately from domain correctness. | Requires React/component test infrastructure alongside the existing Thymeleaf/JavaScript stack. |
| Offline cost and maintenance | No new browser dependency or bundler; reuses the existing pinned WebJar. | Adds React, React DOM, React Flow and a frontend build. This could be justified by richer interactions, but is not required for this private workflow. |

The comparison is a bounded integration assessment, not a claim that React Flow was implemented or benchmarked. The checked-in D3 adapter is the executable spike. Its CI evidence reports actual render/focus milliseconds, visible objects and DOM counts, with a 1,500 ms render/focus ceiling on shared runners. Neutral large-scene fixtures do not prove end-to-end latency for repositories with thousands of commands or complex evidence; that remains a separate #814 acceptance item.

Primary references: [D3 zoom API](https://d3js.org/d3-zoom), [React Flow performance](https://reactflow.dev/learn/advanced-use/performance), [React Flow accessibility](https://reactflow.dev/learn/advanced-use/accessibility). D3's programmatic transform does not automatically enforce the interaction scale extent; fit-to-model deliberately computes its complete transform from the server scene.

## Authority and persistence

`ArchitectureCommandPort` takes an immutable repository/workspace/branch/commit/actor/mode tuple. HTTP re-resolves the authenticated workspace and compares the tuple. A request cannot select another actor or upgrade central read mode. Only ARCHITECT/ADMIN requests in their private workspace can write. Every write also supplies a strong commit ETag, or `If-None-Match: *` for an absent branch. Preview runs the same semantic transformation and head check without inserting Git objects.

Typed commands create/update/delete elements and relations or change semantic containment. New editor element identities are assigned as `arch-<command UUID>` by the server; they are architecture objects, not invented taxonomy catalog entries. The canonical DSL type matrix supplies relation rules. Invalid direction, missing endpoints, self-relations, duplicates, containment cycles and multiple containment parents are rejected. Deletion reports dependent relations, mappings, views, references and evidence; this slice has no implicit cascade.

One accepted command creates one `architecture.taxdsl` commit through `ExpectedHeadDslCommitter` and its expected-old JGit ref update. The commit includes command identity, fingerprint, actor, rationale, correlation, causation, version and inverse target. Retrying the same identity and payload returns its original authority; reusing an identity with another payload conflicts. There is no browser graph replacement endpoint and no editor JPA mutation. A failed Git write cannot publish a projection.

Only affected DSL blocks are formatted; unrelated source and ordering remain byte-identical. Comments and unknown properties of edited blocks are retained. Duplicate identities/properties and unterminated edited blocks fail closed. The existing tolerant import parser remains unchanged. Unknown existing extension fields and taxonomy references are read-only in the editor.

The accepted Git commit is projected using the existing relation rebuild service. A projection failure returns HTTP 202 with the accepted SHA and `REBUILD_REQUIRED`; checkpoint comparison also detects stale or missing projection state after restart. Explicit rebuild uses the same exact context and expected head, without another model commit. Graph, forms, element list, source and exports always derive directly from the selected Git document, never from an unproven relational checkpoint.

## Durable semantic history

The lessons from [audio-analyzer semantic undo/redo](https://github.com/carstenartur/audio-analyzer/blob/master/docs/architecture/semantic-undo-redo.md) are stable command identity, reconstructible inverse data, actor ownership, dependency guards and append-only history. Taxonomy reuses the Git parent/child documents for reconstructibility instead of introducing that project's session/outbox store or copying client state.

Undo targets one of the actor's accepted editor commits; redo targets an accepted undo. Both append a new commit with fresh identity and target linkage. The original target must be reachable in the selected branch and scoped to the same repository/workspace. Later intersecting semantic objects, changed block text, dependent references, a merge or an already accepted inverse block the operation. A relation touches both endpoint element identities. Unrelated later edits survive the inverse. Git handle reopen tests prove replay, history, undo and redo recover from persisted Git data without a browser stack.

The UI lists the latest 50 scoped commands and asks the server to preview eligibility. It does not infer permission from cached history. Native text undo is limited to unaccepted form input; discarding a draft and semantic undo are distinct actions. Git baseline revert remains in the existing versioning workflow.

## Session, layout and delivery boundary

`/architecture/editor` provides the private workflow. Existing `/architecture/workbench` routes and immutable analysis snapshots retain their behavior. Deep links pin repository, workspace, branch, commit and selection. Workspace query routing is explicitly propagated to reads, commands and exports. Each reload invalidates older read/preview results. After acceptance the UI loads the returned immutable commit. A moved branch returns structured HTTP 412; refresh/compare/repreview precedes explicit reacceptance. Ambiguous network failures retain the same command identity for a safe explicit retry.

Layout is derived, not a canonical artifact. No persistent `ApplyLayoutChange` command is exposed: pan, zoom and focus are disposable local state, and export headers identify `DERIVED_SERVER_LAYOUT`. Semantic containment changes use `MoveOrGroupElement`. Shared undo, collaborative sessions, persistent personal/shared layouts, editable DSL-to-command translation, richer evidence decisions, history pagination beyond the displayed window, and end-to-end large-repository budgets remain follow-ups under #814. This PR must not auto-close that broader issue.

## Verification

Domain tests cover transformations, text preservation, idempotent no-ops, invalid relation/type combinations, deletion dependencies, containment and guarded inverses. Application tests cover exact context isolation, stale heads, command identity reuse, concurrent writers, projection failure/rebuild and personal history. Hibernate integration closes all Git handles between command, replay, undo and redo. Controller/export tests establish commit and geometry parity; architecture fitness tests prevent direct JPA writes and renderer transport.

The existing role/browser acceptance runner adds real editor CRUD, dependency rejection, reloaded undo/redo, concurrent writer recovery, pinned exports, keyboard controls, Axe, responsive reflow and bounded renderer measurements. EN/DE user documentation describes the production boundary. The canonical final gate remains `./mvnw verify -DexcludedGroups="real-llm"`; local DNS failures are not a passing verification result. Exact-head CI and review results must be recorded on the PR before merge.
