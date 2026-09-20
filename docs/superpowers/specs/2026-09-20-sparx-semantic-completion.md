# #1075 Sparx read/mapping and native-edit completion inventory

Date: 2026-09-20
Code inspected: `/workspace/scratch/dae1667028d4/Taxonomy-completion` at `0af596fc8538c49f7ce3c84adb093debe5333a84`
Scope: work that can be implemented and tested without Enterprise Architect or Pro Cloud Server. This design does not establish EA/PCS compatibility.

## Binding implementation decisions

The design below is the implementation contract, with these clarifications:

- Profiles keep their existing IDs (`sparx-xmi-2.1`, `sparx-oslc-am-2.0`) and add explicit version `2`. Key the shared registry by `(id, version)`. Legacy connection creation without a version continues to select version `1`; existing connections and frozen reviews retain their behavior. The UI selects and displays both ID and version. Do not encode version in a new ad-hoc connector ID.
- V2 adds native package projection. Existing v1 package evidence remains governed by v1 semantics. Local package editing is transport-neutral. Requirement mapping requires an explicit reviewed endpoint projection; old reviews never acquire one implicitly.
- A feature is identity-bearing exchange evidence. Supporting attribute/operation round-trip does not claim native UML authoring. Unknown structured RDF is bounded preserved evidence with an explicit loss; no arbitrary remote links are fetched.
- The original inventory's private transport DTO names are design guidance, not a second public domain model. Keep one shared semantic assembler and reuse existing XML/RDF primitives.
- Both AST-to-model and model-to-AST directions, serializer, semantic inverse/undo/redo and native editor projection must retain packages and mappings.
- Pure requirement planning may live in `IntegrationDomainAdapter` when it needs connection/mapping data; only genuinely portfolio-owned operations belong on `IntegrationPortfolioPort`. Planning returns deterministic requirement business identity without reserving a database row. Execute under the existing scoped project lock and transaction, validating the contributed DSL before acceptance.
- Actual EA/PCS execution remains `NOT_EXECUTED`; no live write capability is introduced by this slice.
- Portfolio requirements already have one real canonical `requirement` projection carrying exact `x-project-key` and `x-requirement-key`; `projectRequirement` is persistence metadata and must not become a second canonical requirement. Preserve existing canonical IDs and resolve/validate them by that exact pair. The contributor's current sanitized ID algorithm is not injective, so reject ambiguous/colliding IDs before mutation (`REQUIREMENT_IDENTITY_MISMATCH`), rather than silently remapping or globally migrating existing references in this adapter slice. Pure endpoint planning uses the same contributor identity through the portfolio boundary. An exchange mapping cannot overwrite an analysis-owned mapping header (`REQUIREMENT_MAPPING_OWNERSHIP_CONFLICT`).
- For batches with new requirement mappings, perform transactional requirement writes, then the real portfolio DSL contribution, then typed mapping commands, then final validation/journaling; validate the complete planned identity/endpoint/ownership set before writes. Keep the standalone mapping command's requirement-exists check. No fake forward-reference requirement block.
- Replace sequential package move/place sketches below with one atomic `SetArchitecturePackagePlacements(List<PackagePlacement> placements, Set<String> completeParentScopes)`, where `PackagePlacement(PackageMemberKind kind,String memberId,String parentPackageId,int position)` and kinds are `PACKAGE`/`ELEMENT`. Validate complete final sibling lists for every touched old/new parent, unique contiguous positions, acyclicity and depth only after the replacement is constructed. This permits swaps and cross-parent reorders without an invalid intermediate state. Root is represented explicitly and must not require listing every unrelated ungrouped taxonomy element. Detach/removal must be explicit, never inferred from omitted siblings. Compute final native lists from exact local state plus reviewed deltas, retaining unmentioned siblings from incomplete external scopes.

