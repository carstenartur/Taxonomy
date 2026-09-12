# Git bootstrap composition ownership

`com.taxonomy.composition.dsl.service.GitRepositoryBootstrap` owns the startup
workflow that combines application readiness, taxonomy DSL export and the
workspace-owned system Git repository. Repository identity, storage and version
authority remain in the workspace and DSL storage packages.

The component selects `DslGitRepositoryFactory.getSystemRepository()` when it is
constructed. It never derives a request workspace or falls back to another
central repository. When initialization is ready and the `draft` branch has no
head, it exports namespace `default` and creates one commit with author `system`
and message `Initial taxonomy materialization`.

Both synchronous and asynchronous startup are supported. An
`ApplicationReadyEvent` starts the synchronous path. If taxonomy loading is not
ready, the component defers without acquiring its one-shot guard; the real
`InitializationReadyEvent` then retries after the initialization state becomes
authoritative. Repeated events and an existing head do not add commits.

The `taxonomy.git.bootstrap` property enables the component by default and an
explicit `false` removes the bean. Its JVM-wide guard protects multiple Spring
test contexts that share JGit state. An I/O or runtime failure releases that
guard so a later readiness event can retry. The guard remains an internal
production detail and has no reset API.

`ArchitectureWorkspaceAuthorityBoundaryTest` keeps workspace, versioning and
editor production packages below application composition and knowledge
implementations. It also requires this composition owner, rejects the former
`com.taxonomy.versioning.service` owner, and requires representative production
classes from all three guarded workspace packages so the rule cannot pass on an
empty import.

## Measured ownership change

After integrating the published D3 tree, the fresh full-reactor focused run
executed 29 tests: 28 passed, with only the expected dependency-ratchet mismatch.
The seven real startup contracts, two ownership tests, readiness-state tests and
observation contracts all passed. The baseline below is the exact JSON emitted
by that ratchet, with the existing compact formatting preserved.

The current baseline contains **537 class pairs / 146 package edges**, compared
with D3's **539 / 147**. These are all five package-edge changes:

| Source package (under `com.taxonomy`) | Target package | Before | After |
|---|---|---:|---:|
| `composition.dsl.service` | `dsl.export` | 3 | 4 |
| `composition.dsl.service` | `dsl.storage` | 0 | 2 |
| `versioning.service` | `shared.service` | 2 | 0 |
| `versioning.service` | `dsl.export` | 1 | 0 |
| `versioning.service` | `dsl.storage` | 24 | 22 |

Two readiness dependencies become internal to composition. The export dependency
and two repository/factory dependencies retain their existing targets under the
new owner. This explains the net reduction of two cross-context pairs without
changing runtime behavior. Workspace has **42 remaining outgoing pairs**, all to
DSL storage adapters; knowledge retains **117 pairs into workspace**. Storage
ownership and the broader proposed-module graph still prevent treating this
slice as a physical extraction.

Both architecture selectors retain all nine named test classes, including the
D1/D2/D3 guards and `ArchitectureWorkspaceAuthorityBoundaryTest`. Run from the
repository root across the complete reactor:

```bash
./mvnw test -Parchitecture-tests -Dsurefire.failIfNoSpecifiedTests=false
```

The context map, cycle exceptions, migrations and all coverage floors are
unchanged. The composition service and versioning service both retain their
independent 77% line / 59% branch floors and changed-source protection. Local
default verification does not replace canonical CI `-Pci`, database/search,
product/security/recovery/UI or exact-head review gates.

## Integrated local verification

The fresh full reactor `clean verify -DexcludedGroups=real-llm`, using the pinned
local ONNX model, passed in **10:10** with **3,117 application invocations**, zero
failures/errors/skips. The subsequent complete-reactor architecture profile
passed **23 tests** across all nine selected classes in **41.072 seconds**.
No compiled production or test owner lacked a current source, and the former
bootstrap source/binary was absent.

Actual aggregate JaCoCo coverage is **100% line/branch** for the composition
service package and **80.22% / 67.65%** for versioning service, above both retained
77% / 59% floors. `GitRepositoryBootstrap` covers **28/28 lines and 6/6 branches**,
meeting the unchanged changed-source minimum. Existing test assertions and
production behavior remain intact. The local default run still skips AppIT and
post-reactor quality execution; final-head CI and review requirements remain.

The final review strengthened the asynchronous readiness test to inspect the
exact DSL and single commit immediately after initialization becomes ready,
before any repeated application-ready event. A scratch control removing only the
initialization listener failed that assertion while the other six cases passed.
The native full-reactor focused run then passed all **7 bootstrap tests** in
**43.407 seconds**; the refreshed aggregate retained the coverage ratios above.
This test-only supplement does not represent a second full verification run.
