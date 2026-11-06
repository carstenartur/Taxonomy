# QEA writer host recovery ledger — 6 November 2026

This ledger records recovery of the already approved Task 2 host/SDK implementation after loss of the scratch workspace. It does not expand the approved feature scope.

## Current source and recovery boundaries

The recovered writer worktree starts at reader commit `d46a0244d588c23602066388719bbdcab01dfc11`. The parent maintains the remote branch checkpoint and owns commits, integration and merge. No child-agent commits or pushes are made.

The earlier test evidence was lost with the workspace. It is not used as verification of this recovered source.

### Replayed from complete retained code or patch contents

* `IntegrationFileBinding`, `IntegrationBackupFiles`, PostgreSQL migration V46.
* All additive `IntegrationStore` sections, including scoped BASE/RESULT retention, operation descriptors, binary validation, legacy JSON fallback and the final structural plugin-binding comparison.
* All ten `IntegrationBasedFileExportTest` cases and their generic non-vendor fixture.
* Controller multipart route and migration assertions; explicit test entity registration and relational backup-selection changes.
* The reviewed-file delivery section and identity/root helper logic of `IntegrationService`.

### Reconstructed against the pinned contract and baseline architecture

* SDK records/enums/default methods and three contract tests; host SPI version 1.4.0.
* Missing service wiring and initial based-preview guards; the rest of that preview is retained original code.
* Domain adapter policy hooks and the package synchronizer's additive policy overload. The separately verified reader ordering patch was subsequently applied cleanly, preserving that overload.
* Binary entity annotations, leased callback integration/test, backup-contributor wiring and five binary-backup cases.

These reconstructed portions are not claimed byte-identical to the lost files. They retain the approved API shapes and must pass fresh tests and independent review.

## Frozen contract

* Capability `BASED_FILE_EXPORT_V1`; modes `NEW_PROJECT` / `UPDATE_COPY`; `UUID_BRACED_UPPER` host identity policy.
* `FileExportProfile(modes, identityFormat, elementTypes, relationTypes, requirementType, packageType, viewType)`.
* `BasedOutboundRequest(outbound, mode, identityNamespace, baseSha256, baseMediaType, baseContent)`.
* Existing legacy connector signatures remain; new methods have default unsupported implementations.
* Multipart POST to the existing export-preview route receives JSON `BasedExportRequest(operationId, expected, expectedExternalVersion, mode, mediaType, baseFileId)` and exactly one uploaded file or retained basis handle.
* Overview exposes `fileBases` and a direct `fileExportModes` array of currently permitted enum strings.
* Operation exposes `fileExport`: mode, namespace, basis ID/SHA/MIME/length/model identifier, and nullable output ID/SHA/length.
* Existing reviewed-file and download routes remain. Legacy JSON export remains for legacy profiles; based profiles require an explicit basis and return controlled 428 otherwise.

## Authority and persistence boundaries

The connection UUID fixes model/root/object UUIDs before review. Imported identities or an import checkpoint forbid NEW_PROJECT; use UPDATE_COPY or a separate connection. Placement occurrences retain structured native identities. A root already exposed in the candidate cannot be rejected and silently recreated.

UPDATE_COPY requires the accepted import checkpoint's exact bytes digest and model identity. Rejected and unmentioned source content remains. File output never implies remote acknowledgement or advances a common synchronization checkpoint.

BASE and RESULT bytes are immutable, separately persisted and bounded to 16 MiB each. Context, actor, operation, profile, plugin artifact, digest and review bindings remain host-owned. Completed downloads do not invoke a plugin. Pending operations retain exact-artifact checks.

Portable backup stores binary entries and source binding metadata. CURRENT_STATE includes bases required by pending file previews; history includes completed outputs. The existing SOURCE_EVIDENCE_REQUIRES_TARGET_MAPPING and MANUAL_REVIEW_REQUIRED policies remain. Restore does not automatically reactivate target access, a review, a pending operation or a synchronization baseline.

## Fresh verification after recovery

* First SDK attempt was blocked by an expired proxy endpoint. It did not count as RED.
* `recovery-sdk-red-confirmed.log`: the SDK tests reached compilation and failed on the missing new types/default methods before implementation.
* `recovery-sdk-green.log`: BUILD SUCCESS and install, all 24 SDK tests pass. Source/class mtimes were captured in the same cleanup/build process.
* `recovery-host-red.log`: 70 focused cases reached execution. All ten recovered based-file flows passed; the deliberately absent callback/backup wiring produced four assertion failures and two errors. The failures were the new default callback, missing file dataset/archive entries/category, and missing binary-integrity validation.
* After that RED, the leased based-file callbacks and binary backup contributor wiring were applied.
* `recovery-host-green.log`: BUILD SUCCESS, exit 0, 264 tests passed without failures, errors or skips: mapping 23, XMI/OSLC/diagram export 104, runtime catalog 3, interoperability 134. The interoperability total includes based-file flow 10, binary backup 54, leased callbacks 6 and the separately supplied reader regression set 53.
* Relevant class outputs were removed immediately before the build in the same process. The log confirms newer class timestamps for the changed callback/backup production classes and the based-file test class.
* PostgreSQL migration, full application restart/composition and packaged-plugin acceptance remain integration gates owned by the parent. This host verification does not claim an Enterprise Architect product-open test.

For reliable verification in this environment, generated output cleanup, Maven and class-mtime evidence run in one process. Prior cross-tool deletion of ignored build output was observed to be restored at the tool boundary.
