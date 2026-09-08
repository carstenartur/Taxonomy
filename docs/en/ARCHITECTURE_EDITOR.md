# Architecture editor

Open **Architecture editor** from Projects or the snapshot workbench. The context line shows repository, workspace, branch, semantic workspace revision, last checkpoint and actor. Historical revisions/versions and central read contexts are read-only. Use **Create private workspace** when repository membership allows it; editing also requires the ARCHITECT or ADMIN role.

1. Search the element list or select an object in the graph. Selection also updates properties, relations, evidence and the read-only DSL view. Copy the page URL to retain the exact context and selection.
2. Choose **New element**, or edit an existing element's properties. New identities are assigned by the server and remain stable when the display name changes. Taxonomy references and imported provenance remain read-only.
3. Enter a reason and choose **Preview change**. Review the affected blocks and displayed revision, then **Accept operation**. Nothing is persisted by typing or previewing. **Discard draft** restores the accepted properties.
4. To connect elements, choose a relation type and enter the target identity. Selecting a graph edge fills the relation form. Submitting an existing triple updates its review status. The server rejects incompatible directions/types, duplicates and self-relations. The containment parent field moves or ungroups an element with cycle and single-parent checks.
5. **Preview deletion** lists any blocking dependencies. Remove or reassign them explicitly before deleting the element or relation. No cascade is hidden behind the action.
6. **Preview undo** or **Preview redo** in history requests a new audited inverse command. Later dependent changes can block it. History survives reloads; native text undo affects only draft input. Git baseline revert is a separate versioning operation.

Every accepted edit is stored durably with its inverse data, actor and reason. It advances the workspace revision and **does not create a Git commit**. Undo/redo and all accepted changes survive browser reload and application restart.

**Editor history** lists individual personal operations. **Version history** lists Git checkpoints separately. Choose **Create checkpoint/version** with a reason to save the complete current workspace as a stable Git version. Equal content does not create another commit. Later edits stay durably in the workspace until the next checkpoint. Publish, merge, branch/baseline and restore commands checkpoint pending edits at their version boundary.

If the workspace revision changes after preview, choose **Refresh, compare and preview again**, inspect the new state and accept explicitly. A network failure retains the exact command identity. **Resume pending checkpoint** finishes an interrupted version write after reload; it does not duplicate an already written Git commit. **Recover completed version** reconciles a completed version write when no newer uncheckpointed edits exist. Graph/list projections are rebuilt from canonical source on each load.

Graph and element list share the server model. The list shows 50 searchable entries per page; the graph renders at most 200 nodes and 400 edges at once. Focus or search can reach objects outside the viewport. Arrow keys pan the focused graph and +/− change zoom. Forms provide a keyboard alternative to graph interaction.

SVG and vector PDF use the complete deterministic server layout at the displayed workspace revision or Git checkpoint. They are independent of local pan/zoom. The editor currently uses derived layout and a read-only synchronized DSL view; editable DSL translation, saved layouts, shared undo, richer evidence decisions and extended history remain tracked in [#814](https://github.com/carstenartur/Taxonomy/issues/814). Existing analysis snapshots remain immutable.

Implementation decision: [ADR 0005](../adr/0005-private-architecture-editor.md). [Deutsche Anleitung](../de/ARCHITECTURE_EDITOR.md).
