# Feature-owned tests

This change moves 18 existing test classes (104 `@Test` methods) and one shared fixture out of `taxonomy-app`. 17 test classes and the shared persistence fixture are moved byte-for-byte. Package names, class names and process-restart boundaries are unchanged. The record/replay test retains its assertions and gains a required classpath resource check instead of silently returning when its recording is missing. This is a test-ownership change, not a measured build-time improvement.

| Owner | Moved tests | Contract |
|---|---:|---|
| `taxonomy-workspace` | 6 | Editor commands, revision context, HTTP preconditions, integration adapter, atomic journal failure and complete process restart |
| `taxonomy-knowledge` | 7 | Relation/hypothesis boundaries, identity-preserving ArchiMate import and Lucene analyzers |
| `taxonomy-export` | 2 | Visio XML converters and independent Structurizr grammar validation |
| `taxonomy-analysis` | 1 | LLM record/replay, using local recordings only |
| `taxonomy-extension-api` | 1 | Immutable report result contract, JUnit/AssertJ only |
| `taxonomy-interop` | 1 | Transaction atomicity of the integration journal |

## Shared fixture boundary

`EditorPersistenceFixture` has a single source owner in `taxonomy-workspace/src/test/java`. A narrowly filtered `test-fixtures` JAR publishes only this helper, not workspace tests or application configuration. It is attached in `test-compile`, after the default test compiler, so downstream tests also work with `mvn test-compile` and `mvn test`. `taxonomy-interop` declares this artifact and HSQLDB explicitly with **test scope**. The application also declares the fixture artifact in test scope for its retained integration restart test. No reverse application dependency, duplicate helper, new module or production dependency is introduced.

The fixture archive is created even for compile-only builds (possibly empty when test compilation is deliberately skipped), so `maven.test.skip=true` does not leave an unresolved downstream test artifact. `EditorTestFixtureArchiveTest` enforces the exact payload during real test execution.

## Tests deliberately retained in the application

`PublicationIntegrationFixture` and its subclasses use `TaxonomyApplication` plus portfolio/application beans. `IntegrationRestartTest` references `NativeRequirementMappingRestartDriver`, which starts the complete application and portfolio. Both remain unchanged in `taxonomy-app`; moving only the caller would break its same-package dependency. The full application restart drivers, mixed UI/architecture guards and shared-settings checks likewise remain application-owned. Moving their filenames alone would not isolate their dependencies. Cross-module projection tests remain in the application when moving them would introduce an upward module dependency.

The committed LLM recording (and its directory marker) move with the replay test to `taxonomy-analysis/src/test/resources/llm-recordings`. The test copies that exact classpath fixture into its temporary directory; it no longer depends on a module-relative working directory or silently passes when the fixture is absent.

The two build-owned cancellation contracts still execute the real production JavaScript. Their CJS fixtures are test resources; the JS inputs are read via `RepositoryResources`, not from `BOOT-INF/classes` of a packaged application dependency.

## Verification

`FeatureTestOwnershipTest` checks missing and duplicate sources against the reviewed owner map; `EditorTestFixtureArchiveTest` checks the shared artifact. Existing Surefire selection continues to discover the moved tests; no tests, profiles, timeouts or quality gates are disabled. The canonical final verification remains `./mvnw verify -DexcludedGroups=real-llm`. Owner-only runs are additional diagnostics, not a substitute for that gate. Record actual CI outcomes per commit rather than treating source moves or configured caching as a speed measurement.
