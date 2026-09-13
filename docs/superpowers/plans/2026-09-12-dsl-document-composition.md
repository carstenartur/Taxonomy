# DSL document composition implementation plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` to execute this bounded task. Check off steps only after their evidence exists.

**Goal:** Remove export/materialization/archive orchestration from the workspace DSL HTTP adapter and facade while preserving the published DSL API, Git authority and materialization behavior.

**Architecture:** A composition-owned document controller and facade coordinate knowledge materialization/archive operations with workspace reads. `DslOperationsFacade` and its primary semantic variant retain Git/version/checkpoint operations. A single workspace-owned HTTP compatibility resolver supplies the existing read-context behavior to both controllers. All classes remain in the one Spring Boot application; no feature module is extracted here.

**Tech stack:** Java 21, Spring MVC, JGit, Spring Data JPA, Micrometer/OpenTelemetry, JUnit 5, Mockito, ArchUnit, Maven/JaCoCo.

**Spec:** Bounded D3 slice of [#1043](https://github.com/carstenartur/Taxonomy/issues/1043), implementing [#628](https://github.com/carstenartur/Taxonomy/issues/628). Source base: D2 commit `2cb0aaa30c2cd44e7e164ff54f9f4183635f5d79`.

## Global constraints

- Keep every existing route, HTTP verb, parameter default, response/status, security annotation, Git repository choice, transaction, lock, journal/checkpoint distinction, materialization write scope and persistence/search mapping intact.
- Keep the imported `ArchitectureDslDocument` archive in its current package; it is not Git authority. Do not redesign its global archive policy in this ownership move.
- Do not change `GitRepositoryBootstrap`, storage ownership, domain DSL semantics, context map, exception ledger, workflow/review guards or existing coverage floors. Physical feature extraction remains blocked by actual cycles.
- Preserve existing test assertions. Move assertions with their endpoint; do not delete an assertion because its old controller no longer owns the endpoint.
- Keep only one implementation of the legacy HTTP read-context fallback. That compatibility path must not replace or weaken the existing fail-closed pre-resolution interceptor or facade repository resolver.
- Add the new architecture guard to both architecture-test selectors; retain all existing selectors and incorporate upstream selector additions during stack integration.
- Carry the moved operations' existing telemetry target configuration to their new owner in both Micrometer and `observability/javaagent.properties`. Keep bounded names/tags and ensure a composed Git diff is not instrumented twice by the same mechanism. Do not redesign generic bean-target matching.
- All new composition sources receive changed-source protection and package floors at least as strong as their previous owner. Use separate `composition.dsl.controller` (81% line / 58% branch) and `composition.dsl.service` (77% line / 59% branch) packages to preserve the existing independent budgets.
- Record the dependency baseline from freshly compiled actual bytecode only, and explain each changed edge. No hand-built allowance, union, temporary exception or relaxed budget.
- Work in `/workspace/scratch/5f847bdd1880/Taxonomy-dsl-composition`. No pushes, PR mutations, other-worktree edits or additional agents by the implementer. Root owns serial Maven execution, independent review and GitHub publication.

## Task 1: Separate document orchestration and guard the boundary

**Create production files:**

- `taxonomy-app/src/main/java/com/taxonomy/composition/dsl/controller/DslDocumentApiController.java`
- `taxonomy-app/src/main/java/com/taxonomy/composition/dsl/service/DslDocumentOperationsFacade.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/controller/DslReadWorkspaceContextResolver.java`

**Modify production/configuration files:**

- `taxonomy-app/src/main/java/com/taxonomy/versioning/controller/DslApiController.java`
- `taxonomy-app/src/main/java/com/taxonomy/versioning/service/{DslOperationsFacade,SemanticDslOperationsFacade}.java`
- `taxonomy-app/src/main/java/com/taxonomy/observability/TaxonomyObservationConfiguration.java`
- `observability/javaagent.properties`
- `.github/critical-coverage-policy.json`, `pom.xml`, `.mvn/verification-suites.json`
- `.github/architecture-dependency-baseline.json` only after root supplies fresh measured output.

**Create/update tests:**

- `taxonomy-app/src/test/java/com/taxonomy/ArchitectureDslCompositionBoundaryTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/composition/dsl/controller/DslDocumentApiControllerContractTest.java`
- Move `versioning/controller/DslApiControllerContextBoundaryTest.java` to `composition/dsl/controller/DslDocumentApiControllerContextBoundaryTest.java` with the same two history assertions and separate document-facade mock.
- Move the materialize-incremental test and document-list assertions out of `versioning/controller/DslApiControllerBranchCoverageTest.java` into the document-controller contract tests; retain all unrelated assertions in place.
- `taxonomy-app/src/test/java/com/taxonomy/composition/dsl/service/DslDocumentOperationsFacadeTest.java`
- `taxonomy-app/src/test/java/com/taxonomy/versioning/controller/DslReadWorkspaceContextResolverTest.java`
- Update only constructor setup/imports in `versioning/service/{DslOperationsFacadeRationaleTest,DslOperationsFacadeWorkspaceIsolationTest}.java` and any other real constructor consumer found by a complete source search.
- `taxonomy-app/src/test/java/com/taxonomy/observability/DslDocumentObservationTest.java` (same package as the package-private postprocessor).
- Preserve existing full Spring `dsl/DslApiControllerTest`, `versioning/controller/ViewContextIntegrationTest`, materialization write-scope, HTTP pre-resolution, editor atomicity and Git persistence tests.

**Documentation:** Create `docs/dev/DSL_DOCUMENT_COMPOSITION.md`. Update current ownership references in `docs/{en,de}/{MODULE_BOUNDARIES,ARCHITECTURE}.md`, `docs/dev/05-workspace-git-context.md`, `docs/dev/relation-and-dsl-extension-boundaries.md`, `docs/dev/tasks/add-dsl-property.md`, and the DSL-editor row in `docs/internal/MAINTAINABILITY_MATRIX.md`. Keep historical evidence explicitly historical; final current counts come from measured output. Root integrates upstream D1/D2 doc corrections before publication.

**Interfaces and exact contracts:**

| Owner | API/methods |
| --- | --- |
| `DslDocumentApiController` | GET `/api/dsl/export`, GET `/current`, POST `/materialize`, POST `/materialize-incremental`, GET `/history`, GET `/diff/{beforeId}/{afterId}`, GET `/diff/semantic/{beforeId}/{afterId}`, GET `/documents` |
| `DslDocumentOperationsFacade` | `exportAll(String)`, `buildCanonicalModel()`, `materialize(String,String,String,String)`, `materializeIncremental(Long,Long)`, `findDocumentById(Long)`, `findDocumentIdByCommitId(String)`, `listDocuments()`, `diffBetween(String,String)` |
| `DslOperationsFacade` | All existing Git/workspace methods; `diffBetween(String,String)` now delegates only to the selected Git repository |
| `DslReadWorkspaceContextResolver` | Constructor `(WorkspaceResolver, RepositoryStateService)`; `WorkspaceContext resolve(String username)` preserves the old controller helper's provision → resolve / exception → SHARED behavior and warning message |

`DslDocumentOperationsFacade` injects `TaxDslExportService`, `DslMaterializeService`, `ArchitectureDslDocumentRepository` and the Spring-selected `DslOperationsFacade`. The document controller injects that document facade, the workspace facade, `WorkspaceResolver` and the shared read-context resolver. The old controller injects the same read-context resolver instead of `RepositoryStateService`; no duplicate compatibility constructor is needed.

Move the dispatch predicate unchanged:

```java
public ModelDiff diffBetween(String beforeId, String afterId) throws Exception {
    if (looksLikeGitSha(beforeId) && looksLikeGitSha(afterId)) {
        return dslOps.diffBetween(beforeId, afterId);
    }
    return materializeService.diffDocuments(Long.valueOf(beforeId), Long.valueOf(afterId));
}
```

`looksLikeGitSha` retains the exact old null/length/lowercase `[0-9a-f]` behavior. Mixed, malformed, uppercase, short and overflow IDs must keep the same failure/HTTP behavior. Numeric archive comparison must not resolve any Git repository; Git comparison must retain the mandatory request-selected repository and fail closed.

- [x] **Guard first:** Add a non-vacuous ArchUnit rule forbidding `versioning.controller..` and both exact facade classes from depending on `architecture..`, `catalog..`, `relations..` or `dsl.export..`; require the new controller/facade/resolver in the stated packages. Verify exact ownership of the eight routes by reflection and verify parse/validate/format/text-diff/history-index remain in the old controller. Match existing production import options. Run the guard against pre-move classes in fresh standalone output and record the expected ownership failures.
- [x] **Facade move:** Move only export/materialize/archive methods and their three dependencies out of the two workspace facades. Preserve all other implementations including `version`, `merge`, `resolveRepositoryContext`, rationales, search, view contexts and overloads. Add dispatch tests for two 40-character lowercase Git SHAs, two numeric IDs, mixed IDs, uppercase/short/null/malformed/overflow IDs, and propagated Git/archive failures. Verify correct real branch selection through the existing workspace isolation tests, not only a mocked return value.
- [x] **HTTP move:** Copy the eight methods with their annotations and response-building statements, replacing only document operation calls with the document facade. Extract the one compatibility resolver, wire both controllers to it and retain successful/fallback/error paths. Preserve the `/history` 503 envelope and avoid adding exception content there. Test all eight published endpoints with standalone MockMvc (register both controllers to detect conflicting mappings), parameter defaults/required inputs, JSON keys/status/content types, empty/nonempty results, materialize invalid results and incremental branch/default/error behavior. Keep old branch-coverage/history assertions intact in their new owner.
- [x] **Telemetry:** Transfer `materialize`, `materializeIncremental` and `diffBetween` to the composition facade target in both inventories; remove those three from the workspace target so Git diff delegation cannot create duplicate operation spans. Keep operation names and bounded tags. The existing postprocessor uses exact runtime class names, not superclass traversal: do not claim it currently instruments `SemanticDslOperationsFacade` or silently broaden its matching. Exercise the real postprocessor on composition and workspace facades, invoke real routing with mock infrastructure, and assert exactly one operation timer for each moved operation (Git and numeric diffs), error outcome propagation and no content/identity tags. Check the Java-agent method inventory resolves to real methods.
- [x] **Gates and docs:** Add both composition package floors and changed-source prefixes, keeping old rules unchanged. Add `ArchitectureDslCompositionBoundaryTest` to both focused selectors and keep their lists synchronized. Document the new route ownership, Git authority, archive compatibility, telemetry move, remaining bootstrap/storage/knowledge coupling and physical-extraction blocker. Replace stale current owner statements in the listed docs; do not claim measured counts or tests before root supplies evidence.
- [x] **Focused hand-off:** Compile/run new boundary/unit/HTTP/telemetry tests in unique standalone output if feasible. Do not run Maven concurrently with root. Supply the exact focused command/output and a diff-based statement of preserved assertions, constructor consumers and telemetry registrations. Stop editing while root performs serial Maven verification.
- [x] **Root verification and measured baseline:** Root runs a fresh full-reactor focused test selection with `python3 ../runtime/run-maven.py test -Dtest=ArchitectureDslCompositionBoundaryTest,DslDocumentApiControllerContractTest,DslDocumentApiControllerContextBoundaryTest,DslDocumentOperationsFacadeTest,DslReadWorkspaceContextResolverTest,DslDocumentObservationTest,DslOperationsFacadeRationaleTest,DslOperationsFacadeWorkspaceIsolationTest,DslApiControllerTest,ViewContextIntegrationTest,DslMaterializeWriteScopeTest,DslWorkspacePreResolutionInterceptorTest,ArchitectureContextDependencyRatchetTest -Dsurefire.failIfNoSpecifiedTests=false`. Record only its actual measured dependency snapshot, then rerun the changed architecture ratchet. With the pinned local ONNX model, root runs `TAXONOMY_EMBEDDING_MODEL_DIR=/workspace/scratch/5f847bdd1880/Taxonomy-verify/models/bge-small-en-v1.5 python3 ../runtime/run-maven.py verify -DexcludedGroups=real-llm` once. Inspect actual package coverage against unchanged floors; repair concrete regressions rather than lower floors. Local default verify does not replace CI's `-Pci` AppIT/coverage or PostgreSQL/search/UI gates.
- [ ] **Review and publication:** Write the implementation report under the plan-specific ignored SDD directory. Root obtains independent spec/quality review, resolves findings with focused evidence, commits and publishes a stacked draft PR. Keep parent issues open and advance the PR through main-based CI and exact-head review before any merge.