- Task 2 endpoint identity adjudication (2026-09-20): `EndpointOverride` confirms the exact durable native identities of the normalized connector ends. Same-kind arbitrary retargeting is rejected with `SPARX_ENDPOINT_KIND_UNMAPPED` before writes; it would diverge from retained/exported external endpoint evidence. Projection/type remapping remains explicit and supported. Intentional endpoint retargeting requires a future identity/provenance rewrite design. UI choices expose only the actual allowed ends.

- Task 2 review/type adjudication (2026-09-20): only accepted selected artifacts influence the native plan; REJECT/KEEP_INTERNAL endpoint choices cannot mutate retained connectors. A relation has one effective canonical type for native semantics and retained/exported evidence. An explicit later type remap replaces prior endpoint type evidence; a fresh endpoint choice without a type uses the selected canonical type. Concurrent explicit MappingOverride and EndpointOverride types must agree, otherwise fail with `SPARX_ENDPOINT_MAPPING_REQUIRED` before writes. Incoming endpoint tags still cannot grant or clear locally persisted projection approval.

## Recommendation

Implement a new immutable `sparx-oslc-am-2.0@2` and `sparx-xmi-2.1@2` pair. Keep all existing `@1` connections frozen. Version 2 should:

1. exhaust the root AM query and then fetch the documented connector, tagged-value, attribute, operation, attribute/operation-tag, and operation-parameter collections under one aggregate read budget;
2. convert both AM RDF and XMI into the same `ExchangeDocument` meanings for connectors, tags, attributes, operations, and parameters;
3. represent non-native UML features as reviewable, identity-bearing exchange features rather than a blanket loss or an opaque source blob;
4. add native architecture-package commands and a separate requirement-mapping command, instead of treating a package as a `System`, treating a requirement as an element, or weakening the architecture relation matrix;
5. require an explicit relation projection when an EA connector cannot map directly to a native architecture relation.

Do not add remote publication in this slice. `LifecycleIntegrationConnector.publish` and the durable publication journal can be designed separately. The AM descriptor should remain read/pull only, and the compatibility file must remain `NOT_EXECUTED`.

## Primary contract evidence and its limit

Sparx documents the GET endpoints used by this design:

- connectors: `linkedresources/<Package-or-Element GUID with prefix>/`;
- element attributes: `attributes/<Element GUID with prefix>/`;
- element operations: `operations/<Element GUID with prefix>/`;
- operation parameters: `parameters/<Operation GUID with prefix>/`;
- tagged values: `taggedvalues/<Package, Element, Attribute, or Operation GUID with prefix>/`.

See [Retrieving Resources and Resource Features](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/ret_res_feat.html). The documented prefixes are `lt_`, `at_`, `attv_`, `op_`, `optv_`, `pr_`, and `tv_`; see [GUID Prefix Tables](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/guid_prefix_tables.html).

The query capability itself only queries package, element, and diagram properties; it does not query their attributes or operations. Its resource response advertises feature links, but the implementation should derive fixed endpoint paths from validated identifiers rather than follow arbitrary response links. See [Query Capability](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_query_cap.html).

Sparx's update examples document the field vocabulary needed to interpret attributes, operations, parameters, connectors, and tags. They do not establish real response shapes, pagination behavior for every feature collection, atomic snapshot versions, or conditional writes. See [Updating Resources and Resource Features](https://sparxsystems.com/enterprise_architect_user_guide/17.2/the_model_repository/oslc_upd_resources.html). Synthetic fixtures must therefore be labelled contract fixtures, not PCS captures.

## Existing implementation inventory

### Exchange and mapping

