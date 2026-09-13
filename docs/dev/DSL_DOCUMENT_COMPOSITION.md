# DSL document composition ownership

D3 of #1043 moves mixed knowledge/archive/workspace orchestration into
`com.taxonomy.composition.dsl`. It remains inside the single `taxonomy-app`
Spring Boot deployable.

## Published routes

All routes retain their existing `/api/dsl` prefix, verbs, parameters, defaults,
status codes, JSON envelopes and security rules.

| Verb | Path | Owner |
|---|---|---|
| GET | `/export` | `DslDocumentApiController` |
| GET | `/current` | `DslDocumentApiController` |
| POST | `/materialize` | `DslDocumentApiController` |
| POST | `/materialize-incremental` | `DslDocumentApiController` |
| GET | `/history` | `DslDocumentApiController` |
| GET | `/diff/{beforeId}/{afterId}` | `DslDocumentApiController` |
| GET | `/diff/semantic/{beforeId}/{afterId}` | `DslDocumentApiController` |
| GET | `/documents` | `DslDocumentApiController` |

`DslApiController` retains parsing, validation, formatting, text diff, Git
commands/reads, branches, conflict handling and history indexing/search.
`DslDocumentOperationsFacade` composes `TaxDslExportService`,
`DslMaterializeService`, `ArchitectureDslDocumentRepository` and the Spring-selected
`DslOperationsFacade`. Workspace facades no longer own document export,
materialization or archive queries.

## Authority and compatibility

Git remains the authority for versioned DSL. The composition facade dispatches a
pair of exactly 40 lowercase hexadecimal IDs to the workspace facade, which
resolves the mandatory request-selected repository and fails closed. Every other
pair retains the existing `Long.valueOf` archive-ID interpretation, including
mixed, uppercase, short, null, malformed and overflow failures. Numeric archive
comparison does not resolve Git. `ArchitectureDslDocument` remains an
architecture-owned compatibility/materialization archive; its global query
policy is unchanged.

The single workspace-owned `DslReadWorkspaceContextResolver` preserves the
legacy HTTP read provision → resolve sequence and exception → `SHARED` fallback
for both controllers. This compatibility helper does not weaken the fail-closed
request pre-resolution interceptor or Git facade. `/history` retains its 503
`HISTORY_LOAD_FAILED` envelope without exception content.

Materialization write scope, repository identity, checkpoints, journals, locks,
editor atomicity and version/merge rationales are unchanged.

## Telemetry and guards

Micrometer and the Java-agent inventory transfer `materialize`,
`materializeIncremental` and `diffBetween` from the workspace facade to the
document facade. Operation names and bounded tags stay unchanged; delegated Git
diff adds no second facade operation timer. Low-level Git method spans remain.
The Micrometer postprocessor still matches exact runtime classes: it does not
traverse the superclass of `SemanticDslOperationsFacade`.

`ArchitectureDslCompositionBoundaryTest` forbids knowledge and document adapter
dependencies from workspace controllers and both workspace facade classes,
requires the composition/resolver owners and checks exclusive route ownership.
Both focused architecture selectors include it. Separate composition controller
and service coverage floors are 81%/58% and 77%/59% line/branch respectively;
existing floors and changed-source thresholds remain.

## Remaining extraction constraints

`GitRepositoryBootstrap`, DSL storage/export adapters, knowledge materialization
and architecture archive persistence remain coupled. The context map and cycle
exception ledger are unchanged. These measured cycles still block physical
feature extraction; this slice establishes ownership, not a new Maven module.

A separate class-pair inventory of the same fresh bytecode agrees with the
ratchet's 539-pair total. Workspace has no remaining direct pairs to knowledge,
architecture or portfolio implementation packages. Its 47 outgoing pairs are
44 DSL storage-adapter pairs plus `GitRepositoryBootstrap`'s export-service and
two application-readiness pairs. Knowledge still has 117 pairs into workspace,
including explicit repository/context/DSL contracts and concrete resolver,
repository-state and system-repository access that must be reviewed separately.
Removing the document/archive coupling therefore does not authorize Maven
extraction or complete the parent issue.

## Measured dependency change

