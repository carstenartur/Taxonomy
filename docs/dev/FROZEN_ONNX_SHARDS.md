# Frozen ONNX worker scoring

`LOCAL_ONNX` can score a root using immutable candidate text and an in-memory
vector cache. It needs no Hibernate Search index, mutable relation entity,
current catalogue query or other root on the worker. The full-catalogue local
path retains its existing transactional Lucene search.

## Admission and execution

For ONNX admission, capture all eight catalogue roots **once**, using
`CatalogueSnapshotService.captureRoots`. Pass that already atomic capture to:

```java
List<RootCatalogueSnapshot> selected = frozenEmbeddings.decorate(
        repositoryContext, sourceIdentity, allEightCapturedRoots, requiredRoots);
```

`FrozenCatalogueEmbeddingService` is a Spring service with constructor
`(RelationProjectionReadService, LocalEmbeddingService)`. It verifies exact
repository, workspace and branch equality, then reads the authorized relation
identity projection before and after preparation. Both reads must be
`PROJECTION`, `READY`, and at the operation's exact source commit. A missing,
stale, moved or legacy-fallback projection fails admission. An explicitly empty,
complete projection at that commit is valid. An absent branch does not prove an
empty relation set and is rejected.

Relation endpoints must exist in the single captured catalogue generation.
Their names come from those immutable scalar records. The decorator returns
only `requiredRoots`; additional endpoint roots are temporary admission inputs.
Worker execution still binds only its persisted task root. The source commit
identifies the architecture relations; the separate catalogue provenance
identifies the official workbook generation and is not replaced by the commit.

Snapshots retain optional `RootEmbeddingSnapshot` evidence with complete
per-node text, relation commit, text algorithm version, and model/config identity.
Within a bound root, `LocalEmbeddingService.validateFrozenModel()` must run
before traversal. `LlmService` then selects the unannotated
`scoreFrozenNodes` path. Both validation and inference reject an active database
transaction. Missing text, foreign candidates, unavailable inference and model
mismatch throw; the analysis root flow records explicit partial/failure evidence
without inventing score zero.

Admission and scoring do not gain generative relation/reformulation capabilities:
LOCAL_ONNX remains an embedding provider.

## Text and score compatibility

The document text uses the existing `NodeEmbeddingBinder` formatter: English
name and description, outgoing relation type plus target name, and incoming
relation type plus source name. The new text version
`node-binder:relations-source-type-target:v1` sorts the authorized relation
identities by source code, type name and target code before formatting. This
replaces the global JPA association order, which was unspecified and could mix
unrelated repository/workspace projections. A worker never reads those
associations, and there is no scalar-only fallback that drops relation text.

For the same frozen text and vectors, scoring preserves the old filtered
`KNN(k = candidate count)` result: Lucene's `VectorSimilarityFunction.COSINE`
followed by `round((2 * score - 1) * 100)` and clamping to 0–100. Root relevance,
child budget normalization, product thresholds and query-prefix behavior remain
owned by the existing analysis service. The fixed fixture compares the actual
Lucene filtered KNN query and frozen scorer; it is not a claim that an unsafe
historic global vector matches a newly authorized source projection.

Model identity hashes the actual loaded `model.onnx`, `tokenizer.json`, and
every regular companion file in the self-contained model directory, including
`serving.properties`, tokenizer configuration and external weight files.
Deploy the same immutable artifact bundle on admission and worker instances;
external weights must remain inside that bundle. It also records query prefix,
explicit CLS pooling/unit normalization/token-type/dimension/scoring version,
DJL/tokenizer/Lucene versions and the loaded native runtime version. Paths and
configured URLs are not artifact identities. Artifacts are checked before and
after loading; changes fail closed. Workers must match the admitted identity.

The per-service LRU cache key contains all four source-authority fields, root,
model/config identity, text algorithm version, node code and text digest.
`embedding.frozen.cache.max-vectors` defaults to 8192 (allowed 1–100000). Eviction
only triggers recomputation. The cache stores no query result, live entity or
relation projection, and clears on model shutdown. Only explicitly requested
candidate vectors are computed. Global semantic-search APIs remain unavailable
inside a frozen task.

## Measured worker data footprint

`FrozenEmbeddingFootprintTest` launches a fresh JVM for each single root and for
all eight roots. It reads only that process's input snapshots, binds the real
frozen view, and fills the production candidate cache. It uses the production
workbook parser and overlay, deterministic 384-dimensional fixture vectors and
an explicitly empty fixture relation set. No model or provider is downloaded.

The 2026-10-04 run used JDK 21.0.2, `-Xms128m -Xmx256m -XX:+UseSerialGC`.
Heap is retained Java heap after three explicit GCs, relative to a warmed
serialization/service baseline. The cache column is the exact stored float
payload; Java cache/key overhead is included in heap. Times include binding and
populating fixture vectors. The measured data stage excludes Spring, broker,
database connections, native ONNX model memory and process RSS. It establishes
root-dependent data retention, not Kubernetes memory requests or production
latency guarantees.

| Scope | Nodes/vectors | Snapshot heap delta (bytes) | Populated heap delta (bytes) | Vector payload (bytes) | Bind/populate (ms) |
| --- | ---: | ---: | ---: | ---: | ---: |
| BP | 409 | 1514696 | 2708560 | 628224 | 397 |
| BR | 307 | 889112 | 1903600 | 471552 | 360 |
| CP | 34 | 429120 | 966896 | 52224 | 317 |
| CI | 131 | 573144 | 1280672 | 201216 | 326 |
| CO | 92 | 546984 | 1185704 | 141312 | 318 |
| CR | 151 | 663432 | 1405680 | 231936 | 338 |
| IP | 1072 | 2126440 | 4476328 | 1646592 | 528 |
| UA | 376 | 975344 | 2109848 | 577536 | 391 |
| All eight | 2572 | 5173056 | 10141400 | 3950592 | 524 |

[Retained machine-readable evidence](../testing/evidence/frozen-embedding-footprint-2026-10-04.json)
includes workbook and overlay digests and every JVM result. Regeneration writes
`taxonomy-knowledge/target/frozen-embedding-footprint/evidence.json`.

## Verification

Focused deterministic checks, including the fresh JVM probe:

```sh
./mvnw -B -pl taxonomy-analysis -am \
  '-Dtest=EmbeddingModelIdentityTest,FrozenLocalEmbeddingTest,FrozenCatalogueEmbeddingServiceTest,FrozenEmbeddingFootprintTest,RootScoringFlowTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The tests cover real Lucene score equivalence, concurrent CP/IP roots with
overlapping codes, each source-authority dimension, model/config changes,
bounded eviction, missing evidence, inference failure, canonical cross-root
relation text, projection drift, and legacy fallback rejection. They do not
invoke a remote provider.

`FrozenLocalOnnxModelTest` belongs to the existing `onnx` tag and compares actual
pinned-model vectors with Lucene. It requires the existing model provisioning
lane and is excluded from ordinary tests. The integrated 2026-10-04 run downloaded
the pinned BAAI artifact through the existing SHA-256-verified provisioner and
executed this test successfully with native ONNX Runtime on CPU. This proves
actual-model score equivalence for the frozen fixture; the footprint experiment
above still excludes native model and whole-process startup memory.
The normal repository CI, browser, external database and broker gates remain
separate acceptance evidence.
