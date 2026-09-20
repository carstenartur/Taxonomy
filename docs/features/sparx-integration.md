# Sparx Enterprise Architect exchange

Status: **experimental XMI implementation; real-product compatibility unverified**.
This is a delivery slice of [#1075](https://github.com/carstenartur/Taxonomy/issues/1075),
which remains open. Neither Enterprise Architect nor Pro Cloud Server was available
for product acceptance. The fixture is synthetic and does not establish support
for any EA release.

## Use the reviewed XMI workflow

Open **Tool integrations**, select an authorized repository/workspace/branch,
and create a **Sparx EA XMI 2.1** connection. Use `SPARX` as the external system
and a stable repository/model identifier. Select a requirements project if the
exchange contains requirements. The profile/version is immutable for a connection.

1. Export the selected EA model/package as the documented XMI 2.1 subset, retaining
   GUIDs. Back up the EA model before testing this experimental integration.
2. Upload `.xmi` or `.xml`. Only mark **complete external scope** when the file
   authoritatively covers exactly the previously synchronized scope.
3. Inspect additions, property updates, moves, connectors, deletion candidates,
   conflicts and the loss report. The preview itself does not mutate the model.
4. Accept or reject each proposed change. Resolve supported field conflicts
   explicitly. Supply a rationale; deleted content is never accepted by the
   bulk nonconflicting-change button.
5. Apply against the displayed exact workspace revision. Reloading an operation
   retains its preview, decisions, identities, evidence and recovery status.
6. For the return path, preview the exact current export, review it, then download
   `taxonomy-sparx.xmi`. Use EA's import options with **diagram import disabled**
   and preserve GUIDs. Validate this procedure against a disposable copy first.
7. Export the reviewed EA state back to Taxonomy to establish the next observed
   baseline. Downloading a file alone never acknowledges an EA update or advances
   the synchronization checkpoint.

The existing integration page provides keyboard controls, filtering and pages of
40 changes. All labels and capability guidance are available in English/German.

## Exact v1 mapping

| EA/XMI construct | Canonical exchange representation | Native Taxonomy effect |
|---|---|---|
| `uml:Package` | `SPECIFICATION` plus stable placements | Reviewed exchange evidence; no fabricated component |
| Class, Component, Actor, Activity, UseCase, Interface, Node, Artifact | `ELEMENT` with explicit canonical type | Typed architecture create/update/delete commands |
| Requirement stereotype / EA Requirement type | `REQUIREMENT` | Versioned project requirement; explicit project required |
| Name and documentation | Title and plain description | Native editable fields |
| Stereotype | `stereotype` extension | Retained; recognized canonical names select the declared mapping |
| Tagged values | Unique `tag:<name>` attributes | Retained in versioned exchange evidence |
| `taxonomy.<property>` tags | `taxonomy:<property>` extensions | Declared editable architecture properties are applied |
| Dependency | `DEPENDS_ON` | Subject to native endpoint/type constraints |
| Realization | `REALIZES` | Subject to native endpoint/type constraints |
| InformationFlow | `COMMUNICATES_WITH` | Subject to native endpoint/type constraints |
| Usage | `CONSUMES` | Subject to native endpoint/type constraints |
| Association | `RELATED_TO` | Generic relationship |
| Composition | `CONTAINS` | Native containment constraints apply |
| Reverse connector direction | Original endpoints and direction retained | Native endpoints are reversed |
| Bidirectional connector / connector-end roles and multiplicities | Explicit unsupported loss | Bidirectional native apply requires rejection; end metadata is retained only in source evidence |
| Diagrams, coordinates and style | Explicit excluded-layout loss | No native view mutation and no diagram XML in export |
| Attributes, operations and other MDG/UML features | Explicit unsupported loss | Original upload stays in scoped source evidence; feature is not exported |

Default native types are Class/Component → Component, Actor → BusinessRole,
Activity → Process, UseCase → Capability, Interface → CoreService,
Node → System, Artifact → InformationProduct. A recognized stereotype or explicit
`taxonomy.elementType` tag refines that mapping. Unsupported types require review
and a supported remap; they are never silently converted to a generic component.

The XMI codec requires the supported EA transport type and its declared canonical
meaning to agree. A foreign transport type such as `ApplicationComponent` must
first pass through the native projection or an explicit remap. `Component` with
canonical `System` needs the corresponding stereotype or `taxonomy.elementType`
tag; contradictory connector/canonical types are rejected before export.

Version 1 preserves packages and hierarchy as exchange evidence. The current
native editor supports transport-neutral package creation, rename and placement;
this does not change frozen v1 exchange behavior. Version 2 adds native package
projection and explicit requirement-to-element mapping in
[native review](#native-version-2-review). Other endpoint combinations still require
an allowed projection or rejection; not every EA relationship is editable.

## Identity, conflicts and operation history

GUIDs are normalized across brace, `EAID_` and `EAPK_` forms. Renames and moves
retain those identities. The shared integration store binds them to native
business identities within organization/repository/workspace/branch scope.
Exports include `taxonomy.id` and `taxonomy.mappingProfile` metadata. Tagged IDs
are evidence, never authorization to target an unrelated native object.

The baseline is the last acknowledged integration state. Independent local and
remote property changes merge; competing changes require explicit review.
Reused internal identity metadata is rejected pending explicit identity repair,
including claims by another EA GUID and claims against removed historical mappings.
Incomplete listings cannot generate deletion candidates. Rejected objects remain
in the operation evidence. Exact local state checks also include the semantic
revision and project fingerprint, not just a Git SHA.

Accepted architecture edits enter the existing typed semantic journal. The
integration operation records actor, time, connection/scope, profile, before/after
values, external fingerprint and review rationale. Git remains the separately
retryable checkpoint layer. A restart resumes the frozen operation; retries do
not create another semantic operation or another requirement.

## Security and limits

The shared XML policy rejects DTDs, external entities and XInclude resolution;
it bounds input/output to 16 MiB, XML nesting to 96, XML elements to 250,000 and
semantic objects to 10,000. Reviewed native apply retains its existing command
limit. Unknown external XML never controls schemas or outbound hosts. Error
responses do not expose parser excerpts. The XMI path requires no credentials.

Back up the integration database and Git repository together: the database owns
previews, mappings, conflicts and checkpoints. Git alone cannot recover that state.
Do not change a connection's profile version to reinterpret existing mappings;
use an explicit migration/reconciliation after a future profile upgrade.

## PCS OSLC AM read and reviewed pull

The separate **Sparx PCS OSLC AM 2.0 read/pull** profile (`sparx-oslc-am-2.0@1`)
reuses the same review, identity mapping, native commands, journal and checkpoint.
It supports `LINK_ONLY`, `IMPORT_COPY` and `MIRROR_READ`. The generic OSLC RM
connector is not the EA AM connector. This implementation has contract tests;
compatibility with an actual PCS installation remains unverified.

An administrator binds a remote endpoint to the exact Taxonomy repository and
owner. Example (replace the nonsecret identities and endpoint):

```yaml
taxonomy:
  integrations:
    remotes:
      civilian-pcs:
        repository-id: <repository-id>
        organization-id: USER:<repository-owner>
        base-uri: https://pcs.example.org/model/oslc/am/
        credential-environment-variable: CIVILIAN_PCS_TOKEN
        allow-private-networks: false
        allow-insecure-http: false
```

`CIVILIAN_PCS_TOKEN` contains the PCS session GUID obtained using the model's
[documented login](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_user_cred.html).
Login, SSO and token renewal are administrator responsibilities in this slice.
The transport sends `Authorization: OSLC <token>` and the required `useridentifier`
query parameter; it does not use Bearer authentication. Configure PCS/proxy access
logs to redact that credential parameter. Taxonomy stores only the profile key
and credential-free resource identities. Authentication failures retain a failed
operation that can be resumed after replacing the server-side credential.

On the integration page select the AM profile, an allowed authority and the key
`civilian-pcs`. **External repository identity must exactly equal `base-uri`**;
leave external configuration blank. Set a project when importing requirement
elements. Leave the remote resource blank for `sp/` discovery, or enter `qc/`.
Projected/filtered queries are rejected so a partial property list cannot silently
replace an existing object. Read the preview, inspect losses and decide each change;
then apply with a rationale. A changed collection produces `REMOTE_STALE`: cancel
that preview and fetch a fresh one. Already accepted operations replay without a
second read or another model mutation.

The reader follows an advertised AM query and every `oslc:nextPage`, bounded to
20 pages and 16 MiB total including discovery. It uses the common scoped HTTP/DNS
policy, rejects redirects, foreign endpoints, paging loops and credential reflection.
A failed page yields no partial preview. The collection fingerprint includes the
page identities, content and ETags. It is checked by reading again before apply;
it is **not an atomic PCS snapshot or conditional remote write guarantee**. Page
exhaustion never authorizes deletion of missing objects.

Version 1 imports package/element properties, descriptions, GUIDs and package
hierarchy, including requirement elements. A single inline stereotype is read
from the documented nested `ss:stereotypename/ss:name` RDF shape. Multiple or
unrecognized structured stereotypes produce a loss and require explicit remapping
before an element can be applied; no arbitrary canonical type is chosen. Connectors, tags, attributes, operations
and diagrams are not fetched; these exclusions appear in the loss report. XMI
remains the broader semantic exchange route. AM advertises no file export or live
write capability, and its selection validator is independent of publication.

## Remaining product acceptance and live writes

Sparx documents an AM-specific POST update endpoint and a model login token,
including token transport in GET query parameters or POST RDF. These differ from
the generic OSLC RM transport. The reviewed vendor update documentation does not
establish atomic expected-version writes or safe idempotent creation. A preflight
GET followed by unconditional POST cannot provide the issue's concurrency guarantee.
Live create/update/delete, item-level partial publication recovery and full
Push/Synchronize therefore remain open pending a proven PCS contract or a
Sparx-side conditional adapter. The optional SBPI plugin is also not implemented.

Real EA acceptance must test native export → EA import → rename/move/tag/connector
edit → EA export → reviewed Taxonomy apply, including diagram preservation. Record
the evidence described in `docs/qa/sparx-compatibility.json`; do not change its
status to passed from synthetic fixture results.

References: [EA XMI exchange](https://sparxsystems.com/enterprise_architect_user_guide/17.2/model_exchange/importexport.html),
[OSLC AM capabilities](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/info_accessed_via_oslcam.html),
[EA update protocol](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_upd_resources.html),
[authentication](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_user_cred.html),
[GUID prefixes](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/guid_prefix_tables.html).

## Explicit version 2 semantic features

Choose `sparx-xmi-2.1@2` or `sparx-oslc-am-2.0@2` when creating a new connection.
The profile ID remains unchanged; version is a separate immutable value. An API
creation request without `profileVersion` selects `1`. Existing connections and
frozen reviews continue to dispatch their exact stored version.

Both v2 transports use one semantic assembler and the same EA GUID identities.
The fixtures are **synthetic contract fixtures, not EA exports or PCS captures**.
Native package editing and explicit requirement endpoint projection are available
as described in [native review](#native-version-2-review). Feature preservation
does not imply native UML authoring or live publication.

| V2 construct | Canonical representation | Preservation and review |
|---|---|---|
| Tagged value (`tv_`, `attv_`, `optv_`) | `FEATURE`, `tagged-value`, stable GUID, owner, name/value | Duplicate names remain distinct features; no owner value wins. Unique names also project to `tag:<name>`. |
| Attribute (`at_`) / `ownedAttribute` | `FEATURE`, `attribute` | Owner element, optional bounded position, scalar datatype/scope/default/multiplicity fields |
| Operation (`op_`) / `ownedOperation` | `FEATURE`, `operation` | Owner element, optional position and declared operation fields |
| Parameter (`pr_`) / `ownedParameter` | `FEATURE`, `parameter` | Owner operation, position, direction, datatype/default fields |
| Reified dynamic AM connector (`lt_`) | Same directed `Relation` meaning as XMI | Identical reports deduplicate; conflicting same-GUID reports fail. Native type rules still apply. |
| Endpoint outside queried roots | `FEATURE`, `external-connector`, preserved-only | Explicit `SPARX_AM_ENDPOINT_OUTSIDE_SCOPE`; no native relation or deletion inference |
| Unknown RDF property | `attribute:<predicate>` or `extensions.rdf:<predicate>` | `PRESERVED_EXTENSION`; linked/structured values stay bounded evidence and are never fetched |

The v2 XMI writer emits supported attributes, operations, parameters and
identity-bearing tags. A declared `evidence` extension retains remaining canonical
properties for this fixture profile; this is **not** a claim that EA preserves
Taxonomy extensions. Non-consumed feature-detail properties, including stereotypes,
remain in bounded evidence with `PRESERVED_EXTENSION` losses. Unsupported feature
children and external connectors cannot be silently delivered as a lossy XMI
feature. Reject those items before file export. Unauthored connector-end attributes
and subtrees receive connector/field-specific unsupported losses before delivery;
source/target identities and direction retain their existing meaning.

AM v2 exhausts root queries and every connector/tag/attribute/operation/parameter
collection, including attribute and operation tags. Traversal derives fixed paths
from validated kind-specific IDs. Every page uses the secured PCS transport;
arbitrary RDF links never become requests. All collections share these limits:

| Aggregate bound | Limit |
|---|---:|
| Enriched package/element roots | 250 |
| Pages in one collection | 20 |
| HTTP responses, including discovery | 1,024 |
| Response bytes, including discovery | 16 MiB |
| RDF statements | 100,000 |
| Objects | 10,000 |
| Properties per RDF object | 128 |
| Stereotypes per object | 32 |
| Successful read deadline | 30 seconds |

Any failed page or exceeded bound fails the complete fetch and leaves a durable
failed operation. The v2 fingerprint covers each endpoint kind, owner GUID,
sanitized URI, ETag and body hash, including discovery and feature pages. Reviewed
apply repeats the read, so changed features reject an otherwise unchanged-root
preview with `REMOTE_STALE`. Collection exhaustion is neither atomic nor an
authoritative deletion scope: AM v2 always returns `completeScope=false` and
`EXHAUSTED_COLLECTIONS_NOT_ATOMIC_OR_AUTHORITATIVE`.

## Native version 2 review

Version 2 projects packages into neutral native packages with stable IDs and EA
GUID mappings. Native package rename/move/placement edits appear in XMI delivery;
version 1 connections retain their original evidence-only package behavior.
Incomplete incoming scopes retain unmentioned native siblings. Requirement package
membership remains evidence (`SPARX_REQUIREMENT_PACKAGE_PRESERVED_ONLY`).

The review's **Native endpoint projection** chooses `ARCHITECTURE_RELATION`,
`REQUIREMENT_MAPPING`, or `PRESERVE_ONLY`. Source/target lists use durable native
identities supplied by the exact-state endpoint-options API, after direction
normalization. Requirement mappings require an explicit choice. Package endpoints,
requirement pairs and bidirectional connectors require preserve-only review or
rejection. Invalid mappings report `SPARX_ENDPOINT_KIND_UNMAPPED`,
`SPARX_ENDPOINT_MAPPING_REQUIRED` or `SPARX_DIRECTION_UNMAPPED` before native writes.

For example, a review includes:

```json
{"endpoints":{"change-id":{"sourceInternalIdentity":"PROJECT__REQ-1","targetInternalIdentity":"arch-reader","projection":"REQUIREMENT_MAPPING","canonicalType":null}}}
```

The pure complete plan resolves the exact `x-project-key`/`x-requirement-key` pair,
preserves existing canonical IDs and rejects sanitizer collisions with
`REQUIREMENT_IDENTITY_MISMATCH`. Requirement database writes join the transaction;
the real portfolio contribution precedes typed mapping commands and the final local
semantic journal. An analysis-owned mapping causes
`REQUIREMENT_MAPPING_OWNERSHIP_CONFLICT`. Old review JSON can omit `endpoints`.
The application and browser walkthrough uses contract fixtures; EA/PCS product
compatibility remains **NOT_EXECUTED**.

Endpoint choices confirm the exact normalized connector ends. Even same-kind retargeting to another native object is rejected with `SPARX_ENDPOINT_KIND_UNMAPPED`; projection/type choices remain supported. This keeps native meaning aligned with retained and exported endpoint evidence.
