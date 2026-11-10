# QEA operation evidence (contract 1)

The `sparx-qea@2:operations/1` support contract describes the existing bounded
source-copy writer. Every request retains the exact reviewed basis SHA-256;
UPDATE_COPY cannot be applied to changed basis bytes. The corpus is authored SQL
and is not evidence of opening or accepting the output in licensed Enterprise
Architect. It distinguishes native semantic GUIDs from diagram instance IDs.

| Operation | Declared effect | Boundary / regression |
|---|---|---|
| Rename | Faithful selected title update; semantic GUID unchanged | SQL asserts native Object_ID and ea_guid |
| Move package | Faithful selected package ancestry update | SQL asserts Parent_ID and the package mirror owner; native IDs, geometry and semantic GUIDs preserved. Existing tests also cover moving an element to another package |
| Repeat occurrence | Faithful additional membership of the same semantic object | Distinct native Instance_ID required; existing occurrences unchanged; new geometry may use a reported deterministic grid |
| Remove occurrence | Unsupported explicit `operation=delete` | Fails QEA_OPERATION_UNSUPPORTED without mutating basis; omitting a selection is not deletion |
| Move existing geometry | Unsupported | Fails QEA_LAYOUT_UNSUPPORTED; source-copy layout remains unchanged |
| Change endpoint | Faithful with explicit supported Taxonomy meaning/direction | Connector GUID unchanged; SQL checks both endpoint IDs; preserve-only endpoint edits fail QEA_RELATION_MAPPING_REQUIRED |
| Concurrent local edit | Unsupported stale basis | Fails QEA_BASIS_CHANGED for changed bytes and the prior frozen hash; this is not a live external EA revision monitor |
| Connector visibility/routing | Retained per diagram in source evidence and source-copy roundtrip | `ea:t_diagramlinks:<native-instance-or-connector-key>:connectorGuid` references the semantic connector. Native Hidden, Geometry, Style, Path and other non-null scalars remain separately retained. Edited evidence fails QEA_DIAGRAM_LINK_UNSUPPORTED |

Unresolved diagram link references receive QEA_UNRESOLVED_REFERENCE losses.
Repeated links without distinguishable native identities fail explicitly. Absent
native Hidden is unknown, never an inferred visible state. Link routing is retained
as evidence and is not interpreted as canonical geometry.

## External ADMBw profile

`AdmbwSourceProfile.CURRENT` in `taxonomy-sparx-mapping` is the immutable shared
source descriptor consumed by DSL assessment and the independent native plugin.
It owns the supported C1-S1/R7 subset, nine element base types, four connector
base types and bounded endpoint rules, and two qualified diagram types. The
assessment includes the descriptor; native generated diagram notes include the
source URL, source MDG SHA-256, generator version and actual basis SHA-256.

Original upstream: EAM-Toolfamilie revision
`6b1baa03bb097d3aa9afc8bde4d56e275e8a0e93`, MDG
`packages/rules/NAFv4-ADMBw-MDG-2026.05.xml`, SHA-256
`e2e8c69281dcc83ad4382bd1b196c23850a847defb33d0a0ffcb429d747f3643`.
The exact Windows-1252 source bytes are gzip-compressed with their upstream MIT
license as a test resource. Offline regression verifies their fingerprint, native
base types and qualified diagrams; modified source bytes fail fingerprint checking.

The same revision's reference
`packages/core/fixtures/Musterarchitektur_MASC.qea` was retrieved and fingerprinted
as `d64506532debac09db8ada832b66f2798d28d6e77eb8e2b441d1883b7850d009`.
Only this provenance is recorded; the binary is not bundled. The source MDG states
profile version 2026.05 and does not identify its generator version. The descriptor
records that absence explicitly. `taxonomy-admbw-qea/1` identifies our native
writer's generation contract, not an invented upstream tool version.