- `IntegrationContracts.ExchangeDocument` carries artifacts, relations, placements, metadata, and losses. `ArtifactKind` has no feature kind.
- `ExchangeItems.flatten/expand/fields/merge` provides item-level three-way comparison. It can support a new exchange feature if the feature has its own identity and owner reference.
- `SparxMappingProfile` normalizes EA object GUIDs and declares v1 element and connector mappings. `guid` currently understands braces, `EAID_`, and `EAPK_`, but not AM feature prefixes.
- `SparxModelValidator.model` permits only `SPECIFICATION`, `ELEMENT`, and `REQUIREMENT` artifacts. It validates connector endpoints and package placements, but has no feature ownership rules.
- `SparxXmiReader.walk` maps packages, elements, requirements, tags, connectors, and placements. Children such as `ownedAttribute` and `ownedOperation` become `SPARX_FEATURE_EXCLUDED` losses. Connector-end metadata is also explicitly excluded.
- `SparxXmiWriter.write` emits packages, elements, requirements, connectors, tags, and hierarchy. It has no attribute, operation, or parameter writer.
- `SparxOslcAmCodec.read(List<Page>, URI)` parses only the paged `qc/` resource collection. It maps package/element scalar properties and hierarchy, then emits one `SPARX_AM_FEATURES_NOT_FETCHED` loss.

### HTTP read boundary

- `SparxOslcAmReader.validate` only accepts `sp/` and unfiltered `qc/`. It rejects every documented feature path.
- `SparxOslcAmReader.read` follows `oslc:nextPage`, rejects loops and partial collection failures, limits the root query to 20 pages, 16 MiB, and 30 seconds, and recomputes the fingerprint before reviewed apply.
- `OslcTransport.readPcs` enforces repository/owner binding, same-origin path confinement, DNS/network policy, no redirects or automatic retries, five-second response timeouts, a 16 MiB response limit, credential injection at the wire boundary, and credential-reflection rejection. `Response` retains only a sanitized resource URI, ETag, and bytes.
- The current `externalVersion` hashes root-page URI, ETag, and content. It does not include any feature resource.

### Native projection

- `IntegrationDomainAdapter.architectureCommands` emits typed element, relation, and view commands. It ignores `SPECIFICATION` and package `PLACEMENT` items.
- `ArchitectureCommand` has typed element/relation/view operations and `MoveOrGroupElement`. It has no package or requirement-mapping operation.
- `ArchitectureEditorController.WireCommand` exposes only element, relation, move/group, undo, and redo operations.
- `ArchitectureEditorProjection.Schema` exposes element/relation rules only.
- `IntegrationDomainAdapter.relationKey` builds endpoints from accepted `ELEMENT` items only. It cannot address a native requirement or package. It rejects a bidirectional connector, reverses a documented reverse direction, and otherwise applies the strict DSL relation rules.
- `IntegrationService.remap` supports canonical element/relation types and requirement title/text attributes. It has no endpoint or target-semantic mapping.

The current package behavior is evidence preservation, not native editing: an accepted package can update the integration mapping and stored exchange evidence while producing no package command. That behavior is documented, but it cannot satisfy a native package-editing claim. Similarly, a connector to a requirement or package has no faithful native endpoint mapping and fails late or must be rejected.

## Version 2 exchange representation

### Contract change

Add `FEATURE` to `IntegrationContracts.ArtifactKind`. A feature remains an `Artifact`, which avoids adding a second canonical model and lets the existing diff, decision, identity, persistence, and evidence machinery operate at item level.

Use these transport-neutral conventions:

| Meaning | `Artifact` representation |
|---|---|
| Tagged value | `kind=FEATURE`, `type=tagged-value`, `title=name`, `text=value` |
| UML attribute | `kind=FEATURE`, `type=attribute`, `title=name`, `text=description` |
| UML operation | `kind=FEATURE`, `type=operation`, `title=name`, `text=description` |
| Operation parameter | `kind=FEATURE`, `type=parameter`, `title=name`, `text=description` |

Every feature must have:

- a normalized stable GUID in `id`;
- `extensions.owner` containing the owning artifact/feature GUID;
- `extensions.position` containing a non-negative integer when ordering is present;
- documented scalar properties retained in `attributes` using stable names such as `ea:scope`, `ea:classifierName`, `ea:defaultValue`, `ea:lowerBound`, and `ea:paramDirection`;
- linked classifier identity in `extensions.classifier`, after validating its EA identifier;
- any unsupported literal property retained as `attribute:<absolute-predicate-URI>` with `PRESERVED_EXTENSION` loss;
- any unsupported linked or structured property retained as a bounded canonical RDF fragment in `extensions.rdf:<absolute-predicate-URI>` with `PRESERVED_EXTENSION` loss. Do not flatten it to an arbitrary string without a loss.

