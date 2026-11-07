# Shared architecture editing verification

Sharing remains an owner-managed opt-in on the existing private editor. Shared requests
require an active stable principal and ACTIVE source; joining collaborators also
require explicit source-repository CONTRIBUTOR authority. Every collaborative analysis-draft operation, including an
owner request identified only by its URL path, rejects an archived source. Private
owner behavior is preserved. Revocation applies to new requests; already authorized
requests are not cancelled by a later sharing change.

## Atomic control boundary

Sharing control belongs to `com.taxonomy.editor` in the existing workspace Maven
module, preventing the former workspace-to-editor backedge. A scalar ownership,
branch and readiness preflight avoids caching workspace metadata before locking.
The journal control transaction then owns the journal row before locking metadata,
checks the revision, pending checkpoint and fresh Git HEAD, and updates the flag.
Both locks survive until the caller commits or rolls back.

HSQL MVCC does not make a locking SELECT exclusive. The control boundary therefore
uses the existing backup-fence pattern: a no-op UPDATE of `rowVersion`, followed by
entity refresh. Initial rows are inserted and flushed in the caller transaction.
Neither row nor semantic versions advance; no operation or Git commit is invented.
Ordinary journal/integration paths and next-request authority remain unchanged.

## Evidence

| Owner | Result | Scope |
| --- | --- | --- |
| Clean workspace owners | 56 passed | Editor 40, sharing 14, four-process restart owner 2. |
| Completed app guard gate | 99 passed | Draft service 20, draft MVC 14, workspace MVC 20, lifecycle 9, dependency ratchet 34, cycle rule 1, generated OpenAPI 1. |
| Earlier composed source | 176 passed | Schema, backup, SDK, sharing JPA and release contracts on writer `055e83f`; subsequent duplicate-test cleanup passed 33. |
| Earlier browser owner | 19 passed | Native Chrome executes unchanged EN/DE editor controls and retained-draft behavior through its HTTP fixture. |

The new guard regressions failed before correction. Commit-level concurrency cases
cover edits/checkpoint reservations in both orders, caller commit/rollback, first-row
commit/rollback and unchanged versions. Real MVC tests cover all five unpinned owner
draft operations after archival. The multi-request session fixture uses per-request
transactions, retains all authority assertions and cleans only its owned storage.
The completed app gate reported `BUILD SUCCESS`, zero failures/errors/skips.

The tested snapshot is base `5ef4c30b38f1ee03f0a145e976f440dcccdb73b4` plus these
reviewed changes. A fresh detached `/tmp` checkout avoids known executor replay of
obsolete untracked source; intended Java/test/config bytes were compared directly.
The dependency baseline adds only the immutable lifecycle enum reference (2 to 3).
No cycle exceptions or test assertions were relaxed. Independent review cleared
physical locking, request authority, editor ownership, Swagger composition and fixtures.

## Reproduction and limits

With Java 25 and the configured Mockito agent, use the Maven reactor:

```sh
./mvnw -B -pl taxonomy-app -am test -Ptest-local -Dmaven.build.cache.enabled=false \
  -Dtest=WorkspaceCollaborationServiceTest,ArchitectureEditorServiceTest,ArchitectureEditorRestartTest,AnalysisWorkingDraftServiceTest,AnalysisWorkingDraftMvcContractTest,WorkspaceProvisioningBoundaryIT,ArchitectureLifecycleServiceTest,ArchitectureContextDependencyRatchetTest,ArchitectureCycleBoundaryTest,OpenApiGeneratedContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

These focused HSQL/JPA/MVC and contract owners do not replace canonical CI,
PostgreSQL/SQL Server/Oracle locking, migration and recovery acceptance, packaged
runtime checks or commit-bound screenshot production. Those gates remain
parent-coordinated; the guard changes do not modify visual assets.
