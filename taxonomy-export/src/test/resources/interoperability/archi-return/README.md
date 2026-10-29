# Installed Archi return regression pair

Verbatim workbench export and Archi 5.10.0 returned XML from CI run 40861589895,
artifact 12891409813. Source commit: `730a22cb5eefbdd8a88d4cf67a67f7b0ffe23356`.

- Artifact archive SHA-256: `6c36e887907bc6691ca4bdefc0cbecae350d45e75dbd4b9da0b729387fa7818d`
- Original XML SHA-256: `79071f0e3f9539da70a9fc0e186224921249c2ca3a362a89367caf84645004d8`
- Returned XML SHA-256: `9f47f84201c342a489d2cd76bd3f11c7db81dd90bc772e7168889e3cfa5ff169`

The actual `ArchitectureSnapshotExportService` produced an authored frozen
projection, then pinned Archi imported/exported it. This is prior installed-product
evidence, not a fresh execution of the changed return importer, live analysis or
human acceptance. The original product comparator recorded 204 transformations.
The new adapter additionally reports declaration-ID rewrites and explicit Diagram
types and retains the original export losses.
