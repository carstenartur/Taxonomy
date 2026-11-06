# Shared architecture editing implementation plan

**Goal:** Let authorized repository contributors opt into the same existing editor
working copy while preserving private isolation and durable personal undo.

**Spec:** `docs/superpowers/specs/2026-11-06-shared-architecture-editing-design.md`

**Architecture:** Persist one private-by-default workspace flag. Reuse repository
membership, request-pinned WORKSPACE contexts, the JPA editor journal and JGit.
Extend the current editor controls and conflict/repreview workflow.

## Tasks

- [x] Add failing workspace authority tests for opt-in, explicit CONTRIBUTOR
  membership, primary compatibility exclusion, revocation and private isolation.
- [x] Add the flag and centralized access decision; resolve shared contexts only
  from an explicit workspace pin. Retain owner-only sharing administration.
- [x] Add failing two-actor journal tests for stale revisions, personal undo,
  independent edits and durable restart.
- [x] Expose sharing controls and DE/EN permission notice in the current editor;
  show all actors' history with personal inverse actions. Retain frozen intent
  and deleted-target identity on refresh/repreview.
- [x] Add PostgreSQL V47, HSQL restore V17 and backup/restore retention, including old archive
  compatibility. Coordinate vendor restore schemas with their existing owner.
- [x] Prevent stale detached workspace metadata merges from reversing owner sharing
  decisions, and retain registered former journal actors in scoped history archives.
- [x] Run focused Maven-owned tests and browser regressions, record exact results,
  review the complete diff and commit locally for parent integration.

## Review focus

Public or primary repositories must not bypass explicit collaboration membership.
Revocation and disabled sharing must reject a previously joined collaborator.
Old contexts must fail closed when the sharing mode changes.
Concurrent deletion must preserve the draft's target identity.
Backup import must not accidentally enable collaboration for an old archive.
