# Frozen catalogue reads for analysis workers

`CatalogueSnapshotService` in `taxonomy-knowledge` supplies the catalogue boundary
for durable analysis tasks. It has no analysis-domain, JMS or Artemis dependency.

The package dependency baseline records three explicit entry points: analysis
scoring reads `catalog.snapshot` to select the bound candidate scorer; architecture
metadata recomputation uses `catalog.provenance` to join the catalogue mutation
gate; and frozen embedding admission accepts the existing workspace
`RepositoryContext` to validate exact source authority. Each adds one class
dependency in an existing module direction. The catalogue-owned
`NodeEmbeddingText` formatter is shared with the search binder, preserving its
exact text without a reverse catalogue-to-search dependency. Generic cycle rules,
dependency-count checks and architecture test selectors remain unchanged.

At admission, call `captureRoots(source, requiredRoots)` **once** for the entire
operation input and persist each returned `RootCatalogueSnapshot` with the
operation's immutable requirement. Include all eight target roots when relation
search requires them. `captureRoot` is only a singleton convenience; a loop of
separate calls does not establish a common generation.

Two distinct provenances are recorded. `source` identifies the authorized
architecture repository/workspace/branch/Git commit. `catalogueProvenance`
identifies the shared official catalogue actually read: the retained source
journal revision UUID/time, workbook/overlay/relation-input SHA-256 digests,
lengths, and explicit input-use status. An architecture Git commit does **not**
select a historical official workbook. The caller must authorize that operation
scope, reject architecture-source drift before admission, and durably associate
both provenances; attaching an identity is not an authorization grant.

Capture uses a fresh `READ_COMMITTED` transaction and holds the source journal's
database row gate until all requested roots have been copied. Catalogue startup,
overlay reconciliation, forced reload and derived graph metadata recomputation
acquire the same gate **before** any
catalogue row reads or writes. The row-version write and refresh avoid stale
pre-lock snapshots on MVCC databases; batched entity-manager clears do not release
the database lock. Neither provider calls nor worker computation run under it.
New catalogue import paths must use `CatalogueSourceJournal.lockForMutation()`
inside their mutation transaction before accessing catalogue rows.

Missing journal generations fail closed. A coordinator's loaded overlay must
match the retained database overlay digest and enabled/disabled state, so a
rolling deployment cannot silently combine one pod's overlay metadata with
another import's rows. Legacy workbook or relation bytes that were not retained
remain explicitly `NOT_RETAINED`; their current configured files are never
substituted as original evidence. Schema version 2 requires catalogue provenance;
unprovenanced version-1 snapshots are rejected.

At execution, load only the required persisted roots and bind them on the same
thread that calls the scorer:

```java
try (var ignored = catalogueSnapshots.bind(expectedSource, requiredRoots, snapshots)) {
    // Existing TaxonomyService and CatalogueOverlayService scoring reads.
}
```

`CatalogueSourceIdentity` contains `repositoryId`, `workspaceId`, `branch`, and
`sourceCommit`, preserving nullable compatibility values without substitution.
`bind` requires exact identity equality, an exact required-root set, no duplicate
node identities, a complete rooted hierarchy, and matching catalogue-generation
and overlay provenance across every bound root.
Workers additionally validate the required roots against
`taxonomy.analysis.worker.shards`. Unknown nodes and missing, foreign or
unconfigured roots throw before any current-catalogue fallback.

The schema-versioned JSON record retains scalar catalogue identity, text, parent
codes, source provenance, level and source sort order, plus overlay roles,
classification metadata and whether the parent was patched. Collections are
immutable. Existing entity-shaped read APIs return detached copies; changing a
returned object cannot change the persisted evidence or a later read. Assessment
descriptions re-resolve candidates against the bound record. Overlay parent
patches therefore keep their existing boundary against semantic inheritance.

Bindings are thread-confined, reject nesting and clear on `close`, including
provider failures. They are deliberately not inherited by executor threads.
Every delivery must validate and bind its own input. There is no shared catalogue
snapshot cache: overlapping node identifiers from separate sources cannot reuse
one another's catalogue data. Frozen ONNX candidate vectors have a separate bounded
cache keyed by source, root, model/config identity, node code and text digest.

