# Production repository-history capture

## Goal and authority

Continue issue #1146 and the authorized completion plan. The production job path
already captures the three current/selected profiles. Connect the reviewed
original-history owner to the same installation fence, authorization, inventory,
job, archive writer and download path for `REPOSITORY_HISTORY`.

This is a checkpoint on the way to full installation recovery. `INSTALLATION_FULL`
continues to require the separate template/preferences Git inventory and private
restore-evidence owner before admission. Do not label a repository capture a full
installation backup or infer extra repositories from names, UUIDs or commit IDs.

## Composition

Keep the single production `BackupCaptureSource`. For current/selected profiles,
continue to use `GitStandBackupContributor` and its existing projection. For the
history profile select `GitHistoryBackupContributor`,
`WorkspaceGitHistoryReferences`, and the same existing-only repository facade.
Both paths expose their exact inspected manifest repositories and document
references through a small application-owned composition boundary. No feature
module depends on application composition and no private JGit storage API is used.

History admits BUNDLE, BARE and WORKTREE with the existing request/time/scope
checks and `EXPORT_HISTORY` capability. BUNDLE still rejects an empty Git history;
the caller can explicitly select BARE. Current/selected layout support is not
silently widened by this change. Selection size, archive/row/Git limits, leases,
source database witness and current authorization checkpoints remain unchanged.

All live refs, original ancestor IDs, deleted-ref/reflog roots, database-only
commit roots, raw saved working states and their proofs come from the existing
history owner. The owner verifies its inspected closure again during capture.
No synthetic commit is substituted into original history. External servers and
target credentials remain explicitly outside the captured local installation.

## Document reference closure

Knowledge selection must include references from every original `.taxdsl` in the
captured commit closure and from retained workspace working states, operation
before/after bodies and checkpoint bodies. Portfolio/analysis metadata continues
through the existing bounded owner projections for the selected profile. Inline
source/fragment/requirement links resolve against the exact document being read;
foreign or missing required references fail capture rather than borrowing a newer
document or silently dropping metadata.

Extend the public history owner with a bounded synchronous document visitor.
The visitor supplies an exact `BackupDocumentReference` and one bounded original
UTF8 byte body while its existing capture session is alive. Each body is checked
against its captured commit/path/type/length/SHA256 proof. Per-document maximum is
the existing portable-record bound. Count and total visited-byte bounds apply
across all explicitly selected repositories. No concatenation of all bodies and
no raw repository handle escapes. The database/source inventory is checked before
and after the pass; do not reopen and reinventory the complete source for each
individual document. Check cancellation throughout reads and between callbacks.

Parse each body independently, retain only bounded code/hypothesis identities,
and discard its body/AST before the next callback. Workspace DSL bodies use the
same bounded projected-record scanner, so history preserves their raw content
while current profiles retain their existing privacy projection. Do not parse
display labels, author names or unrelated text as current principal identities.

`ArchitectureBackupContributor` receives the chosen Git owner's exact document
references. Its existing scoped content proof and declared global-legacy omissions
remain authoritative. Templates/preferences remain outside repository scope.

## Required evidence

1. Actual admission test fails on the current production source for all three
   history layouts, then passes through the chosen history path. Current/selected
   unsupported combinations and full-installation prerequisites remain explicit.
2. Original document visitor covers ancestor and database-only commits, binary
   UTF8 content, shared count/byte bounds, cancellation and source movement. Its
   callback never receives bytes outside the exact inspected closure.
3. Actual Spring application job captures history, writes a verified ZIP and
   permits only the authorized native download. Native Git clone/fsck/show
   preserves original SHA1 IDs and saved working bytes in all three layouts.
4. A reference present only in an ancestor or retained editor body is included;
   a foreign repository, foreign source bytes and out-of-scope administrative data
   are absent. Missing required source bytes make the job fail without download.
5. Re-run the existing current/selected production behavior and relevant owner
   tests. Record exact source hashes, actual exits and immutable report paths.
   Canonical CI remains required; no local Chrome retry or lowered verification.

## Follow-on checkpoints

Full installation history additionally needs explicit ancillary repository kinds,
the actual template/preferences owners, protected secret handling and inherited
private evidence. The durable restore/import/activation/contribution workflow and
directed database/operational matrix remain in the authorized issue scope.
