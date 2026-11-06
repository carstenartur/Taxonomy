# Shared architecture editing

The user authorized completing the previously open simultaneous editing workflow.
This extends ADR 0005's private editor without changing its durable semantic journal,
explicit Git checkpoints, source-preserving commands or actor-owned undo/redo.

An existing working copy remains private by default. Its owner can explicitly enable
shared editing in the existing architecture editor. A notice states that active
source-repository members with CONTRIBUTOR or greater permission gain access to the
draft and operation history. The exact repository/workspace/branch link joins that
same working copy; joining never changes another tab's selected workspace. Public
visibility and the primary repository's legacy contribution exception grant no access.
Only the owner controls sharing and the existing workspace administration.

Authorization rechecks the persisted opt-in, active workspace/repository and explicit
membership on every request. Disabling sharing, archiving or membership revocation
blocks collaborators while retaining their accepted edits. Shared access and sharing
controls reuse the application's verified active-principal authority, including old
sessions after account/binding disablement. Local logins already have stable
principals; legacy unmapped SSO sessions retain private behavior and cannot opt
into or join shared editing until explicitly mapped to stable identity. The
workspace module consumes an authority port implemented in app composition for
active shared workspace resolution, default selection, edits, history, exports
and personal draft/source reads. Existing owner metadata/list behavior is retained.
Only the locked owner sharing operation updates the persisted flag; ordinary
metadata merges cannot replay an older sharing decision. The flag survives restart
and backup/restore, with old archives defaulting to private. History exports retain
registered former actors as identity evidence after membership removal or account
disablement. Restore still requires explicit identity mapping and does not replay
memberships, logins or grants. Unknown legacy actors retain private quarantine.

Both actors edit the same durable revision stream. Competing revisions return 412;
the person refreshes and explicitly previews the frozen intent again with a fresh
command identity. Unaccepted form input survives this refresh. A concurrently deleted
target must never become a new element implicitly. Undo/redo append personal inverse
operations, preserve unrelated later edits and reject intersecting later changes.
The history identifies all actors but offers inverse controls only for personal edits.

No live cursors, CRDT, shared undo, new frontend, event platform or per-edit Git commits
are introduced. Existing explicit refresh and exact-context reads remain the update
mechanism. Requirements, immutable versions and project-independent architectures
retain their existing identities and lifecycle.

Verification uses the existing Maven/JUnit workspace and native browser owners for
private isolation, explicit membership, revocation, two actors/revisions, personal
undo, retained drafts, restart and backup round trips. Full CI and external database
acceptance remain separate parent-coordinated gates.
