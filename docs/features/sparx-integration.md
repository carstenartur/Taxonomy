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

Packages and package hierarchy are durable exchange semantics. The current native
editor does not provide package authoring. Requirements-to-element connectors and
endpoint combinations outside the native architecture matrix require rejection or
supported remapping before apply. This does not claim every EA relationship is
editable in Taxonomy.

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

## OSLC and remaining acceptance

OSLC live synchronization is **not enabled by this slice**. The shared canonical
contracts/diff/journal remain its required foundation. The existing generic OSLC
RM connector is not an EA Architecture Management connector and must not be used
as one.

Sparx documents an AM-specific POST update endpoint and a model login token,
including token transport in GET query parameters or POST RDF. These differ from
the generic OSLC RM transport. The reviewed vendor update documentation does not
establish atomic expected-version writes or safe idempotent creation. A preflight
GET followed by unconditional POST cannot provide the issue's concurrency guarantee.
Live create/update/delete, item-level partial publication recovery and full
Pull/Push/Synchronize therefore remain open pending a proven PCS contract or a
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
