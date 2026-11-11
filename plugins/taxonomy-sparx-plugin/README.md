# Sparx QEA exchange plugin (experimental)

This independently built, operator-installed plugin contributes two versioned
profiles to Taxonomy's existing integration workflow. `sparx-qea@1` retains its
read-only contract. `sparx-qea@2` reads snapshots and writes reviewed native QEA
copies from an uploaded binary basis. Neither profile runs Enterprise Architect
or publishes to a Sparx server. The supplied database is never modified.

**EA product acceptance remains unverified.** Public tests construct synthetic
SQLite fixtures. Local schema inspection and writing against an operator-supplied
official empty template are separate evidence from opening a file in EA desktop
or accepting it through PCS. No proprietary template is bundled with this plugin.

## Build and install

Use Java 25. Install the matching public SDK and independent Sparx mapping library
from the repository first, then build this project independently:

```sh
./mvnw -pl taxonomy-extension-api,taxonomy-sparx-mapping -am install
cd plugins/taxonomy-sparx-plugin
../../mvnw verify
```

Install `target/taxonomy-sparx-plugin-1.5.4-SNAPSHOT.jar` through the host's existing
operator plugin installation flow and restart the host. The manifest declares
plugin ID `taxonomy.sparx`, mode `STARTUP`, and host SPI `>=1.5.0 & <2.0.0`.
The SDK is provided by the host and is not included in the plugin JAR. SQLite
and its native libraries are included. The JDK-only `taxonomy-sparx-mapping`
library is shaded into the plugin's own package namespace, so its element and
version-3 relation meanings stay aligned with XMI without bundling a host module.
The plugin has no dependency on the
Taxonomy application, interop implementation, Spring, Neo4j, or AAroN binaries.
Allow the driver's native library with the host JVM option
`--enable-native-access=ALL-UNNAMED` on Java 25. The standalone test JVM already
sets that option.

The preferred upload MIME type is `application/vnd.sparx.qea`; SQLite and binary
upload MIME types are also accepted. A MIME type or filename never substitutes
for SQLite header and schema validation. Upload a self-contained, closed database
snapshot: an uncheckpointed WAL file is not included by uploading only a `.qea`.

## ADMBw delivery from the architecture editor

The editor can add C1–S1 and R7 to a private copy of a closed QEA project
with the required ADMBw catalogues. It uses the same optional plugin and a
separate checkpoint-based delivery action. See the
[ADMBw profile and review steps](../../docs/features/admbw-conformance-de.md).

## Supported projection

| EA source | Exchange representation |
| --- | --- |
| `usys_system.ProjectGUID` | Stable model identifier in metadata |
| `t_package` | `SPECIFICATION`/`Package` with original GUID and hierarchy |
| `t_object` | Element or requirement with original GUID, name, notes, stereotype and scalar evidence |
| Package mirror in `t_object` | One package artifact, resolved through `PDATA1` or the package GUID; mirror GUID retained as evidence |
| `t_connector` | Original connector GUID, raw type/direction, source and target GUIDs |
| `t_objectproperties`, `t_connectortag` | Tagged values and original row evidence; duplicate names retained without overwrite |
| `t_xref` | Original cross-reference evidence and recognized qualified stereotype declarations |
| `t_diagram`, `t_diagramobjects` | View identity and membership; original geometry retained as evidence |
| Selected `ParentID`, `Classifier_guid`, `PDATA1`, `PDATA3` references | Deterministic implicit relations requiring explicit mapping or preserve-only review |

Package placements use the model GUID as their container and reference the parent
package placement. Diagram placements use a `VIEW` GUID as their container.
`placementKind` distinguishes the two in retained attributes. A diagram occurrence
does not move an element out of its package. Diagram routing, appearance and EA
layout are not imported as a native Taxonomy rendering contract.
Package positions use deterministic reader ordering; original `TPos` fields
remain source evidence and are not an EA tree-order round-trip guarantee.

Names, filenames, package paths, local integer row IDs and the file digest are not
canonical object identities. GUIDs are normalized without replacing them. Missing,
malformed or duplicate object/model identities fail visibly. Same-name elements
remain distinct. An element rename or package move retains its original GUID.
The host scopes canonical identities to the integration connection and retains
the external GUID in its existing identity mappings.

The standard `usys_system` table must contain exactly one valid `ProjectGUID`.
Databases without that identity require a documented schema extension; the reader
does not silently invent an identity from the file hash. This is a deliberate
boundary of profile 1, not a claim that every SQLite database is an EA model.

## Reviewed QEA output: profile 2

Choose the mode and upload its binary basis in the existing integration export
flow. Preview the candidate, select supported changes in the existing review and
download the retained result. The file is an exchange artifact; delivery does not
acknowledge an EA/PCS write or promote a common synchronization checkpoint.

| Mode | Required basis | Identity and preservation |
| --- | --- | --- |
| `NEW_PROJECT` | An operator-supplied empty EA QEA template with one root package and no model objects, mirrors, connectors, diagrams, tags or cross references | The host supplies the new model, root and object GUIDs before review. The existing root's integer ID is retained; reviewed top-level packages and unplaced new objects are placed under it. |
| `UPDATE_COPY` | The exact imported QEA snapshot, with its model identity and SHA-256 bound to the operation | Existing GUIDs and integer IDs remain stable. Only selected supported fields are changed on a copy. Unselected objects and occurrences are never deleted. |

Obtain an empty template from Sparx's [repository resources](https://sparxsystems.com/resources/repositories/)
or your authorized EA installation and supply it yourself. The official Schema
1558 empty QEA template examined during development has no `ProjectGUID`; profile
2 validates template emptiness separately and initializes the identity on the
output copy. Populated core/model-reference tables are rejected. Preserved lookup
and configuration rows do not establish that an arbitrarily customized repository
is a suitable empty template; use an empty project supplied by EA. The writer never
creates a guessed replacement database schema.

