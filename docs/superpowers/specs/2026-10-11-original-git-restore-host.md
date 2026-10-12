# Original Git restore host and retry boundary

This continues the authorized #1146 restore implementation. The native archive adapter is committed as e282e16. No installation activation, source grant reuse, or complete restore support is claimed by this host alone.

## Invariants

The host accepts only a fully verified history archive and the exact explicit allocation map bound to the isolated target generation. It prepares every native source before allocating target Git. Aggregate expanded object bytes across repositories share one bound. Archive paths never determine physical target names.

The staged V6 owner ledger binds the archive SHA-256, target generation, complete allocation digest, operator principal, original input digest and stable per-repository operation UUID. All PREPARED rows commit before the public library factory may allocate a repository. Existing Git without these durable rows is rejected through the public existing-only library facade. A conflicting or partially missing owner ledger fails closed.

Each library connection comes from a fresh Core-only validated factory. A host DataSource checks the actual borrowed connection against the target and the current active destination. The same check runs before statement execution and commit, including setAutoCommit(true). This covers mutable routing after the initial generation check. No library implementation classes, reflected library fields, copied library DDL, or ORM schema creation are used.

Standard JDBC result sets, including metadata result sets, preserve the same guarded statement and connection back-references. Neither unwrap nor a result-set back-reference may return the raw connection. Cursor fetches and updatable result-set mutations recheck the same current authority and physical destination.

Canonical completed proofs sort all maps and commit sets. Each proof is bounded to 16 MiB and their shared prepared budget is 64 MiB; expanded original Git object bytes share the configured bound across the entire repository allocation list. Future proof bounds are checked during read-only preparation, before a durable allocation is claimed.

Original object and ref writes use public Git/library APIs. They can commit independently. After exact target readback, original-event provenance and the completed repository proof commit in one owner transaction. An interrupted PREPARED import may resume compatible partial Git with the same operation ID. A COMPLETE retry performs read-only object/ref/provenance verification and must reject deleted or changed data; it cannot silently repair previously completed evidence.

The real importing principal owns the new live audit event. Original names, dates, messages, ordinals and prior origins remain separately retained primary evidence. Editor working-state readback belongs to the Workspace SQL owner after Git import; this host never fabricates it.

## Required evidence

Use real ZIP64 reader verification, original native Git payloads, versioned isolated HSQL and the public library factory. Verify original IDs and aliases, inert source provenance, restart after a real committed completion with lost acknowledgement, cancellation after actual object publication, rejection of existing unapproved Git, complete-owner tamper rejection, and actual late Active-to-Staging routing before statement execution. Keep initial stub failures separate from fixture failures. Follow with an unchanged-source ordinary reactor gate and current-head canonical CI; supplemental owner tests do not replace either.

## Component verification checkpoint

The original five behavioral cases failed against the empty host and passed against the durable implementation using actual native archives, migrated HSQL and the public storage library. Independent review then identified a real result-set connection escape: the added test executed a write after active/staged aliasing, producing one genuine failure among eight cases. The common result-set wrapper closes that path. The same eight cases now pass with no skips, including actual revocation and actor change after the PREPARED commit (both permit zero Git object writes until fresh authorization), and resumption after the authority is restored. The verified source/resource digest is ede54e07a95dd0cc8ef8f0f345298c68fe36a0fe376203d20379ef5cfc6fc672. This is a bounded Java 21 component checkpoint; ordinary Maven composition, Workspace editor round-trip and installation readiness remain separate gates.
