# Native PostgreSQL recovery implementation boundary

This is the bounded implementation of product-completion Task 5 under the
existing authorized recovery work. The product-completion spec remains binding.

Ruling: Do not enable a portable owner matrix: inspection found 80 portable-primary
categories and no complete all-owner orchestration or PostgreSQL target adapter.
Implement trusted native same-version recovery as the separable practical path.
If treated as portable import, persisted application grants/jobs would retain
unsafe authority; documentation and the closed registry must make that distinction.

1. Add native file dry-run and exact idempotent publication retry; exercise
   altered/missing/extra content and unchanged source behavior.
2. Bind a PostgreSQL custom-dump bundle to the existing native manifest format;
   reject raw clusters, missing/plain-SQL payloads and incompatible versions.
3. Add bounded pg_dump/pg_restore/psql execution under the existing tooling CLI,
   external libpq credentials, witnessed source/target identity and empty target
   preflight. Capture must never write the source root.
4. Restore SQL and a manifest-bound receipt in one transaction, then publish files.
   Reject nonempty/replayed databases. If database commit succeeds before file
   publication, retain an attributable inactive target and require explicit receipt
   inspection before file-only resume; never replay SQL blindly.
5. Reuse the complete native application oracle for PostgreSQL 16 with source and
   target databases, persisted business/Git content, undo, restart and redo. The
   existing PostgreSQL Maven owner selects the IT; local provisioned PostgreSQL
   may run identical assertions without Docker.
6. Retain bounded results under target, document native ownership/ACL/secret
   semantics, exact missing portable owners and Rancher deployment prerequisites.
   Run helper tests and the application rehearsals; parent owns independent review,
   integrated CI and publication.

## Follow-up to independent review of 225aa4c

1. Reproduce large-object-only and public-collation-only target acceptance on
   PostgreSQL 16, exercising both plan and restore and preserving existing bytes.
   Extend the shared preflight/transaction predicate for those catalogs. Document
   the exact enumerated guard and the fresh, unmodified template0 prerequisite;
   do not claim exhaustive equality with every PostgreSQL catalog setting.
2. Reproduce the client output ownership boundary. Pre-create dump and SQL files
   on the host with CREATE_NEW and POSIX 0600 before invoking PostgreSQL clients,
   preserving host ownership when container clients run as root. Verify both
   output paths and a real nonroot host/root client boundary without broadening
   permissions. The Docker execution lane remains an integrated CI gate.
3. Re-run the focused native helper/application and documentation tests, retain
   RED/GREEN evidence and commit a separate review correction for parent review.