## Runtime roles and supported search behavior

`taxonomy.analysis.runtime-role=all` remains the default. `all` and `coordinator`
retain existing catalogue initialization and local search behavior.

With `taxonomy.analysis.runtime-role=worker`, catalogue initialization reports
readiness for bound task inputs without querying or importing the global
catalogue. The local ONNX initializer does not load a model or build indexes.
A worker-specific Hibernate customizer disables Hibernate Search before it opens
unrelated Lucene projections. Unbound catalogue reads on that worker fail.
Catalogue readiness does not establish broker or provider readiness; deployment
probes must separately check those dependencies.

Full-text, semantic-search and graph APIs retain their global projections and
reject frozen/worker reads. Local ONNX analysis scoring has a separate exact-source
candidate path described in [Frozen ONNX worker scoring](FROZEN_ONNX_SHARDS.md).
Admission must persist its proven relation-enriched texts and actual model/config
identity. Workers validate that evidence before scoring; they never query the
current global index or reconstruct text from mutable relation associations.

Snapshots intentionally exclude incoming/outgoing relation entity collections
and vectors. Those collections are mutable, tenant-specific projections and must
be loaded separately from exact-source durable relation evidence. A frozen
`getFullTree()` is a catalogue scoring tree with empty relation collections.
The non-frozen local tree behavior is unchanged.

## Measured scalar footprint

`CatalogueSnapshotFootprintTest` reuses the production workbook parser and overlay
against the checked-in `C3_Taxonomy_Catalogue_25AUG2025.xlsx` and writes
`taxonomy-knowledge/target/catalogue-snapshot-footprint.json`. The 2026-10-04 run
measured the following schema-2 UTF-8 JSON payloads before persistence; database
IDs are null and the source-journal identity is a deterministic fixture.

| Scope | Nodes | JSON bytes |
| --- | ---: | ---: |
| All eight roots | 2,572 | 2,547,691 |
| BP | 409 | 499,206 |
| BR | 307 | 281,450 |
| CP | 34 | 37,145 |
| CI | 131 | 115,692 |
| CO | 92 | 99,024 |
| CR | 151 | 158,448 |
| IP | 1,072 | 1,022,809 |
| UA | 376 | 333,908 |

The full payload is a JSON array of root records; each individual measurement is
one root record. The workbook SHA-256 is
`6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130`;
the overlay SHA-256 is
`f82b7d614d00700760bb009ece94949080148d244974b2363037bed3d3b10d3e`.
These are scalar snapshot measurements before the optional embedding component
was added, not startup heap, process RSS, Lucene index size or Kubernetes resource
guarantees. The [frozen ONNX measurements](FROZEN_ONNX_SHARDS.md#measured-worker-data-footprint)
now include fresh-JVM retained heap and candidate-vector storage. Full pod and native
model memory remain separate deployment measurements.

Run the focused regressions with the repository Maven wrapper:

```sh
./mvnw -B -pl taxonomy-knowledge -am \
  '-Dtest=CatalogueSnapshot*Test,CatalogueWorkerIsolationTest,CatalogueOverlayServiceTest,LocalOnnxIndexInitializerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The tests cover root-only capture, JSON round trips, concurrent CP/IP sources with
overlapping identifiers, all four authority dimensions, missing/configuration
failures, immutable assessment context, scope cleanup, worker startup/index
isolation and preservation of local behavior. `CatalogueSnapshotConsistencyTest`
uses an actual HSQLDB MVCC database, JPA repositories, retained source journal and
Spring transaction interception. It overlaps an eight-root capture with an
import, verifies startup reconciliation takes the gate before reading rows, and
rejects missing generation/overlay drift. The overlay publication regression
holds index construction mid-flight and verifies concurrent readers cannot see
its digest with incomplete metadata. `DerivedMetadataCatalogueConsistencyTest`
reproduces a stale full-row ORM update overwriting a newer import, and verifies
metadata recomputation takes the gate before relation traversal can load nodes.
These tests do not replace the external
database matrix, broker, browser or constrained-cluster acceptance gates.
