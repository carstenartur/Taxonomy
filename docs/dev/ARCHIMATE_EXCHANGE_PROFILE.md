# Experimental ArchiMate Exchange profile

Taxonomy exports the selected persisted architecture graph through **ArchiMate
Exchange 3.1**, using the unchanged namespace
`http://www.opengroup.org/xsd/archimate/3.0/`. The executable profile is
[`taxonomy-archimate-3.1-v2`](../../taxonomy-export/src/main/resources/archimate/profile-v2.tsv).
This is an experimental model projection. It is not standards-certified, a
lossless replacement for canonical persistence, or a certified handoff to a named
architecture product. [Issue #967](https://github.com/carstenartur/Taxonomy/issues/967)
remains open for independent consumer acceptance.

## Download and authority

The read-only architecture workbench shows the selected snapshot, commit and
mapping profile before enabling **Download ArchiMate + manifest**. The download is:

```text
GET /api/projects/{projectId}/architecture-workbench/{snapshotId}.archimate.zip
```

The package contains `model.archimate.xml`, `mapping-profile.tsv`, and
`manifest.json`. The existing `.archimate.xml` route remains available; its XML
also embeds the original identities, provenance and complete mapping/loss report
as standard ArchiMate properties. All authorization and exact-snapshot checks
run before serialization. No LLM, current catalogue lookup, or selection-policy
rerun is part of this export.

The manifest binds the XML and exact mapping-profile bytes with SHA-256. The
existing HTTP authority headers and artifact ETag also cover the ZIP bytes. The
canonical semantic graph hash remains independent of presentation and container
geometry. Human review properties are the authorized mapping decisions observed
at export; the artifact hash binds those values without treating a later decision
as a mutation of the immutable analysis graph.

| Preserved authority | Source |
| --- | --- |
| Project, requirement, immutable requirement version | Authorized snapshot projection |
| Snapshot ID, status and creation time | Persisted analysis snapshot |
| Repository, workspace, branch and authoritative commit | Authorized snapshot/workspace scope |
| Catalogue fingerprint | Stored snapshot fingerprint, when available |
| Exporter, ID and mapping-profile versions | Executable export contract |
| Canonical semantic graph hash | Exact persisted graph |

Older projections may lack a requirement-version, repository or catalogue
fingerprint. Each unavailable field is named as `OMITTED`. The original selection
profile fingerprint is not retained in the current snapshot projection; this is
reported explicitly. The exporter never substitutes the current catalogue or
current preference values for missing historical data.

## Mapping and properties

The TSV is UTF-8, has a header row, and contains one row per supported source type:
`scope`, `sourceType`, `targetType`, `accessType`, `properties`, `kind`, `rationale`,
`profileVersion`. The `properties` column contains semicolon-separated property keys. It covers the complete `RelationType` enum plus the explicitly
supported diagram aliases. Unknown semantic element or relationship types fail
export; this profile enables no fallback to BusinessObject or Association.

All native type projections are marked `MAPPED`. They retain the exact original
type in `taxonomy.type`. Several Taxonomy categories share the same ArchiMate type;
that target type alone cannot reconstruct the Taxonomy meaning. Relationships
retain the Taxonomy endpoint order. This profile does not claim that every broad
projection is an equivalent ArchiMate metamodel relationship. `PRODUCES` writes the
`Write` qualifier; `CONSUMES` writes `Read`; unqualified Access uses the XSD default
`Access`. The clean reader accounts for that default.

| Scope | Standard exchange representation |
| --- | --- |
| Element and relationship identities | Deterministic XML identifiers plus `taxonomy.id` |
| Original element/relation type | String `taxonomy.type` |
| Relationship category and endpoint order | `taxonomy.relationCategory` and source/target references |
| Relevance and score meaning | Number `taxonomy.relevance`; explicit model score-semantics property |
| Anchor and impact selection | Boolean `taxonomy.anchor` / `taxonomy.selectedForImpact` |
| Hierarchy and layer | `taxonomy.parentId`, numeric depth/layer |
| Confidence, direct score, mapping origin | Typed, allowlisted snapshot properties where available |
| Human review, action status, actor/time, rationale, action evidence | Authorized decision properties where available |
| View membership and supported layout | Concrete element-backed nodes and relationship-backed connections |

ArchiMate property definitions use `string`, `number` and `boolean`; values are
validated against the declared Taxonomy type when read. Properties are allowlisted
at the application boundary. Prompt text, provider/model response payloads,
provider-derived explanations, raw requirement text, warnings and unreviewed
source evidence are not copied into exchange metadata. Omitted fields are named
in the report. Human decision comments and action evidence are intentionally part
of the authorized handoff.

Every selected object is either exported or individually accounted for by the
loss report. Visual-only containers and connections touching those containers are
`OMITTED`, with the original identity retained in the report. Original parent IDs
remain properties even when they point to an omitted visual container. The
manifest's field policy explains that properties extend the target model while
native type projections transform its semantics.

## Identity, layout and validation

`length-prefixed-utf8-hex-v1` encodes each original identity component as four bytes
of UTF-8 byte length followed by those bytes, then hex-encodes the result. Separate
XML prefixes identify models, elements, relationships, views, nodes, connections
and property definitions. This is injective, independent of display names, and
does not perform lossy sanitization or truncate hashes. A node or connection ID
includes its view identity, so multiple occurrences in separate views cannot
collide. Snapshot-bound model IDs use the immutable snapshot identity.

The typed model supports up to 32 views. Conversion creates a layered view and,
when it selects a nonempty proper subset, an anchor/impact view. Flat organization
groups organize elements by their original type. Supported layout includes node
position, size, fill color, line width, labels and connection membership. Nested
view nodes, bendpoints, arbitrary vendor extensions, multilingual value lists, language tags other than the emitted `en`, and
foreign view constructs are outside this declared reader profile.

Every output is generated with StAX and validated against the complete pinned
model/view/diagram XSD set **before any bytes are returned**. Required concrete
`xsi:type` attributes appear on abstract node and connection declarations;
`diagrams/view` is already concrete in the schema. Styles use `lineWidth` as an
attribute. References and duplicate identities are checked before serialization;
connection endpoints must also agree with their relationship inside the same
view. Empty optional collections are omitted because present collections require
at least one child.

The schema set is packaged with the application for offline validation. Its
[origin, source commit, licence and checksums](../../taxonomy-export/src/main/resources/archimate-3.1/ORIGIN.md)
are retained; tests verify the original bytes. DTDs and external entities are
rejected, and schema resolution is restricted to the five pinned files. Input and
output are bounded to 32 MiB and XML nesting depth 128, 10,000 elements, 30,000 relationships, 32 views,
50,000 view nodes and 150,000 connections. Invalid XML 1.0 characters, lone
surrogates, malformed XML IDs, unknown types and unresolved references fail.

## Reimport boundaries

`ArchiMateExchangeReader` performs schema validation, decodes the original stable
identities, validates typed properties and the mapping/qualifier contract, and
reconstructs the canonical graph plus organizations, properties, views and the
loss report. Tests compare this semantic model and view membership across repeated
export/import, including duplicate display names, long Unicode labels, an XSD
default qualifier, and a 1,000-element model. Layout is compared as supported
structured data, independently of the graph hash.

The existing catalogue import/preview endpoint has a narrower purpose: it creates
catalogue relations. For a Taxonomy-profile artifact it now resolves exact node
codes and original relationship types and never falls back to label matching.
Missing identities are reported as unmatched. Repeated relation materialization
is idempotent in the selected repository/workspace. Generic third-party models
retain the explicitly heuristic catalogue matching path after XSD validation.

**Catalogue materialization does not restore an analysis snapshot, overwrite
catalogue labels, preserve an external relationship ID as a database primary key,
or persist imported review decisions/views.** Those omissions are returned in the
machine-readable import loss report. The clean exchange reader preserves these
values in its typed model. Treat the catalogue relation endpoint as an explicit
projection, not as the persistence inverse of an architecture snapshot.

## Consumer evidence and remaining acceptance

The regression suite proves normative XSD validity and the Taxonomy reader
contract. It does not substitute for Archi desktop import or another independent
architecture consumer. A source inspection of Archi commit
`f757b06d5f75b5565786bb0679c75450cc3a577f` found that `XMLModelImporter` discards
property-definition types because Archi stores property values as strings. This
is **source evidence only**, not an executed import. A subsequent Archi export
may therefore lose the typed-property declarations required by this profile; the
strict reader rejects that changed contract rather than silently accepting it.

Before closing #967, record successful imports of the same fixture into Archi and
an independent consumer, including exact tool versions, Taxonomy source commit,
schema/profile versions, fixture checksums, inspected model/view content and
observed losses. Keep the UI experimental until those records exist. No claim of
universal or named-tool compatibility is made by this implementation.

For comparison: VSDX is the experimental editable visual handoff; SVG/PDF are
human-readable views; Mermaid/Structurizr are special-purpose textual projections;
canonical JSON evidence retains the richer evidence model. ArchiMate Exchange
transports the declared model subset between tools; Git/DSL and semantic history
remain Taxonomy's canonical persistence layers.