Preserve duplicate tag names as separate feature objects. Only project a tag into the owner's legacy `tag:<name>` attribute when the name is unique. A duplicate name receives `SPARX_TAG_DUPLICATE_UNMAPPED`; no value wins by order. The reserved unique tags `taxonomy.id`, `taxonomy.mappingProfile`, `taxonomy.elementType`, `taxonomy.relationType`, and `taxonomy.lastSyncRevision` retain their current validation and mapping behavior.

Add `SparxMappingProfile.prefixedGuid(String value, Set<String> allowedPrefixes)`. It must validate and strip exactly one allowed AM prefix before delegating to `guid`; it must not accept a connector GUID as an attribute GUID. Keep the original prefixed identifier in `extensions.externalIdentifier` for evidence.

### Shared mapper

Add package-private transport DTOs in `taxonomy-export`:

```java
record SparxResource(String prefixedId, URI uri, String type, String title,
                     String description, String parentPrefixedId,
                     Map<String, Value> properties) {}
record SparxConnector(String prefixedId, String sourcePrefixedId,
                      String targetPrefixedId, String eaType, String direction,
                      String title, String description,
                      Map<String, Value> properties) {}
record SparxFeature(String prefixedId, String ownerPrefixedId,
                    FeatureKind kind, int position, String title, String text,
                    Map<String, Value> properties) {}
enum FeatureKind { TAGGED_VALUE, ATTRIBUTE, OPERATION, PARAMETER }
```

Add `SparxSemanticExchangeAssembler.assemble(...)`. It alone should apply `SparxMappingProfile`, normalize direction, create `Artifact`/`Relation`/`Placement` values, validate ownership and identity, and create losses. `SparxXmiReader` and `SparxOslcAmCodec` should parse transport syntax into these DTOs and call the assembler. This prevents AM and XMI from assigning different canonical meanings to the same EA connector or feature.

Version 2 XMI must parse and write the bounded `ownedAttribute`, `ownedOperation`, and `ownedParameter` subset plus EA tag extensions into the same feature artifacts. Unsupported UML children remain explicit losses. Because actual EA XMI has not been exercised, tests prove only deterministic codec meaning and round-trip within the declared fixture profile.

`SparxModelValidator.model` must additionally verify:

- each feature owner exists and has an allowed owner kind;
- tags may belong to package, element, attribute, or operation;
- attributes and operations belong to elements;
- parameters belong to operations;
- feature GUIDs do not collide with object, relation, model, or other feature GUIDs;
- positions are bounded and unique within an owner/kind when supplied;
- total artifacts, relations, and features stay within the aggregate item limit;
- an accepted unsupported feature cannot be exported unless it is represented losslessly by the v2 writer or explicitly preserved-only.

## Bounded AM collection algorithm

### Read plan

Refactor `SparxOslcAmReader` around these methods:

```java
ExchangeDocument read(RepositoryContext context, Connection connection,
                      String resource, String expectedVersion)
List<Response> collectChain(ReadBudget budget, Connection connection,
                            URI first, EndpointKind kind)
List<FeatureRequest> planFeatures(ResourceIndex roots)
URI validateRoot(...)
URI validateFeature(...)
```

`ReadBudget` is one object shared by discovery, root query pages, and every feature page. Use concrete limits:

