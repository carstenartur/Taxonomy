# Task: Add a Workspace Operation

## Goal

Add a new operation that a user can perform within their workspace — for example,
renaming a workspace, archiving a variant, comparing two workspaces, or transferring
selected changes to another user's workspace.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/java/com/taxonomy/workspace/service/WorkspaceManager.java` | Add the new operation method |
| `taxonomy-app/src/main/java/com/taxonomy/workspace/controller/WorkspaceController.java` | Add the new REST endpoint |

---

## Files usually touched

- `taxonomy-app/…/workspace/service/WorkspaceManager.java` — orchestrates the operation
- `taxonomy-app/…/workspace/controller/WorkspaceController.java` — REST endpoint
- `taxonomy-app/…/workspace/service/WorkspaceResolver.java` — if the operation needs
  to resolve a workspace by name or context
- `taxonomy-app/…/workspace/service/WorkspaceProjectionService.java` — if the operation
  produces a new projected view
- `taxonomy-domain/…/dto/WorkspaceInfo.java` — if the operation returns new workspace metadata
- `taxonomy-app/…/versioning/service/VersioningFacade.java` — if the operation involves
  DSL content or Git operations (branching, merging, cherry-picking)
- `taxonomy-app/…/versioning/service/SelectiveTransferService.java` — for cherry-pick style transfers between workspaces

---

## Files usually not touched

- `taxonomy-domain/…/model/RelationType.java` — relation types are independent of workspace operations
- `taxonomy-dsl/` — DSL parser/serializer are independent; touch only if the operation
  reads or writes DSL content directly
- `taxonomy-export/` — export is independent of workspace operations
- `taxonomy-app/…/analysis/service/` — LLM analysis is independent of workspace management
- `taxonomy-app/…/catalog/service/TaxonomyService.java` — taxonomy nodes are read-only;
  workspace operations do not modify the taxonomy catalog

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `GET /api/workspace` | `WorkspaceController` |
| `POST /api/workspace` | `WorkspaceController` |
| `DELETE /api/workspace/{name}` | `WorkspaceController` |
| `POST /api/workspace/{name}/operation` | `WorkspaceController` (new pattern) |

New workspace operations typically follow the pattern
`POST /api/workspace/{name}/<operation-name>`.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/workspace.js` — workspace panel logic;
  add button handler and API call
- `taxonomy-app/src/main/resources/templates/index.html` — workspace panel section;
  add the button or menu item
- i18n: add labels to both `messages.properties` and `messages_de.properties`

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
# App module unit tests
mvn test -pl taxonomy-app

# Full verify if you changed the UI or application configuration
mvn verify -DexcludedGroups="real-llm"
```

Relevant test classes:
- `WorkspaceControllerTest` — endpoint status codes, response shapes
- `DslApiControllerTest` — if the operation involves DSL commits
- `ConflictDetectionServiceTest` — if the operation involves merging or transferring changes

---

## Documentation / screenshot updates

- `docs/en/WORKSPACE_VERSIONING.md` — add the new operation to the workspace operations section
- `docs/en/USER_GUIDE.md` — if the operation is user-facing, add a walkthrough
- Screenshots: regenerate the workspace panel screenshot if it shows the list of operations

---

## Common pitfalls

1. **Workspace-scoped vs. system-level:** Operations on a user's own workspace are
   straightforward. Operations that affect another user's workspace (e.g., admin
   workspace eviction) require `ROLE_ADMIN` and must go through `SystemRepositoryService`,
   not `WorkspaceManager`.

2. **Git state guard:** `RepositoryStateGuard` enforces that certain operations
   cannot run while the repository is in a conflicted or rebasing state.
   Wrap operations that modify Git content with the appropriate guard check.

3. **Terminology in the UI:** User-facing text must use workspace domain terms,
   not raw Git terms. See the Terminology Rules in
   [Developer Guide](../../en/DEVELOPER_GUIDE.md#terminology-rules).

4. **Idempotency:** Workspace operations should be idempotent where possible.
   For example, creating a workspace that already exists should return the existing
   workspace, not an error.

5. **Context resolver:** The `WorkspaceContextResolver` maps the authenticated
   username to the correct workspace. Always use it rather than reading the username
   from a raw request parameter.
