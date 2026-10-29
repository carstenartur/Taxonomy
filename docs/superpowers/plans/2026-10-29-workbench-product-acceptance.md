# Workbench Archi product acceptance

The current installed-product lane checks `archimate-3.1/1`, while the workbench
emits `taxonomy-archimate-3.1-v2`. Passing the first cannot establish acceptance
of the second. Extend the existing Maven-owned product lane; do not add a runner,
workflow, selector or duplicate codec.

1. Make outcomes identify product and exact profile. Require StrictDoc ReqIF,
   Archi integration exchange, and Archi workbench exchange independently.
2. Generate the actual workbench ZIP with `ArchitectureSnapshotExportService`,
   retain its manifest, and hand its exact `model.archimate.xml` bytes to Archi.
3. Compare original IDs, element/relation semantics, retained properties, source
   provenance, view membership and geometry after import/export. Any accepted
   transformation must be explicit and recorded; unknown changes fail.
4. Test missing/duplicate/wrong-profile evidence and corrupted returned models
   through the existing Maven unit owners before the installed-product run.
5. Execute the pinned installed product using the same commit-bound application
   artifact as the existing lane. Preserve the version, profile, hashes and
   transformation report. Keep independent-consumer/Visio acceptance pending.

No global interoperability certification or full database/authorization test is
implied by the bounded deterministic export fixture.
