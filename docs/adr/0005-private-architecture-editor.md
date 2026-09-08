# ADR 0005: Durable semantic editing and explicit Git checkpoints

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

## Decision: three authorities with distinct lifetimes

Git is the authority for durable architecture versions/checkpoints. The durable workspace session and semantic operation log are authoritative for accepted uncheckpointed editing revisions. Every accepted semantic edit is durably journaled; only explicit semantic checkpoints create Git commits.

| Concept | Authoritative representation | Identity / concurrency |
| --- | --- | --- |
| Accepted architecture operation | Append-only `editor_operation`, including complete before/after canonical DSL for reconstructible inverses | UUID command/operation ID; previous and resulting semantic revision |
| Current private workspace | `editor_workspace` canonical DSL snapshot and semantic revision | Repository/workspace/branch scope; locked revision comparison |
| Architecture version | Complete deterministic `architecture.taxdsl` in Git | Git commit SHA; separate checkpoint command and durable intent |

A property change and an architecture release are different abstractions. A live editor needs detailed acceptance, actor, inverse and conflict data. A durable architecture version should describe an intentional stable state. Turning every gesture into a commit makes version history noisy and ties undo eligibility to branch topology, merges and Git retention. Git is therefore not the live editor undo stack.

`ArchitectureCommandPort` retains typed commands, authenticated exact scope, stable UUID identities, rationale and bounded metadata. `If-Match: "workspace-revision-N"` checks the semantic revision; a checkpoint does not invalidate an already previewed edit at the same revision. A Git SHA is never an operation ID. Private writes require ARCHITECT/ADMIN authorization and server-resolved workspace membership. No browser graph replacement endpoint exists.

## Accepted operation transaction

`EditorJournal` uses the application JPA database and its own transaction. A pessimistic workspace-row lock serializes different application instances. Revision is checked under that lock, with a unique scope/revision constraint and a primary-key constraint protecting initialization races. Before returning acceptance, the transaction commits:

- command/operation UUID, actor, timestamp, rationale, type, correlation/causation;
- versioned complete before/after canonical source, sufficient to reconstruct an inverse without Git;
- affected semantic identities, including both relation endpoints;
- undo/redo target UUID, prior and resulting semantic revision;
- deterministic length-prefixed SHA-256 idempotency fingerprint;
- the resulting canonical workspace snapshot and revision.

The command and its snapshot either both commit or neither does. Retrying an identical identity/payload returns the original operation and revision; changing the payload conflicts. Preview and history reads do not initialize a workspace, advance revision, insert an operation or write Git. Rejected transformations leave no durable residue.

Full before/after source is an intentionally simple reconstructible body format, not Java object serialization. A versioned nonempty envelope preserves empty source on databases such as Oracle that collapse empty strings to NULL. It preserves the existing inverse algorithm and source anchors; it costs storage proportional to snapshot size per operation. Future lossless body compaction needs a versioned decoder and must retain every inverse and retry guarantee.

Only affected DSL blocks are formatted. Unrelated source/order remains byte-identical. Edited blocks retain unknown properties and comments, with the existing normalization limits. The source-preserving patcher, quote/comment-aware block parsing, deletion dependency preview and validation matrix remain unchanged.

## Undo, redo and recovery

Undo targets a personal accepted operation; redo targets a personal accepted undo. Each appends a fresh UUID operation. Neither removes history. The durable operation sequence supplies later affected-object intersections and already-inverted checks. The source patcher independently guards changed blocks, dependencies and restoration anchors. Unrelated later operations survive. Version imports are explicit journal boundaries rather than fabricated user edits; their intersecting changes block unsafe inverses.

A complete restart reloads the workspace snapshot, revision, operation bodies, actor/timestamp and target links from the database. No browser state, service singleton or one-commit-per-operation Git chain participates. The restart acceptance test launches four separate JVMs against the same database, accepting, undoing, redoing and verifying while Git still has no editor commits.

## Checkpoint transaction and failure model

`CreateCheckpointCommand` is a separate application command:

1. Under the workspace database lock, check the expected semantic revision and command identity, then persist `editor_checkpoint` with the complete frozen DSL, actor/rationale, timestamp, expected Git parent and exclusive/inclusive revision range `(fromRevision, throughRevision]`. Reserve the workspace for this intent.
2. Outside that transaction, construct the Git blob/tree/commit from the durable intent. Timestamp, parent, author, text and footers are fixed, so every retry computes the same SHA. Advance the branch using expected-old-object comparison. Equal DSL reuses the previous checkpoint without a redundant commit.
3. In another database transaction, record the resulting SHA, checkpoint revision and completion, then release the reservation. No semantic operation is appended and the semantic revision does not change.

There is **no atomic database/JGit transaction**. If Git succeeds but completion fails, the persisted intent is still pending. A retry or **Resume pending checkpoint** recreates the same object ID, recognizes an already applied commit and completes the database linkage. If Git has not succeeded, the same command can retry the same write. While pending, reads remain available and editing is blocked rather than silently losing the reserved version. Git ref conflicts fail closed. When the deterministic recovery check proves that a different Git head prevented this intent from being applied, the intent is retained as rejected and its reservation is released. Unrelated external ref changes must then be reconciled explicitly; a new checkpoint identity is required.

