# Standards interoperability

Implementation and product compatibility validation: #926. See [ADR 0006](../adr/0006-standards-interoperability.md).

## Workflow and authority

Open **Tool integrations** from the architecture editor in an owned private
workspace. Create a connection with a format profile, external identity, authority
mode and (for requirements) project ID. Upload a file or read a configured OSLC
resource. The preview identifies the exact internal and external states, changed
objects, conflicts and mapping losses. Review every proposed change, provide a
rationale and apply. Large previews have filtering and pages of 40 objects.

| Mode | Inbound behavior | Outbound behavior |
|---|---|---|
| LINK_ONLY | Store trace evidence without changing the model | No publication |
| IMPORT_COPY | Create/update independent reviewed copies; omissions do not delete | Reviewed file export |
| MIRROR_READ | Reviewed mirror; complete file omissions may propose deletion | No publication |
| PUBLISH_TARGET | No inbound model import | Reviewed file export |
| BIDIRECTIONAL | Three-way reviewed updates and explicit deletion candidates | Reviewed file export |

For `LINK_ONLY`, the review's target field accepts `requirement:<key>` in the
selected project or `element:<id>` in the workspace. The server resolves and
authorizes the target. Linking records provenance without overwriting either
side's fields. A target from another scope is rejected.

The standard file adapters do not write to a vendor server. Downloading a file
does not confirm remote acceptance. OSLC consumer transport supports discovery and
conditional reads, not remote write capability. No scheduled synchronization runs
implicitly. Capability descriptors are the authority for available actions.

## Supported profiles

| Profile | Supported representation | Explicit limits |
|---|---|---|
| ReqIF 1.2, profile 1 | Objects, types/datatypes, attributes including enumeration/XHTML, specifications and repeated hierarchy occurrences, relations, retained extensions | XML packages up to 16 MiB and 10,000 artifacts; no ReqIFZ extraction or attachment fetching; portfolio titles 240 characters and text 100,000 characters |
| ArchiMate exchange 3.1, profile 1 | Declared element/relation mappings, properties, folders, views, node placement and connection evidence | Unsupported semantic types require mapping/rejection; canonical relation rules still apply; reviewed architecture batches are bounded to 2,000 typed commands |
| OSLC RM 2.1 / Core 3.0, profile 1 | RDF/XML discovery/read consumer; authenticated read-only provider with RDF/XML, Turtle and JSON-LD | No remote write, delegated UI or arbitrary query execution; structured blank-node requirement attributes require another explicit mapping profile |

ReqIF package extensions and attribute definitions are retained as reviewed
metadata. Specification children, ArchiMate view nodes/connections and organization
groups have separate occurrence identities; rejected children cannot survive in
hidden container XML. Multiple organization groups remain separate on export.
Optional XMI, UAF, SysML and vendor-specific connectors can implement the same SPI;
they are not advertised as capabilities of these three built-in profiles.

ArchiMate mappings include Capability, BusinessProcess, BusinessRole,
ApplicationService, BusinessService, CommunicationNetwork, ApplicationComponent,
DataObject, BusinessObject and TechnologyService. The corresponding canonical
types and relation constraints are validated on the server. `Taxonomy.ElementType`
and `Taxonomy.RelationType` properties preserve a more precise canonical type on
return exports. Names are never used for identity matching.

Canonical relations without a lossless declared ArchiMate representation remain
visible in the export preview with `UNSUPPORTED_RELATION_TYPE`. Review must reject
them or choose an explicit mapping. They never silently become Association. A
partial reviewed export is identified as partial. Local view membership and
editable properties overlay older exchange evidence; removed placements prune
dependent visual connections with an explicit loss report.

ReqIF title/text mapping uses recognized attribute names; the user can select
other existing attributes during review. A single rich-text field cannot represent
two independently editable fields. In that case the body uses that field and the
title uses LONG-NAME, with an explicit mapping report. Safe original types and
attributes survive export; changed canonical fields overlay their original values.

## API and identity

`/api/integrations` lists/creates scoped connections. Connection resources expose
the current exact state and operation history. `/previews`, `/remote-previews` and
`/export-previews` require a stable operation UUID and expected `InternalState`.
`/apply` and `/files` take the preview fingerprint, item decisions, optional typed
mapping overrides and rationale. Operation resources expose events, `/retry`,
`/cancel` and the frozen reviewed `/file`.

The canonical request transport supplies CSRF, repository/workspace selection and
authentication. Missing and foreign integration identities both return 404.
Write routes additionally require ARCHITECT or ADMIN. No browser-side canonical
graph is accepted. Retrying the same operation with different input or decisions
is rejected. Repeating an accepted request does not reapply the model.

