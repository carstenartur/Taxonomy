# Reviewed Sparx QEA file export

## Approved objective

Extend the optional Sparx integration with reviewed QEA file writing and merge the verified implementation. Keep the existing integration page, authorization, review, history, identity mappings and download lifecycle. This specification records the already approved scope; its restoration after the execution-workspace outage does not introduce another approval step.

## Version and transport boundaries

The independently installable plugin retains the immutable, read-only `sparx-qea@1` profile. `sparx-qea@2` adds `FILE_EXPORT` and `BASED_FILE_EXPORT_V1`. The public SDK contract is additive and advances the host SPI to 1.4.0. Older connectors keep working through default methods.

The file writer creates a downloadable SQLite/QEA artifact. It does not modify a user's original file or contact a Sparx server. The existing PCS/OSLC production interface remains read-only. File delivery is neither a remote publication acknowledgment nor permission to advance a server synchronization checkpoint. Conditional remote writes, concurrency protection and recovery on an actual PCS installation require separate verified capabilities.

## File modes and identities

`NEW_PROJECT` requires an operator-supplied, structurally empty EA QEA template. The plugin validates native model tables before use while retaining permitted template lookup/default data. No proprietary template is redistributed. Connection-scoped model, bootstrap-root and artifact UUIDs are fixed before the first review candidate; final native identifiers must match that reviewed candidate.

`UPDATE_COPY` requires the exact accepted import basis, bound by SHA-256 and model identity. It operates on a private copy. Existing GUIDs and native integer IDs remain stable. Unselected or unknown rows, tables, columns and BLOBs, unchanged tags and layouts, and opaque source evidence must survive. An incomplete exchange never infers deletions. A locally rejected change must not silently become an accepted file mutation.

SQLite schema conflict policies must not replace other rows: statements explicitly use `INSERT OR ABORT` and `UPDATE OR ABORT`. Triggers, generated columns and cascading update behavior that could violate the selected mutation boundary are rejected. Empty and NULL memo-tag representations survive unrelated edits; an explicit selected clear remains distinguishable from no change.

## Shared semantics

`taxonomy-sparx-mapping` is a JDK-only module with no production dependencies on the host, framework or plugin SDK. It owns the ten canonical element mappings and twelve explicit v3 relation transports. The existing XMI v1/v2 relation meanings remain immutable. The QEA plugin shades the shared class into its own permitted namespace.

Taxonomy `REALIZES` uses an explicitly tagged Association transport. Native QEA containment uses Aggregation with the composite end aligned to the semantic container; the canonical v3 transport remains Composition. Physical database encoding must not create a competing semantic map.

## Host lifecycle and persistence

Based export preview accepts multipart `request` JSON plus exactly one uploaded binary basis or a retained `baseFileId`. Legacy JSON-only previews retain their existing behavior; a connector that requires a basis returns a controlled precondition failure if it is omitted. Candidate, review and prepared output remain bound to the selected profile and exact plugin artifact.

Binary bases and results are stored separately from the operation JSON, with connection, context, actor, profile, plugin binding, size and SHA-256 metadata. The current 16 MiB input/output bound remains explicit. A retained result is downloadable after process restart and after plugin removal without invoking that plugin. Changed bytes or bindings must fail validation.

Portable backups contain the binary entries and provenance as source evidence. Restoration follows the existing `SOURCE_EVIDENCE_REQUIRES_TARGET_MAPPING` / `MANUAL_REVIEW_REQUIRED` contract and does not automatically reauthorize another installation's old operations. This differs from restarting the same installation, which retains its authorized journal and downloads.

## User interface

The existing integration export form exposes only the modes currently allowed by the server. It supports an uploaded basis or a retained basis descriptor, displays review-bound file context, and uses the existing review/apply/download actions. Mode, connection or basis changes invalidate the pending preview; an unchanged retry keeps its operation ID. Read-only authority, missing capability, missing basis and in-progress work disable the action. Retained descriptors have no fabricated filename.

## Acceptance boundary

Required evidence includes public SDK compatibility, source/native ordering, all native mapping encodings and preservation regressions, permissions and scope binding, binary restart/tamper behavior, backup integrity, Maven-owned UI contracts, the actual independently built plugin installed through the host, and required CI on the final commit.

Synthetic SQLite tests and an optional smoke against an official empty template do not establish EA product compatibility. Actual EA desktop / PCS acceptance remains `NOT_EXECUTED` until genuine operator evidence is available. No test or documentation may promote file-level checks to product acceptance.