| Bound | Value | Failure |
|---|---:|---|
| root resources eligible for enrichment | 250 | `SPARX_AM_ENRICHMENT_LIMIT` |
| pages in any one collection chain | 20 | `SPARX_AM_PAGE_LIMIT` |
| HTTP responses across the whole read | 1,024 | `SPARX_AM_REQUEST_LIMIT` |
| aggregate response bytes, including discovery | 16 MiB | `REMOTE_RESPONSE_LIMIT` |
| aggregate RDF statements | 100,000 | `ITEM_LIMIT` |
| root objects + connectors + feature objects | 10,000 | `ITEM_LIMIT` |
| properties on one resource/feature | 128 | `SPARX_AM_PROPERTY_LIMIT` |
| stereotypes on one object | 32 | `SPARX_AM_PROPERTY_LIMIT` |
| total collection wall clock | 30 seconds | `REMOTE_TIMEOUT` |

The 250-resource enrichment bound is intentionally lower than the generic 10,000-item exchange bound because one element can cause several HTTP requests. Crossing it must fail the whole fetch with a visible scope-limit code; do not return package/element data with an “unfetched features” warning and call that complete.

After the root resources are validated and sorted by normalized GUID, construct requests in deterministic order:

1. `linkedresources/<pk_|el_...>/` for every package and element;
2. `taggedvalues/<pk_|el_...>/` for every package and element;
3. `attributes/<el_...>/` and `operations/<el_...>/` for every element;
4. `taggedvalues/<at_...>/` for every returned attribute;
5. `taggedvalues/<op_...>/` and `parameters/<op_...>/` for every returned operation.

Each endpoint is a collection and must use the same `oslc:nextPage` rules as the root query. A loop, malformed next link, failed later page, limit breach, or timeout fails the entire operation and leaves no partial preview. Process sequentially in v2; the fixed budgets matter more than speculative concurrency and make request evidence deterministic.

### URI and credential boundary

Keep the public/user-selectable scope limited to `sp/` and unfiltered `qc/`. Add an internal `EndpointKind` validator with exact path grammars beneath the configured `/oslc/am/` boundary:

```text
linkedresources/(pk|el)_{UUID}/
taggedvalues/(pk|el|at|op)_{UUID}/
attributes/el_{UUID}/
operations/el_{UUID}/
parameters/op_{UUID}/
```

Only `oslc.paging`, `oslc.pageSize`, `page`, and `pageToken` may appear on a collection or next-page URI. Continue to reject `useridentifier` supplied by a response or user, user info, fragments, encoded path traversal/separators, host/scheme/port changes, redirects, and configuration contexts for this PCS profile. Build first-page feature URIs from validated IDs; do not follow `ss:features`, `ss:nestedresources`, or other arbitrary feature links.

`OslcTransport.readPcs` remains the only code that appends the session token. Durable resource identities, errors, metadata, and hashes use its credential-free `Response.resource`. Apply credential-reflection checks to every feature response and ETag.

### Version evidence

Replace the current root-page-only string builder with a `SparxAmReadEvidence` accumulator. For every successful sanitized response, append:

```text
endpoint kind
owner GUID or ROOT
credential-free normalized URI
ETag or the literal <none>
SHA-256 of response bytes
```

Sort records by endpoint kind, owner, and page URI, then hash the complete canonical record list into `ExchangeDocument.externalVersion`. Store only bounded counts in metadata (`resourceCount`, `connectorCount`, `featureCount`, `responseCount`, `byteCount`) and keep `collectionEvidence=EXHAUSTED_COLLECTIONS_NOT_ATOMIC_OR_AUTHORITATIVE`.

Reviewed apply must repeat the identical plan and compare the complete v2 `externalVersion`. This detects changed tags/connectors/features between preview and apply. It still is not an atomic PCS snapshot, does not authorize deletion, and does not justify `completeScope=true`.

## Connector mapping

Parse the documented link shape as a pair: the source `am:Resource` has a dynamic `ss:<connector-type>` predicate to the target resource URI, and the associated description supplies the `lt_` connector identifier, title, description, and direction. Require:

- one source identifier;
- one connector identifier with `lt_` prefix;
- one same-boundary target `/resource/(pk|el)_{UUID}/` URI;
- a supported direction value;
- no duplicate connector GUID.

