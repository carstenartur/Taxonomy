# Sparx QEA read plugin (experimental)

This independently built, operator-installed plugin contributes the `sparx-qea@1`
integration profile to Taxonomy's existing preview and reviewed-apply workflow.
It reads an uploaded SQLite `.qea`/`.qeax` snapshot. It does not run Enterprise
Architect, contact a Sparx server, alter the uploaded database, or write QEA/XMI.

**Compatibility evidence is synthetic only.** The tests construct small SQLite
databases using the examined EA table shapes. They are not EA product exports
and do not establish compatibility with any Enterprise Architect version.

## Build and install

Use Java 25. Install the matching public SDK from the repository first, then build
this project independently:

```sh
./mvnw -pl taxonomy-extension-api -am install -DskipTests
cd plugins/taxonomy-sparx-plugin
../../mvnw verify
```

Install `target/taxonomy-sparx-plugin-1.5.3-SNAPSHOT.jar` through the host's existing
operator plugin installation flow and restart the host. The manifest declares
plugin ID `taxonomy.sparx`, mode `STARTUP`, and host SPI `>=1.3.0 & <2.0.0`.
The SDK is provided by the host and is not included in the plugin JAR. SQLite
and its native libraries are included. The plugin has no dependency on the
Taxonomy application, interop implementation, Spring, Neo4j, or AAroN binaries.
Allow the driver's native library with the host JVM option
`--enable-native-access=ALL-UNNAMED` on Java 25. The standalone test JVM already
sets that option.

The preferred upload MIME type is `application/vnd.sparx.qea`; SQLite and binary
upload MIME types are also accepted. A MIME type or filename never substitutes
for SQLite header and schema validation. Upload a self-contained, closed database
snapshot: an uncheckpointed WAL file is not included by uploading only a `.qea`.

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
workflow. Independently, profile 1 limits a decoder input to 32 MiB, supported rows to 20,000 in total, a scalar
cell to 65,536 characters, total retained text to 16 Mi characters, schema entries
to 512, columns per supported table to 128, and hierarchy depth to 128. A deadline
and SQLite progress handler bound database reads. Exceeding a limit rejects the
snapshot instead of returning a silently truncated model.

## Verification and provenance

Run the independent project's `verify` lifecycle. Tests cover discovery, actual
SQLite reads, stable GUIDs, package moves, same-name objects, package mirrors,
diagram membership, tags, unknown semantics, unresolved references, reviewed
dependency rejection, corrupt/truncated input, schema substitution and bounds.
Tests also write `target/packaged-fixtures/base.qea` and `renamed.qea` for the host's
installed-JAR integration smoke test. These files are not shipped in the JAR.

Before claiming EA product compatibility, add a permitted real export with its EA
version, export settings and provenance, compare QEA and XMI projections, and
exercise the installed plugin. No full EA or QEA round-trip claim is made here.

See [THIRD-PARTY.md](THIRD-PARTY.md) for the examined upstream mapping sources,
dependency version evidence, license files and redistribution notices.