Reviewed delivery binds generated external IDs to their original local objects,
so returning a native export updates the same requirement/element. These bindings
retain the frozen exported values but have **no confirmed external baseline**.
Omissions cannot delete such objects. A later return compares against that frozen
delivery and detects intersecting local changes; file download still advances no
confirmed synchronization checkpoint.

The connection overview provides its exact **OSLC discovery** URL. The provider's
`/oslc/scopes/{scope}/catalog` links service providers, resource shapes and paged
queries. `/configurations/current` identifies the scoped stream; any other
`Configuration-Context` is rejected. Resource responses provide ETags with
`If-Match` (412 on mismatch) and `If-None-Match` (304 on a match). Accept negotiation
honors quality values and does not select a representation explicitly excluded
with `q=0`. These resources still require authenticated repository/workspace
context; the scope hash is not an access token.

Requirement endpoints expose only the currently approved version. Its text and
version identity are immutable, but visibility follows current approval: an older
version is not a historical approval archive. Architecture version endpoints read
an explicitly selected reachable Git checkpoint. The small provider does not
implement arbitrary OSLC query expressions or general configuration management.

## Security and operations

XML is bounded before DOM construction. DTDs, external entities, external schemas,
XInclude processing and active XHTML are excluded. Schemas resolve only to pinned
classpath resources. Passive links are retained; automatic external media/CSS and
executable rich-text content are rejected. No parser or remote response body is
included in failure events.

OSLC remotes are administrator-owned `taxonomy.integrations.remotes` settings. A
remote profile binds `repository-id`, `organization-id`, an HTTPS `base-uri` ending
in `/`, and an optional `credential-environment-variable` for a bearer token. Its
connection contains only the profile key. Organization identity is the repository
owner type and ID, for example `ORGANIZATION:<id>`. DNS addresses are checked by the
HTTP client's actual connection resolver; redirects are never followed. Private
networks and insecure HTTP are disabled unless explicitly configured for that
profile. Link-local/metadata addresses remain forbidden. Reads have connection,
socket, total deadline and response size bounds. Unknown query parameters fail
closed. Certificates and hostnames retain normal HTTPS verification.

Back up both database-held Git storage and the application database tables. The
workspace/editor journal, integration operations, mappings and checkpoints must
be restored together with portfolio versions. An integration in CHECKPOINT_PENDING
resumes the frozen Git intent. FETCH_PENDING/FETCH_FAILED can repeat the bounded
remote read. Cancellation is allowed before model acceptance; it does not rewrite
accepted history. Search/projections can be rebuilt independently of these durable
authorities. PostgreSQL migration V20 introduces the integration tables; Hibernate
creates equivalent mapped tables for the other supported databases.

If the Git parent moved, the operation becomes `CONFLICT` and records
`MODEL_APPLIED_CHECKPOINT_CONFLICT`. Its accepted model snapshot is retained;
explicit version reconciliation is required before another review. An ordinary
retry must not overwrite the moved branch. Transient failures retain the pending
intent and can retry the same checkpoint ID without a second model mutation.

## Compatibility evidence

The exchange unit tests exercise unmodified Eclipse RMF and Archi producer fixtures,
semantic updates, hierarchy/layout preservation, native object additions, stable
identities and adversarial XML. Three source fixtures intentionally remain negative
tests: the sampled StrictDoc file lacks a required timestamp, the sampled Polarion
file reuses an XML ID, and the upstream file named `valid_file` has an unresolved
IDREF under the normative schema. They are not silently repaired.

Fixture success is format evidence, not installed-product certification. Product
import/export evidence comes from the required **Interoperability product round
trips** CI job:

| Product path | Pinned version | Evidence and limits |
|---|---|---|
| StrictDoc → Taxonomy → StrictDoc → Taxonomy | StrictDoc 0.29.0 | Native SDoc with stable MIDs, text and Parent relation; declaration, occurrence and relation IDs regenerated by StrictDoc are explicit losses |
| Archi fixture → Taxonomy → Archi → Taxonomy | Archi 5.10.0 | Official release SHA-256 is verified; identity, type, endpoint, hierarchy and geometry comparison of the fixture subset |

The job retains `evidence.json`, execution logs, dependency versions and exchanged
files, tied to the application digest and Git source revision. A successful report
supports only the recorded product/version/profile/fixture combination. Full
vendor compatibility is not implied. Until that job passes, these paths remain
unverified. Core tests also exercise durable rollback/retry, three-way conflicts,
source preservation and recovery across six independent JVM processes. Browser
tests cover role restrictions, reviewed import, idempotent retry, reload,
cancellation, frozen download, German labels, keyboard operation and narrow
viewports. PostgreSQL, MSSQL and Oracle tests exercise concurrent HTTP acceptance
and durable mapping/checkpoint reload. Complete CI validation remains a release gate.
