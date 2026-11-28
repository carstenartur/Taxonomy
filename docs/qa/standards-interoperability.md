# Standards interoperability acceptance

Issue [#926](https://github.com/carstenartur/Taxonomy/issues/926) adds a reviewed
standards boundary on the durable editing architecture from #1028. The required
CI lanes verify the current pull-request head; historical evidence below proves
only the recorded source revision and fixture subset.

## Workbench v3 cross-check on 28 November 2026

The Java/Maven tests compare the v3 endpoint table with the pinned Archi 3.2
matrix and retain both canonical and native relationship directions across XML
and diagram round trips. The independent Python EAM checker at revision
`783c4c01753a2d168256bdb245cba58a76d8e275` reported zero findings for a
generated set of 1,500 relationships covering ten source concepts, ten target
concepts and fifteen Taxonomy relationship types. The export explicitly recorded
932 weaker associations in this exhaustive set. Its composition is synthetic
and cannot be used to estimate the frequency of such mappings in user models.

The historical flood fixture contains 50 elements and 36 relationships. Two
canonical `REQUIRES` relationships from capabilities to a data object had an
invalid native `Access` projection in v2. For the same graph in v3 the EAM
checker reports zero matrix errors and the same eighteen isolated-element
warnings. Taxonomy reports the two weaker associations and recovers both typed,
directed requirements on import. [Regression test](../../taxonomy-app/src/test/java/com/taxonomy/interop/CivilianArchiMateExchangeTest.java)

A separately installed Archi 5.11.0 imported and re-exported both the small
workbench example and the flood model. Both retained element and relationship
identities, native directions and the geometry of their two views. The flood
views contained 96 node placements and 72 connection occurrences. Archi folded
whitespace in 351 of the 3,136 flood property values. Exact comparison of those
raw strings fails. Taxonomy's return path identified every change and recovered
the source values when supplied with the original export. The check ran on
generated files and compared diagram data. The results include the product and
file digests and the comparison outcomes. [Recorded results](archimate-v3-archi-5.11-results.json)

## Civic application process

The synthetic German application process exercises both outcomes of the document
check. An incomplete application goes back for more documents. A complete
application proceeds to booking. The regression checks 17 directed relationships
and seven responsibility assignments in the editor projection, checks the
responsibilities in the decision tree, and reads the actual SVG and PDF exports.
The model and scores are fixed so that a changed diagram or report can be traced
to its projection. [DSL fixture](../../taxonomy-app/src/test/resources/scenarios/antragsbearbeitung-v1.taxdsl) · [Regression test](../../taxonomy-app/src/test/java/com/taxonomy/architecture/decision/CivilApplicationProcessProjectionTest.java)

Run the case offline after Maven dependencies are available.

```bash
./mvnw -pl taxonomy-app -am test -Dtest=CivilApplicationProcessProjectionTest -Dsurefire.failIfNoSpecifiedTests=false
```

## Installed-product evidence

The [2026-09-08 product run](https://github.com/carstenartur/Taxonomy/actions/runs/34242073916/job/102116477328)
passed both independently installed products. Its unmodified evidence object and
workflow/head references are retained in
[interoperability-products-2026-09-08.json](interoperability-products-2026-09-08.json).

The tested application is Taxonomy `1.4.0-SNAPSHOT`. PR head
`afc88d37c191b2540c309dc482110f5993c824f5` was tested through GitHub's merge commit
`563705699d2418ecf86ffd1b1d3180e90a9a04ba`; these are deliberately different
identities. The application SHA-256, input/output SHA-256 values and execution
timestamp are in the JSON evidence. Unequal file digests are expected: semantic
comparison is the contract, not XML byte equality.

| Product / profile | Actual execution | Supported comparison / known limits |
|---|---|---|
| StrictDoc 0.29.0 / ReqIF 1.2 profile 1 | Native SDoc export → Taxonomy export → StrictDoc import and export → Taxonomy import | Stable requirement/specification MIDs, title/text, named attributes and hierarchy/relation topology. StrictDoc regenerates declaration, hierarchy and relation identifiers. |
| Archi 5.10.0 / ArchiMate 3.1 profile 1 | Upstream Archi exchange fixture → Taxonomy export → Archi headless import/export → Taxonomy import | Stable elements/views, types, relation endpoints, hierarchy and node geometry. The source fixture also contains preserved unsupported canonical concepts; it does not prove an automatic canonical mapping for those concepts. |

The Archi release archive has a pinned verified upstream digest. StrictDoc is
installed with its exact product version in an isolated application environment;
the resolved product dependency versions are retained with each run. Neither
product lane uses a mocked importer or exporter. The current gate also compares
named Archi properties, connection identities/endpoints and attachment/bendpoint
coordinates. The stronger check observed that Archi 5.10.0 replaces two collinear
attachment points with their midpoint bendpoint. This is an explicit layout
transformation, not lossless layout equivalence: `ARCHI_ATTACHMENTS_TO_BENDPOINT`
records each original/resulting point set. Only this precisely checked transformation
is accepted; unknown geometry changes fail. Taxonomy's own codec retains the
original attachment evidence. Future runs retain these per-connection machine-readable
losses alongside the product result.

## Acceptance ownership

| Contract | Executable evidence |
|---|---|
| Official schemas, independent producer files, safe XML, identity and supported semantic round trips | `ExchangeStandardsTest`, pinned offline ReqIF/ArchiMate schemas and fixture origins |
| Three-way field conflicts, identity reuse, complete-scope deletion and delivered-but-unconfirmed identities | `IntegrationDiffTest` |
| Source-preserving typed architecture import, local property/view changes and explicit layout losses | `IntegrationArchitectureProjectionTest`, existing editor/DSL tests |
| Reviewed requirement copies, exact project revisions, idempotent apply, frozen exports and stable native re-import | `IntegrationFlowTest` |
| Approved OSLC provider discovery/read, authorized stable links after rename, conditional versions and non-disclosure | `IntegrationFlowTest`, `OslcProviderProtocolTest`, `OslcRdfContractTest` |
| Real HTTP authorization, stale resources, rate limits, no redirects, bounded stalled responses and SSRF policy | `OslcTransportTest` |
| Atomic ORM acceptance, rollback, mappings, pending intents and one explicit Git batch checkpoint | `IntegrationJournalTest`, `IntegrationFlowTest` |
| Recovery after process termination during fetch/preview/apply/Git/delivery | `IntegrationRestartTest` starts six separate JVMs against the same durable database |
| Two concurrent HTTP writers, conflict/idempotent retry and mapping/checkpoint reload on real databases | Inherited interoperability case in `AbstractDatabaseContainerIT`, executed for PostgreSQL, SQL Server and Oracle |
| Fresh PostgreSQL schema and adoption of pre-migration installations | `TaxonomySchemaPostgresMigrationIT`, including all five V20 integration tables |
| Actual forms, role restrictions, review/apply/reload/cancel/download, DE/EN, keyboard, narrow viewports and axe | Maven-owned browser shards execute `integration-acceptance.mjs` |
| Independent product import/export | Required `Interoperability product compatibility` job |

The product lane cannot pass if either product fails or if the application artifact
does not match the checked-out source and digest. Its result is required by the
final Maven verification job alongside core and UI evidence. Database compatibility
is a separate required workflow. Draft and
review requirements remain in force; a format fixture or this document cannot
substitute for a successful final-head run and review.

## Version and recovery boundaries

| Action | Durable effect | Git commit |
|---|---|---|
| File preview, remote discovery/read, cancellation | Scoped integration evidence/events | None |
| Link-only acceptance | Authorized identity/link snapshot and checkpoint evidence | None |
| Reviewed import that changes the canonical model | Atomic portfolio/DSL/journal/mapping state plus pending checkpoint intent | One explicit checkpoint for the accepted batch |
| Unchanged import or identical retry | Replayed/unchanged result and retained evidence | No redundant commit |
| Reviewed file export and download | Frozen reviewed bytes and delivered identity bindings; no external acknowledgement | None |
| Ordinary semantic editor command, undo or redo | Durable editor operation and workspace revision under ADR 0005 | None |
| Explicit version/checkpoint, publish, merge, baseline or restore | Existing version application commands under ADR 0005 | According to the explicit version boundary |

This follows the audio-analyzer separation: operational evidence is durable
independently of Git, while meaningful versions are explicit checkpoints. No atomic
transaction across ORM and JGit is claimed. Accepted model data survives a failed
checkpoint; retry uses the frozen intent and stable checkpoint ID. A moved parent
becomes a durable conflict requiring explicit reconciliation.

## Supported delivery limits

File export is a deliberate delivery, not proof that an external server accepted
it. The built-in OSLC adapter is discovery/read/link only. No remote-write,
background synchronization, vendor SDK, XMI/UAF/SysML or universal lossless
metamodel capability is advertised. The small provider exposes currently approved
requirements and selected reachable architecture versions, not a historical
approval archive. See the [DE](../features/standards-interoperability-de.md) and
[EN](../features/standards-interoperability.md) guides for setup and exact subsets.

The local command `./mvnw verify -DexcludedGroups="real-llm"` was attempted, but
the workspace could not resolve the Spring Boot parent POM because Maven hosts
were unavailable through DNS. This is an environment failure before test
execution, not a successful full local run. GitHub's Maven, database, browser,
security and product workflows remain the full verification authority.