Use the same `SparxMappingProfile.relationType` and direction normalization as XMI. A connector with both endpoints in the fetched root index becomes a canonical `Relation`. A connector whose endpoint lies outside the root index becomes a reviewable `FEATURE` of type `external-connector`, retaining the target GUID and properties, with `SPARX_AM_ENDPOINT_OUTSIDE_SCOPE` and `PRESERVED_EXTENSION`. It must not be treated as a deletion candidate or a native relation.

If both endpoint collection calls report the same connector, de-duplicate only by identical GUID and identical canonical fields. Conflicting duplicate representations fail `DUPLICATE_IDENTITY`; first-wins would hide a model inconsistency.

## Native package editing

Do not reuse `MoveOrGroupElement` or a `CONTAINS` relation for EA packages. `CONTAINS` is a typed architecture relation with a restricted `SY/CM` matrix, while a package is a neutral organization boundary.

Add a transport-neutral package construct to the architecture DSL/model:

```java
record ArchitecturePackage(String id, String title, String description,
                           String parentId, int position,
                           Map<String, String> extensions) {}
```

Add these sealed `ArchitectureCommand` variants (the binding atomic-placement clarification above supersedes the individual move/place sketch):

```java
CreateArchitecturePackage(String id, Map<String,String> properties)
UpdateArchitecturePackage(String id, Map<String,String> properties)
MoveArchitecturePackage(String id, String parentPackageId, int position)
PlaceArchitectureElement(String elementId, String packageId, int position)
DeleteArchitecturePackage(String id)
```

Required follow-through:

- `ArchitectureDslCommands.apply` must validate package IDs, one parent, maximum depth 80, acyclicity, unique sibling position, and delete dependencies.
- `CanonicalArchitectureModel` and `AstToModelMapper` must expose packages without including them in `getElements()` or the relation type matrix.
- `DslValidator` must validate package hierarchy separately from semantic relations.
- `ArchitectureEditorController.WireCommand` must add explicit `CREATE_PACKAGE`, `UPDATE_PACKAGE`, `MOVE_PACKAGE`, `PLACE_ELEMENT`, and `DELETE_PACKAGE` cases with the same exact semantic revision precondition.
- `ArchitectureEditorProjection.Schema/View` must expose packages as a hierarchy/editor surface, not as graph nodes of type `System`.
- `IntegrationDomainAdapter.snapshot` and `exportDocument` must include native packages and placements using their durable integration mappings.
- `IntegrationDomainAdapter.architectureCommands` must emit package create/update first, then moves/element placements, then dependency-safe deletes. A package rename/move preserves its native ID and EA GUID mapping.

Package placement for a portfolio requirement has no current native home. Preserve it as exchange evidence with `SPARX_REQUIREMENT_PACKAGE_PRESERVED_ONLY`; do not create a fake architecture element or package membership.

## Requirement and connector endpoint mapping

`IntegrationDomainAdapter.architectureCommands` currently constructs its endpoint table only from accepted `ELEMENT` artifacts. Replace that map with an `EndpointIndex` built from accepted element mappings, package mappings, and deterministic requirement apply plans:

```java
record EndpointRef(EndpointKind kind, String businessIdentity, Long requirementId) {}
enum EndpointKind { ARCHITECTURE_ELEMENT, REQUIREMENT, PACKAGE }
```

Add a pure `IntegrationPortfolioPort.planRequirementApply(...)` that returns the stable requirement key/business identity for both existing and new requirements without mutating storage. `IntegrationService.apply` must create all requirement plans, build and validate the complete package/element/relation/mapping command list, and only then execute requirement writes and the already-validated architecture command batch in the current transactional boundary. This avoids creating a requirement before discovering that its connector is invalid.

Do not pass requirement connectors through the general architecture relation matrix. Add typed mapping commands:

```java
UpsertRequirementMapping(String requirementIdentity, String elementId,
                         String rationale, Map<String,String> exchangeProperties)
DeleteRequirementMapping(String requirementIdentity, String elementId)
```