The full-reactor focused run compiled the production sources and measured
**539 cross-context class pairs across 147 package edges**, compared with D2's
537 pairs across 141 edges. The committed baseline is the complete JSON emitted
by `ArchitectureContextDependencyRatchetTest`, with formatting preserved.

All four workspace-to-architecture archive pairs disappear. Composition now
owns three archive pairs; removing obsolete facade constructor dependencies
removes two adapter pairs, while five composition-to-workspace pairs become
explicit. These ownership changes explain the net increase of two class pairs.
No context-map classification or cycle exception changed.

| Source package (under `com.taxonomy`) | Target package | Before | After |
|---|---|---:|---:|
| `composition.dsl.controller` | `architecture.model` | 0 | 1 |
| `composition.dsl.controller` | `dsl.export` | 0 | 1 |
| `composition.dsl.controller` | `dsl.storage` | 0 | 1 |
| `composition.dsl.controller` | `versioning.controller` | 0 | 1 |
| `composition.dsl.controller` | `versioning.service` | 0 | 1 |
| `composition.dsl.controller` | `workspace.service` | 0 | 2 |
| `composition.dsl.service` | `architecture.model` | 0 | 1 |
| `composition.dsl.service` | `architecture.repository` | 0 | 1 |
| `composition.dsl.service` | `dsl.export` | 0 | 3 |
| `composition.dsl.service` | `versioning.service` | 0 | 1 |
| `versioning.controller` | `architecture.model` | 1 | 0 |
| `versioning.controller` | `dsl.export` | 1 | 0 |
| `versioning.controller` | `dsl.storage` | 2 | 1 |
| `versioning.service` | `architecture.model` | 1 | 0 |
| `versioning.service` | `architecture.repository` | 2 | 0 |
| `versioning.service` | `dsl.export` | 6 | 1 |

## Verification checkpoints

The first full-reactor focused run executed 97 tests: 96 passed and the single
expected failure was the dependency ratchet reporting the fresh measurement
above. HTTP contracts, request-selected Git resolution, archive/Git dispatch,
write scope, view context and observation contracts passed before refreshing the
baseline. The standalone contract run passed 38 tests; the ownership guard was
also observed failing before the move. Independent source review found no
remaining findings in the controller, facade, security or telemetry changes.

After recording that measured baseline, the full-reactor architecture profile
passed all 19 selected tests with no failures, errors or skips (44 seconds).
That checkpoint covers the six selectors then present on the D3 branch.

After integrating D1/D2 metadata, a fresh full-reactor
`clean verify -DexcludedGroups=real-llm` with the pinned local ONNX model passed
in 9:58: **3,103 application test invocations, zero failures/errors/skips**.
The subsequent architecture profile passed **21 tests** in 41 seconds across
all eight selected classes, including the three ownership guards. Production
outputs were freshly compiled; no compiled test owner lacked a current source.

The aggregate exposed two changed-source coverage gaps in retained workspace
code. Five additional history-scope contract cases now exercise selected-context
aggregation/relation responses and both routes' missing-context/provisioning
failures through the real controller and facade. Their separate full-reactor
`test jacoco:report-aggregate` run passed **5/5** in 44 seconds. This supplements
the 3,103-invocation full run; it is not a claim of another full-suite execution.
The regenerated JaCoCo aggregate contains the actual full, architecture and
supplemental execution data against the same production classes:

| Package (under `com.taxonomy`) | Line coverage | Branch coverage | Required line/branch |
|---|---:|---:|---:|
| `composition.dsl.controller` | 100% | 100% | 81% / 58% |
| `composition.dsl.service` | 100% | 100% | 77% / 59% |
| `versioning.controller` | 85.62% | 61.70% | 81% / 58% |
| `versioning.service` | 79.91% | 67.63% | 77% / 59% |

All changed production sources covered by the critical policy meet its existing
75% line / 60% branch minimum. In particular, `DslApiController` reaches
38/62 covered branches (61.29%) and `DslOperationsFacade` reaches 67/87 covered
lines (77.01%). No coverage floor, context classification or exception was
relaxed.

Focused standalone verification supplements the full-reactor focused and
`verify -DexcludedGroups=real-llm` runs; it does not replace CI `-Pci` AppIT,
coverage, PostgreSQL, search or UI gates.
