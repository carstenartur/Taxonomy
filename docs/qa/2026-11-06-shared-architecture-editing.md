# Shared architecture editing verification

The private-by-default editor has an owner-managed sharing opt-in. Joining requires
an exact workspace link, an active stable principal and explicit source-repository
CONTRIBUTOR authority. Public/primary compatibility access is insufficient. The
existing journal retains both actors’ edits, revision conflicts and personal undo.

## Verified source

The shared changes compose writer `055e83f986a7083f74aac7dcb6dd16e63c875171`,
including release repair `16415195fdf3117ade536eca27646fef76d24337`. PostgreSQL
V47 and HSQL restore V17 preserve the peer’s V46/V16 and exact version inventories.
Frozen V1, bounded binary reads, frozen diagram-ID checks and SDK contracts remain
unchanged. Ordinary metadata merges cannot overwrite the sharing flag; its locked
owner operation owns updates. History capture retains registered former actors
without replaying grants. Unmapped legacy labels retain private quarantine.

## Evidence and scope

| Gate | Result | Scope |
| --- | --- | --- |
| Composed source before redundant-test cleanup | 176 passed | App 104, interop backup 57, extension contracts 6, workspace JPA 5, release verification 4. |
| Final cleanup candidate | 33 passed | Peer SPI contracts 6, workspace JPA 5, app schema/restore/version contracts 22. |
| Architecture browser owner | 19 passed | Native Chrome 149 executes EN/DE editor controls, join links and retained deleted-target drafts against the existing HTTP fixture. |
| Workspace MVC boundary owner | 15 passed | Real application/JPA/JGit authority, persisted identities, disabled old principals, membership revocation and exact workspace routing. |

Both completed Maven gates reported `BUILD SUCCESS`, zero failures/errors/skips.
The browser and MVC production sources did not change in subsequent composition
or cleanup; those named reports remain applicable. Additional focused journal,
restart, lifecycle and source-draft contracts passed. Existing owner metadata/list
behavior is preserved; shared workspace resolution/edit/history/export authority
is revalidated.

The cleanup removes an older duplicate SDK test class and schema smoke method.
Peer contracts provide broader field-order/immutability coverage; peer schema
checks cover V16 upgrade preservation, BLOB storage and model registration. The
interop backup owner verifies binary roundtrips. The schema test now differs from
writer `055e83f` only in expecting 17 applied business migrations.

## Reproduction and limits

Use Java 25 and the configured Mockito agent. The final lightweight gate is:

```sh
./mvnw -B -pl taxonomy-app -am test -Ptest-local -Dmaven.build.cache.enabled=false \
  -Dtest=BasedFileExportContractsTest,RestoreSchemaMigrationTest,WorkspaceRestoreImporterTest,WorkspaceCollaborationServiceTest,TaxonomyMigrationVersionContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Native browser evidence uses matching headless shell/chromedriver 149.0.7827.55.
The focused HSQL and browser owners do not replace canonical CI, PostgreSQL
migration/validation, packaged runtime acceptance or SQL Server/Oracle native
recovery. Those gates remain parent-coordinated. Independent source review cleared
the authority, sharing lost-update, historical actor fixes and final cleanup, with
no remaining blockers. The peer coverage remains authoritative.