Supported output includes package names/notes and parent moves; element and
requirement names/notes, declared element types and tagged values; explicit
connector meanings, endpoints and direction; and view names/notes, packages and
new diagram membership. A package's existing object mirror is kept consistent
without changing its GUID. New packages get their native mirrors. Existing
diagram rectangles, style, sequence and routing remain untouched; changing native
layout is rejected. New occurrences use frozen rectangles or a simple declared
grid, with a `QEA_NEW_DIAGRAM_LAYOUT` transformation notice. Native package `TPos`
ordering is preserved and is not rewritten from derived reader positions.

The copied database preserves unknown tables, unknown columns, BLOB bytes,
unselected tagged-value rows and unrelated cross references. This is logical
content preservation: SQLite pages and the output file hash can change. No
`INSERT OR REPLACE`, schema reconstruction or inferred deletion is used. A
selected change to a binary field, duplicate tag name, foreign/mixed stereotype,
implicit reference, unsupported column or generated column fails explicitly.
Schemas with triggers or cascading foreign-key update actions are rejected before model writes. Required insert columns
need an understood value or constant native default. New nullable native
timestamps are left null so replay does not fabricate a different wall-clock
value; existing timestamps remain intact.

The semantic transport is explicit. Every newly encoded canonical element or
connector carries a qualified `Taxonomy::...` stereotype and
`taxonomy.mappingProfile=sparx-qea@2`, together with `taxonomy.elementType` or
`taxonomy.relationType`. Connectors also carry `taxonomy.relationDirection`.
The reader requires the redundant declarations to agree. `REALIZES` uses an
Association transport with its explicit Taxonomy meaning. `CONTAINS` uses EA's
native Aggregation representation, with composite flag 2 at the container end,
and reads back as the shared Composition transport. A generic UML realization or
aggregation does not acquire those Taxonomy meanings merely from its native type.

Version 2 requires the host's binary-basis export capability. Calls without an
explicit mode and exact bytes fail; a source digest or filename alone cannot
reconstruct a native file. The host retains basis and result bytes separately
from JSON, binds review to the plugin artifact and exact basis, and owns scope,
authority, restart, backup and download behavior.

## Semantics and review

The generic mappings are `Component` → `Component`, `Node` → `System`, `Activity`
→ `Process`, `Actor` → `BusinessRole`, `Dependency` → `DEPENDS_ON`, and `Association`
→ `RELATED_TO`. Unambiguous canonical or `Taxonomy::` element stereotypes on
supported UML base types can also receive a `canonicalType`. Unknown types and profile-specific stereotypes remain
in the preview with a mapping loss so a user can remap or reject them. The plugin
does not change stereotypes to make a model pass a validator.

UML `Realization`/`Realisation` does not automatically become Taxonomy `REALIZES`:
their meanings and customary direction differ. The original type and endpoints
remain available for review. Implicit references likewise retain their original
meaning; the reader does not present all AAroN graph aliases for one parent field
as independent canonical relationships.

`validateInboundSelection` validates the actual selected projection before apply.
Accepted relations need accepted endpoints and a supported explicit mapping;
preserve-only relations can retain unsupported or unresolved source evidence.
Accepted placements need their container, member and parent placement. The host
still owns authorization, endpoint-kind review, canonical validation and atomic
apply; this plugin does not grant authority from source tags.

Every result has `completeScope=false`. A bounded projection of EA tables is not
an authoritative inventory of the whole model and does not authorize deletion of
unmentioned Taxonomy objects. Unsupported tables and unprojected data are reported
as losses. Non-null scalar values from supported rows are retained in `ea:`
attributes; binary values are represented by length and SHA-256, not copied into
text evidence. Duplicate tag names retain their individual rows and do not create
an arbitrary scalar `tag:` value.

`ExchangeDocument.source` is a short digest/provenance statement, never the binary
database or base64. The input bytes are copied into a temporary, isolated SQLite
snapshot, opened read-only with extensions disabled and an untrusted-schema
policy, and removed after the read. SQL comes only from the reader's fixed table
queries, not from model data or user-entered query text. Known tables implemented
as views or virtual tables are rejected.

## Bounds

The host's 16 MiB upload limit remains the effective limit for its HTTP import
workflow. Independently, the plugin limits input and generated output to 32 MiB, supported rows to 20,000 in total, a scalar
cell to 65,536 characters, total retained text to 16 Mi characters, schema entries
to 512, columns per supported table to 128, and hierarchy depth to 128. A deadline
and SQLite progress handler bound database reads. Exceeding a limit rejects the
snapshot instead of returning a silently truncated model.

## Verification and provenance

Run the independent project's `verify` lifecycle. Tests cover discovery, actual
SQLite reads, stable GUIDs, package moves, same-name objects, package mirrors,
diagram membership, tags, unknown semantics, unresolved references, reviewed
dependency rejection, corrupt/truncated input, schema substitution and bounds.
Writer tests inspect native SQL rows and BLOBs independently of the reader, cover
all declared mappings and reject unsupported schema/identity changes. Tests also
write `target/packaged-fixtures/base.qea`, `renamed.qea` and `empty-template.qea` for the host's
installed-JAR integration smoke test. These files are not shipped in the JAR.

Before claiming EA product compatibility, add a permitted real export with its EA
version, export settings and provenance, compare QEA and XMI projections, and
exercise the installed plugin. No full EA or QEA round-trip claim is made here.

See [THIRD-PARTY.md](THIRD-PARTY.md) for the examined upstream mapping sources,
dependency version evidence, license files and redistribution notices.
