# ONNX mixed-provider startup: CI regression follow-up

PR #1319 removes the accidental dependency of automatic semantic indexing on
`LLM_PROVIDER=LOCAL_ONNX`. `CUSTOM_OPENAI` and enabled local embeddings must remain
usable together. Do not restore the provider guard to make unrelated tests pass.

## Connection budget

The default mass indexer processes one entity type with two object loaders and an
identifier reader. Those background transactions share the application's JDBC
pool with foreground workspace operations and nested JGit storage transactions.
Both HSQLDB profiles now default to **8 maximum connections**, retaining one
minimum idle connection and the existing 30-second acquisition timeout.
`TAXONOMY_DB_MAX_POOL_SIZE` still takes precedence. An existing explicit value of
`4` is not automatically changed by upgrading; review it when enabling ONNX.
External database profiles and operator-defined pools are not changed here.

The default leaves headroom for foreground work; it is not a guarantee for an
unbounded request load. Increasing `TAXONOMY_EMBEDDING_INDEX_LOADER_THREADS`
requires reviewing the total connection budget again. See the upstream
[Hibernate Search mass-indexer tuning guidance](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/#indexing-massindexer-tuning).

`EmbeddingConnectionBudgetTest` exercises the shipped profile settings with real
HikariCP, HSQLDB (memory and file), Hibernate ORM and Hibernate Search/Lucene. A
latch replaces slow embedding computation, holding the real index-loading
connections while three nested foreground connections perform reads and a write.
After releasing the latch, all 4,096 authored fixture documents must be indexed
and the foreground write must remain committed. The fixture is outside the
application's entity-scan package. No external provider or model is contacted.

## Test isolation

`EmbeddingLifecycleSafetyTest` uses a thread-scoped static bean lookup mock. It
must not replace the process-wide `SpringContextHolder` while background indexers
are active. Its exact invocation check is retained, and the negative inference
check targets the actual `embedDocument` method.

## Verification boundaries

The resource scenario was executed directly using Java 21 and the dependency
jars from the original PR's commit-bound application artifact. Both profiles
failed with the original four-connection defaults (pool exhaustion); both passed
with eight connections, including complete document counts and retained writes.
This is a focused resource regression, **not** a full Java 25 reactor/JUnit pass.
The JUnit wrapper makes the same scenario part of ordinary Maven verification.

Focused repository command (requires the repository's Java 25 toolchain):

```bash
./mvnw -B -pl taxonomy-app -am \
  -Dtest=EmbeddingConnectionBudgetTest,EmbeddingLifecycleSafetyTest,LocalOnnxIndexInitializerTest,SearchIndexCoverageTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The authoritative command remains:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

The previous `ScenarioArchiMateImportTest` exceeded its unchanged 30-second
import deadline. Its result must be checked in the new full CI run; the focused
connection test does not prove that this separate performance assertion passes.
No test selections, existing deadlines, native-ONNX checks or readiness
assertions are relaxed by this follow-up.
