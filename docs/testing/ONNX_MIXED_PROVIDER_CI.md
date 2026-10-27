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

The application tests rebuild a shared in-memory HSQLDB schema when loading a
new Spring context. Surefire already restricted the context cache to one entry,
but Failsafe did not inherit that plugin-specific system property. A context
switch in Failsafe therefore only paused the previous context; its JDBC pool,
JPA factory and unfinished indexing could remain alive during schema recreation.

The test-only `spring.properties` now supplies the same one-context policy to
Surefire, Failsafe and IDE test runs. Spring's context cache closes the evicted
context **before** calling the replacement context's loader. The setting is not
part of the production application, does not disable embeddings or context
pausing, and does not change any test selection or deadline.

`SpringContextCacheIsolationIT` uses the integration-test JVM's unmodified
`DefaultContextCache`, two real Spring contexts and a real Hikari/HSQLDB database.
Its replacement loader requires the old context and pool to be closed before
recreating the schema. No explicit cache-size constructor or mocked lifecycle can
hide missing Failsafe configuration. See Spring's
[context caching](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)
and [context pausing](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/context-pausing.html)
documentation for the distinction between eviction and pausing.

## Verification boundaries

The initial connection-budget scenario was executed directly using Java 21 and
the dependency jars from the original PR's commit-bound application artifact.
Both profiles failed with the original four-connection defaults and passed with
eight connections, including complete document counts and retained writes.

The subsequent Java 25 [CI run 40564106590](https://github.com/carstenartur/Taxonomy/actions/runs/40564106590)
on head `1911c782e9220de4f8bc38dd1b32aed5b66da512` confirms:

- All five previously failing test classes completed without failures:
  `ArchitectureReportTests`, `ReformulationEvidenceRoundTripTest`,
  `AdoptedLineageRealReanalysisTest`, `EmbeddingLifecycleSafetyTest` and
  `ScenarioArchiMateImportTest`.
- The measured ArchiMate apply operation took **29,016 ms**, passing its unchanged
  30-second assertion. This single result is not a performance guarantee.
- Both `EmbeddingConnectionBudgetTest` cases passed, and all nine
  `LocalOnnxPipelineIT` cases passed, including the real local ONNX reference path.
- The core job later timed out while starting `WorkspaceAccessSecurityIT` after
  `WorkspaceProvisioningBoundaryIT`. Its log shows the previous executor failing
  to stop within the lifecycle phase while indexing continued into the next
  context's schema bootstrap. Completed reports contain no failing assertions;
  they do not establish success for the unfinished integration suite.

For the test-classpath policy follow-up, a fresh process using the artifact's
actual Spring 7.0.9 libraries reproduced the missing configuration before the
change and resolved the one-context policy after the change. A separate real
Spring/Hikari/HSQLDB probe confirmed that pausing retains the pool/schema, while
closing releases it before recreation. These Java 21 probes are **not** a run of
the new JUnit integration test or the full Java 25 reactor. Those require fresh CI.

Focused repository reproduction, deliberately selecting the Failsafe test:

```bash
./mvnw -B -pl taxonomy-app -am verify \
  -DskipITs=false \
  -Dtest=LocalOnnxIndexInitializerTest,EmbeddingConnectionBudgetTest,EmbeddingLifecycleSafetyTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=SpringContextCacheIsolationIT \
  -Dfailsafe.failIfNoSpecifiedTests=false
```

The authoritative command remains:

```bash
./mvnw -B verify -Pci -DrunOnnxTests=true
```

No test selections, existing deadlines, native-ONNX checks or readiness
assertions are relaxed by this follow-up. The context-isolation change must pass
the full workflow on its own exact head before this PR is described as green.
