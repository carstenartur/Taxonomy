# Verified legacy owner mapping (implementation checkpoint)

This is a controlled-identity **implementation fixture**, not approval to change a real
installation. Existing Keycloak installations stay in `LEGACY` until their owner mappings
have been independently established and accepted by the operator (EXT-02).

`VerifiedLegacyOwnerMigration` is an internal, server-owned service, **not** an HTTP
self-service endpoint. A currently active stable administrator invokes:

1. `preview(authentication, limit)` (limit 1–256) to obtain a bounded ordered set
   of historical disabled scopes, the full unresolved count and a revision fingerprint.
   A truncated preview is diagnostic only; it is not a complete migration plan.
2. Verify **every** historical owner against the target provider's immutable issuer and
   subject, using an independent privileged source. Retain the approval records outside
   application logs. Never match by display name, e-mail or `preferred_username`.
3. Supply a full list of `VerifiedMapping` entries (including the reserved principal
   IDs and exact scopes) and the preview fingerprint to `linkVerified`. The service
   rejects partial, stale, duplicate or conflicting mappings; up to 4,096 unresolved
   identities are supported per all-or-nothing batch. A larger migration needs a
   separately designed staged, restartable implementation, not blind partial activation.
4. Check the durable identity and access behavior on the *actual* supported database,
   across deployments and after restart, before authorizing an installation-wide mode
   switch. The tests in this change use HSQLDB controlled identities only.

The linking transaction keeps the existing scope, enables its reserved stable principal,
adds the exact OIDC issuer/subject binding and writes a stable-actor audit record. The
approval reference is stored only as a SHA-256 digest. No backup capability is granted.
`unlinkVerified` disables the exact historical principal and binding but retains the
binding tombstone; a recycled subject cannot silently take over the abandoned scope.

Limitations: this first implementation uses the historical reservations created by the
V3 principal migration. It does **not** yet inventory ownership inserted later in LEGACY
mode, expose an authorized operator workflow, gate the STABLE mode startup, migrate all
remaining username-keyed tables, or prove queued-worker revocation. Do not treat
`preview.total == 0` as proof of installation migration readiness. These are outstanding
parts of #785. Actual owner approval and production rollout remain EXT-02.

## Post-V3 ownership drift and startup fence

The read-only `LegacyOwnerMigrationReadiness` inventories the same explicit ownership
columns as V3 (including user-scoped system repositories). It compares currently
persisted owner scopes against the stable principal and binding registry, counts
late LEGACY writes with **no V3 reservation**, unresolved disabled reservations
and owner rows lacking an active local, verified historical OIDC, or generated STABLE
binding. It does not expose owner names on the startup failure path. The inventory is
bounded to 100,000 distinct scopes and fails closed above that limit or if the
identity schema is unavailable.

`linkVerified` now rejects an otherwise complete approved mapping plan if post-V3
LEGACY ownership has not been reserved. In the `keycloak` profile, a configured
`principal-mode=STABLE` checks the inventory after identity-schema initialization
and rejects startup while it is incomplete. Fresh installations without historical
ownership remain able to opt in. `LEGACY` is unchanged and never silently promoted.

**Important:** This is a necessary startup fence, **not** a proof of safe rollout.
It does not migrate later unreserved scopes, coordinate concurrently running LEGACY
instances, classify every identity-bearing writer, reauthorize queued workers,
verify all database dialects or replace the installation-specific issuer/subject
approvals in EXT-02. Stop all LEGACY writers, inventory and migrate the real
installation, verify the full username-key audit and all retained actors, and
collect restart/multi-instance evidence before any authorized mode switch.
Issue #785 remains open.