These commands update the existing canonical `RequirementMapping` DSL concept and validate that one endpoint is a requirement and the other is an architecture element. They do not introduce a new unrestricted relation type.

Extend review data without overloading `MappingOverride.canonicalType`:

```java
enum RelationProjection { ARCHITECTURE_RELATION, REQUIREMENT_MAPPING, PRESERVE_ONLY }
record EndpointOverride(String sourceInternalIdentity,
                        String targetInternalIdentity,
                        RelationProjection projection,
                        String canonicalType) {}
```

Add `Map<String, EndpointOverride> endpoints` to `ReviewedChangeSet` with a backward-compatible constructor for old JSON. Rules:

- element-to-element may use `ARCHITECTURE_RELATION`, but `canonicalType` must pass the existing `DslValidator.relationTypeRules`; do not loosen the matrix;
- requirement-to-element may use `REQUIREMENT_MAPPING` only after an explicit review choice;
- package endpoints, requirement-to-requirement endpoints, bidirectional connectors, and endpoints outside scope default to `PRESERVE_ONLY` or rejection;
- an override identifies durable native identities, never display names;
- direction reversal occurs before endpoint-kind validation;
- no connector maps silently to `RELATED_TO` or `CONTAINS`.

Change `IntegrationDomainAdapter.relationKey` to accept `EndpointIndex` and `EndpointOverride`, and return either a typed `RelationKey`, a typed requirement-mapping plan, or a precise `IntegrationProblem` (`SPARX_ENDPOINT_KIND_UNMAPPED`, `SPARX_ENDPOINT_MAPPING_REQUIRED`, `SPARX_DIRECTION_UNMAPPED`, or the existing native type-rule code). Preview/UI should expose these codes before apply.

## Exact code changes by class

| Class/interface | Change |
|---|---|
| `IntegrationContracts` | add `ArtifactKind.FEATURE`, `RelationProjection`, `EndpointOverride`, and reviewed endpoint mappings |
| `ExchangeItems` | flatten/expand/merge feature artifacts normally; include `owner`, position, and preserved properties in comparison |
| `SparxMappingProfile` | add prefix-specific GUID validation; declare v2 feature/type mappings; retain immutable v1 behavior |
| new `SparxSemanticExchangeAssembler` | common AM/XMI semantic construction and loss policy |
| `SparxOslcAmCodec` | split root/connector/feature parsers; parse linked values and feature collections; remove blanket v2 feature loss |
| `SparxOslcAmReader` | add deterministic feature read plan, reusable paging, one aggregate budget, and complete read evidence |
| `OslcTransport` | keep security policy; optionally expose a package-private PCS GET accepting only a prevalidated `URI` and endpoint kind so feature paths cannot use the general query allowlist |
| `SparxXmiReader` / `SparxXmiWriter` | parse/write the same feature artifacts; retain explicit losses for unsupported UML/EA constructs |
| `SparxModelValidator` | validate feature ownership, identity, order, bounds, and round-trip disposition |
| `SparxSnapshots` | assign stable internal identities to feature artifacts as it already does for artifacts; reserve removed feature identities |
| `IntegrationDomainAdapter` | native package snapshot/commands; endpoint index; typed requirement mapping; no package/requirement coercion |
| `IntegrationPortfolioPort` | pure requirement apply plan plus execute step |
| `ArchitectureCommand` / `ArchitectureDslCommands` | typed package and requirement-mapping commands with strict validation |
| `CanonicalArchitectureModel`, `AstToModelMapper`, `DslValidator` | separate package hierarchy and requirement-mapping validation |
| `ArchitectureEditorController` / `ArchitectureEditorProjection` | exact package editing API/schema; endpoint remap payload and choices |
| `IntegrationService.select/apply/remap` | validate endpoint mappings, build complete plans before mutation, and persist reviewed mapping choices |

## Required tests

### `taxonomy-export`

Extend `SparxOslcAmCodecTest` with:

- `mapsConnectorsTagsAttributesOperationsAndParametersToVersion2Meaning`;
- `duplicateTagNamesArePreservedAndNeverFirstWins`;
- `outsideScopeConnectorIsPreservedWithExplicitLoss`;
- `conflictingDuplicateConnectorRepresentationsFailClosed`;
- `wrongFeatureGuidPrefixOwnerOrStructuredPropertyFailsOrReportsExactLoss`;
- `featurePageChainMustBeComplete`.

Extend `SparxXmiCodecTest` with:

- `xmiAndAmFixturesProduceTheSameConnectorAndFeatureMeaning`;
- `attributesOperationsParametersAndTagsRoundTripDeterministically`;
- `featureOwnerIdentityAndPositionSurviveRenameAndMove`;
- `unsupportedFeatureFieldsHavePreservedOrUnsupportedDisposition`;
- `v1AndV2ProfilesCannotBeInterpretedInterchangeably`.

### `taxonomy-interop`

Extend `SparxOslcAmReaderTest` with real local HTTP contract cases for:

- pagination on root and feature collections;
- failure on a later connector/tag/attribute/operation page returns no preview;
- global byte, response, item, root-enrichment, and deadline limits;
- feature paging loop and same-origin/path escape rejection;
- `useridentifier` reflection in a feature response/ETag;
- a changed feature with unchanged root pages causes `REMOTE_STALE`;
- the Authorization/query token is present on every request and absent from persisted evidence;
- deterministic evidence independent of server response ordering.

Extend `SparxProjectionTest` with:

- package create/rename/move/delete emits typed package commands and never an element/`CONTAINS` coercion;
- element placement in a package is separate from semantic containment;
- element-to-element connector still obeys the current relation matrix;
- requirement-to-element connector requires and then emits `UpsertRequirementMapping`;
- package, requirement-to-requirement, bidirectional, and external endpoints cannot become native relations without an allowed explicit projection;
- rejected/preserved-only features remain in evidence and mappings without a native command.

Extend `SparxIntegrationFlowTest` with:

- package and requirement mapping apply/retry are idempotent across restart;
- invalid connector plans cause no requirement, package, element, relation, or mapping mutation;
- v2 external version includes all feature responses and is re-read before apply;
- `completeScope` remains false and missing roots/features never infer deletion;
- mapping-profile v1 connections remain frozen and v2 requires a new/migrated connection.

Add focused DSL/editor tests for package hierarchy cycles, delete dependencies, exact revision preconditions, requirement-mapping endpoint kinds, undo/redo, and projection schema. Do not add tests that claim an EA or PCS version was exercised.

## Critical risks

1. **Documented schema versus actual PCS output.** The official pages define endpoints and update examples, but no real product response has been captured. RDF aliases, collection wrapping, feature pagination, and ETag behavior may differ. Keep compatibility `NOT_EXECUTED`.
2. **Profile migration.** Feature objects change persisted identities and diffs. Reinterpreting `@1` in place would generate false additions/removals; immutable `@2` profiles and explicit migration are mandatory.
3. **Feature fan-out.** Even a modest model can exceed 1,024 calls or 16 MiB. Fail the whole enriched read with a scope-limit code; never return a partially enriched model as complete.
4. **XMI dialect uncertainty.** A deterministic v2 XMI fixture proves shared internal meaning only. EA import/export preservation still needs a real-product round trip.
5. **Package model breadth.** Adding native packages touches parsing, serialization, editor projection, dependency checks, undo/redo, and exports. Keeping packages distinct from semantic elements prevents wider corruption but is a material DSL change.
6. **Requirement transaction ordering.** Requirement creation currently occurs after command planning but lacks a pure planned endpoint identity. The plan/execute split is required to prevent partial native mutation when connector validation fails.
7. **Relation semantics.** Package and requirement endpoints cannot safely reuse the element relation matrix. Explicit projection choices and typed requirement mappings are necessary; a generic fallback would satisfy transport shape while corrupting domain meaning.