Version application boundaries checkpoint current edits before branch/baseline creation, publish, merge, restore, revert or cherry-pick. Workspace locks guard the subsequent version action and import. A changed resulting version is journaled with complete snapshots as `VERSION_IMPORT`. Every adopted version also gets a durable `editor_checkpoint` link (`origin=VERSION_ACTION`) containing its Git SHA, snapshot, semantic revision range, actor and rationale. This preserves the association for native merge/restore/publish commits whose messages were produced by the existing version workflow. An explicit editor checkpoint uses `origin=EDITOR_CHECKPOINT` and additionally carries this linkage in Git footers. A Git success followed by database failure is not reported as atomic: **Recover completed version** imports the completed Git state only when no newer uncheckpointed edits exist. Otherwise reconciliation requires an explicit semantic decision; accepted workspace data is never overwritten automatically.

## Exactly when Git commits are created

| Application action | Git behavior |
| --- | --- |
| Typed create/update/delete element or relation; containment move; undo; redo | **No Git commit.** One durable semantic revision per accepted change. |
| Preview, read/reload, history, search, export, projection rebuild, draft discard | No Git commit and no semantic operation. |
| Explicit Create checkpoint/version | One commit for changed canonical DSL; equal content reuses the prior version. |
| Publish / source integration / merge | Checkpoint uncheckpointed workspace content, then the existing explicit publish/merge version writes if content/topology requires them. |
| Branch or baseline creation | Checkpoint source edits if necessary; creating a ref at an existing snapshot adds no commit itself. |
| Restore / revert / cherry-pick | Preserve uncheckpointed edits first, then create/apply the requested version and journal its resulting state. |
| Existing explicit DSL/version commit, initial repository/workspace seed, portfolio publication, external Git import | Remain intentional version/provisioning application actions; they are not normal editor commands. |

The changes do not redefine unrelated legacy relation-decision or portfolio APIs as editor commands. Existing version-history compare, restore and branch operations continue to address Git SHAs.

## Projections, search, exports and UI

The editor graph, server-generated search index for the element list, properties and source are rebuilt from the exact canonical DSL selected by the server: either a durable workspace revision or an immutable Git checkpoint. The UI receives derived views; it has no independent canonical graph. A workspace view carries a semantic revision; a version view carries a Git SHA. SVG/vector PDF use the same selected source and deterministic server geometry, including exports before the first Git checkpoint.

The pre-existing relational relation projection and Hibernate Search version index retain their Git-checkpoint source and existing rebuild/readiness semantics. They are not relabeled as projections of uncheckpointed workspace edits. Editor list/search and graph rebuilding use the workspace snapshot directly. Any future cached workspace index must be scoped and revision-stamped separately and must never substitute its rows for either authority. Deleting derived indexes must not remove workspace or operation rows.

Editor history shows the latest 50 personal semantic operations. Version history lists stable Git checkpoints separately. Historical revision/version links are read-only; refresh opens the current durable workspace. Structured HTTP 412 responses name semantic revisions, and refresh/compare/repreview precedes explicit reacceptance. Native text undo covers unaccepted form input only. Ambiguous transport failures preserve command identity; checkpoint recovery is discoverable after reload.

## Comparison with audio-analyzer

The reference is [durable semantic undo/redo](https://github.com/carstenartur/audio-analyzer/blob/master/docs/architecture/semantic-undo-redo.md). Both systems atomically retain accepted semantic operations, canonical state, revision, retry identity and inverse linkage. Both recover personal undo/redo after restart and reject later intersecting changes without rewriting history.

Taxonomy retains its own typed architecture commands and source-preserving DSL patches. Its body format stores full before/after source rather than copying workflow operation codecs. It has no ordered SSE stream in this editor, so acceptance does not require an SSE outbox. Projections are rebuilt on demand; the durable checkpoint intent is the recovery record for the separate Git side effect. Workflow-specific classes, shared collaboration modes and client graph synchronization were not imported.

## Verification and scope

Acceptance covers 100 mixed semantic operations without Git advancement, full process restart, retry, two independent writers, append-only inverses/conflicts, checkpoint retry after Git success, two checkpoints separated by edits, version branch/compare/restore, both canonical projection sources, exact revision exports and browser history separation. Existing domain source-preservation, dependency and parser tests remain unchanged. The inherited database HTTP acceptance test exercises preview, acceptance, retry, undo/redo and checkpoint separation against PostgreSQL, Oracle and SQL Server. Application schema validation includes the new entities and PostgreSQL migration V19. Restart guarantees require a persistent database; an intentionally in-memory demo database cannot survive its process.

The canonical gate is `./mvnw verify -DexcludedGroups="real-llm"`, together with the existing database/browser/security/export integration jobs. A blocked dependency download is not a passing test; current-commit CI evidence must be checked before merge.

D3/SVG renderer, server validation, read-only DSL view and immutable analysis snapshots are retained. Persistent layouts, editable DSL-to-command translation, shared undo, extended history pagination and full large-repository budgets remain follow-ups under #814.
