# Task: Add a Workspace Operation

## Goal

Add a new operation that a user can perform within their workspace — for example,
renaming a workspace, archiving a variant, comparing two workspaces, or transferring
selected changes to another user's workspace.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java` | Add the new operation method |
| `taxonomy-workspace/src/main/java/com/taxonomy/workspace/controller/WorkspaceController.java` | Add the new REST endpoint |

---

## Files usually touched

- `taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java` — orchestrates the operation
- `taxonomy-workspace/src/main/java/com/taxonomy/workspace/controller/WorkspaceController.java` — REST endpoint
- `taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceResolver.java` — if the operation needs
  to resolve a workspace by name or context
- `taxonomy-workspace/src/main/java/com/taxonomy/workspace/service/WorkspaceProjectionService.java` — if the operation
  produces a new projected view
- `taxonomy-domain/src/main/java/com/taxonomy/dto/WorkspaceInfo.java` — if the operation returns new workspace metadata
- `taxonomy-workspace/src/main/java/com/taxonomy/versioning/service/VersioningFacade.java` — if the operation involves
  DSL content or Git operations (branching, merging, cherry-picking)
- `taxonomy-workspace/src/main/java/com/taxonomy/versioning/service/SelectiveTransferService.java` — for selective content transfers between contexts

---

## Files usually not touched

- `taxonomy-domain/src/main/java/com/taxonomy/model/RelationType.java` — relation types are independent of workspace operations
- `taxonomy-dsl/` — DSL parser/serializer are independent; touch only if the operation
  reads or writes DSL content directly
- `taxonomy-export/` — export is independent of workspace operations
- `taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/` — provider analysis is a separate capability
- `taxonomy-knowledge/src/main/java/com/taxonomy/catalog/service/TaxonomyService.java` — catalogue import has separate authority;
  workspace operations do not modify the taxonomy catalog

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `GET /api/workspace/list` | `WorkspaceController` |
| `POST /api/workspace/create` | `WorkspaceController` |
| `PUT /api/workspace/{id}/rename` | `WorkspaceController` |
| `PUT /api/workspace/{id}/description` | `WorkspaceController` |
| `POST /api/workspace/{id}/switch` | `WorkspaceController` |
| `POST /api/workspace/{id}/archive` | `WorkspaceController` |
| `DELETE /api/workspace/{id}` | `WorkspaceController` |
| `POST /api/workspace/compare` | `WorkspaceController` |
| `POST /api/workspace/provision` | `WorkspaceController` |
| `POST /api/workspace/sync-from-shared` | `WorkspaceController` |
| `POST /api/workspace/publish` | `WorkspaceController` |

Choose the HTTP method and identity shape to match the operation's existing
contract. Metadata operations address workspace IDs; provisioning, synchronization,
publication, and context navigation also have workspace-scoped routes. Read
`WorkspaceController` and `DslApiController` rather than assuming one generic URL
pattern for all operations.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/workspace/taxonomy-workspace-provisioning.js`
  and `taxonomy-workspace-sync.js` — provisioning and synchronization controls
- `taxonomy-app/src/main/resources/static/js/versioning/taxonomy-context-bar.js`,
  `taxonomy-context-compare.js`, and `taxonomy-context-transfer.js` — context
  navigation, comparison, and transfers
- `taxonomy-app/src/main/resources/static/js/api/workspace-provisioning-api.js`
  — existing provisioning API adapter
- `taxonomy-app/src/main/resources/templates/index.html` — workspace panel section;
  add the button or menu item
- i18n: add labels to the appropriate English/German bundle pair under
  `taxonomy-app/src/main/resources/i18n/`, such as `messages.properties` and
  `messages_de.properties`

---

## DTOs / domain types

| DTO | Usage |
|---|---|
| `WorkspaceInfo` | Workspace metadata returned by the API |
| `WorkspaceRole` | Role of the user within the workspace |
| `RepositoryState` | Current Git state of the workspace |
| `TransferSelection` | A set of changes selected for inter-workspace transfer |
| `TransferConflict` | Conflicts detected during a transfer operation |
| `ContextRef` | A reference to a specific commit/variant context |

---

## Tests to run

```bash
# Workspace-owned behavior and contract tests, including needed sibling modules
./mvnw -pl taxonomy-workspace -am test

# Application endpoint, persistence, security, and restart composition when affected
./mvnw -pl taxonomy-app -am test
```

`taxonomy-app` depends on `taxonomy-workspace`; `-am` includes the workspace
library and its unit tests instead of requiring preinstalled sibling artifacts.
Application-level endpoint, persistence and restart tests remain in `taxonomy-app`.

Relevant existing tests:

- `WorkspaceManagerTest`, `WorkspaceManagerBehaviorTest`, `WorkspaceResolverTest`,
  `WorkspaceContextResolverTest`, and `RepositoryStateGuardTest` in
  `taxonomy-workspace` — operation behavior, request identity, and write guards
- `WorkspaceControllerBranchCoverageTest` in `taxonomy-workspace` — controller
  branches; `WorkspaceControllerTest` in `taxonomy-app` — composed HTTP behavior
- `ConflictDetectionServiceTest` and `SelectiveTransferServiceTest` in
  `taxonomy-workspace` — merging and selective transfers
- `DslApiControllerTest` in `taxonomy-app` — composed DSL/checkpoint operations

These are focused iteration commands. Complete
`./mvnw -B verify -Pci -DrunOnnxTests=true` and the applicable companion gates in
[Maven Verification](../MAVEN_VERIFICATION.md). A module selection or the default
`verify` lifecycle does not certify all browser, database, or restart scenarios.

---

## Documentation / screenshot updates

- `docs/en/WORKSPACE_VERSIONING.md` — add the new operation to the workspace operations section
- `docs/en/USER_GUIDE.md` — if the operation is user-facing, add a walkthrough
- Screenshots: regenerate the workspace panel screenshot if it shows the list of
  operations; follow [capture ownership and provenance rules](../../../.github/copilot-ref-screenshots.md)

---

## Common pitfalls

1. **Roles and ownership:** Preserve both global authorization and workspace-owner
   checks. The application-owned `AuthorizationRulesConfigurer` defines route
   roles; read it before adding an operation. Administrative eviction is an
   existing `WorkspaceController` → `WorkspaceManager` path, while system
   repository operations have their own service contracts.

2. **Git state guard:** `RepositoryStateGuard` enforces that certain operations
   cannot run while the repository is in a conflicted or rebasing state.
   Use its exact `WorkspaceContext` overload so actor, workspace, repository, and
   branch cannot disagree; preserve read-only and operation-in-progress checks.

3. **Terminology in the UI:** User-facing text must use workspace domain terms,
   not raw Git terms. See the Terminology Rules in
   [Developer Guide](../../en/DEVELOPER_GUIDE.md#terminology-rules).

4. **Creation and provisioning differ:** `createWorkspace` allocates a new ID and
   initially unprovisioned workspace. It does not deduplicate by display name.
   Preserve operation-specific retry semantics and provisioning authority.

5. **Context resolver:** The `WorkspaceContextResolver` maps the authenticated
   username to the correct workspace. Always use it rather than reading the username
   from a raw request parameter.

6. **Editing and checkpoints:** Accepted editor operations are durable semantic
   revisions; Git commits are explicit stable checkpoints. Preserve that separation
   when an operation modifies architecture content; see
   [ADR 0005](../../adr/0005-private-architecture-editor.md).
