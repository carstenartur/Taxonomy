# Native cold installation recovery rehearsal

The approved scope is the documented stopped-writer, file-backed HSQL deployment.
Portable owner import and its closed support registry remain unchanged.

1. Add behavior-first tests for a bounded native snapshot helper in the existing
   tooling module: complete byte inventory, isolated new target, version binding,
   tampering/missing/extra files, symlinks, active HSQL lock and interrupted copy.
2. Extend the existing tooling entry point with capture/verify/restore operations;
   require explicit stopped-writer acknowledgement and keep publication separate
   from staging. A digest is an integrity receipt, not archive authentication.
3. Add one ordinary Maven-owned application recovery acceptance using separate
   full application JVMs. Seed actual portfolio/version/snapshot and curated
   product decisions, retained source bytes, SQL-backed Git and editor journal,
   preferences and templates. Capture only after graceful process termination.
4. Restore into a new isolated directory, verify through actual services, mutate
   the target with undo, restart for redo, and compare the untouched source tree.
   Use disabled remote providers and no pending external work.
5. Retain bounded machine-readable evidence under target; document exact scope,
   operator commands and remaining portable/cross-database/desktop limits in EN/DE.
6. Run focused Maven tests and available broader verification; report commands,
   counts and missing prerequisites honestly. Local commit only; controller owns
   independent review and publication.
