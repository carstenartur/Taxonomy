# Workspace dependency assessment

This is the documentary Slice E assessment for [#1043](https://github.com/carstenartur/Taxonomy/issues/1043), within [#628](https://github.com/carstenartur/Taxonomy/issues/628), at production snapshot `bd430ff41788289567de65d78d7d8b8f4adc02a8`. It classifies existing boundaries; it changes no runtime behavior, ownership map, baseline or enforcement rule.

The measured workspace context has **zero outgoing dependencies on other managed contexts**, including knowledge, architecture and portfolio. The reverse knowledge-to-workspace inventory contains **117 distinct class pairs**. The [exhaustive structured inventory](workspace-dependency-classification.json) records each pair once, with source paths, a classification, rationale and measured member/location evidence. These are class-pair counts, not counts of individual bytecode dependency occurrences; nested classes remain distinct origins or targets.

## Graph and provenance

The canonical package measurement has 140 managed package edges and 472 managed class pairs. The native source/compiler/import inventory covers 828 production Java source files and 1,408 production classes. Fresh native architecture evidence supplied for this assessment reports 137 passing tests, with no failures, errors or skips. The root regenerated the canonical baseline byte-identically from that compiled inventory. The assessment independently checked the supplied hashes and current source against the immutable snapshot; it did not rerun Maven or alter the baseline.

| Evidence input | SHA-256 |
| --- | --- |
| [Dependency baseline](../../.github/architecture-dependency-baseline.json) | `5ff8756fa9b743c3b92853a961fb40320f128ee84c86d9f5c5fb99dea2df8c82` |
| [Context ownership map](../../.github/architecture-contexts.json) | `f0a2e0f093c313300b5b615ea4a55234955c6324bef2d7124a806e5132fdb348` |
| Native `architecture-module-graph.txt` report | `54218255ab78eb0fc9a65dcdcf9d0e8e0415589690f66a89331ba03d43e39352` |

The [module extraction gate](MODULE_EXTRACTION_GATE.md) reports `taxonomy-workspace: ready`: the current candidate reaches no extraction blocker. Its outgoing planned-module edges are to the existing `taxonomy-domain`, `taxonomy-dsl` and `taxonomy-export` support modules; zero managed-context outgoing edges does not mean zero dependencies.

The **complete proposal remains cyclic**. Its reported cyclic group comprises `taxonomy-analysis`, `taxonomy-app`, `taxonomy-architecture` and `taxonomy-knowledge`, with witness `taxonomy-analysis -> taxonomy-app -> taxonomy-analysis`. No physical feature module exists. Workspace readiness therefore establishes neither complete-DAG acyclicity nor permission to extract a Maven module.

## Classification decisions

Categories describe the observed pair's responsibility, not the whole caller or target class. They are analysis labels, never waivers or a replacement for the ratchet.

| Classification | Meaning | Workspace outgoing | Knowledge to workspace |
| --- | --- | ---: | ---: |
| `owner-api` | Workspace-owned contract, neutral identity/value, or cohesive public authority boundary appropriate for this consumer | 0 | 95 |
| `composition-orchestration` | Outer-boundary coordination of request/application identity and knowledge behavior | 0 | 15 |
| `residual-violation` | Implementation, persistence or implicit request authority that should be supplied through an owner API or composition | 0 | 7 |
| **Total** | Distinct measured class pairs | **0** | **117** |

Explicit `RepositoryContext`, `RepositoryScope`, compatibility `WorkspaceContext`, exact-head conflicts and the DSL read/version/publication/context-resolution ports are owner contracts. Knowledge retains relation semantics, projection validation, transaction timing and recovery policy; workspace retains repository selection and Git access. A value-contract classification does not certify every legacy caller's identity handling.

Ten pairs differ from the provisional classification: `SystemRepositoryService.getRepository` and `RepositoryMembershipService.canMaintain` in each of `GitHypothesisReviewCompatibilityFilter`, `GitProposalReviewApiController`, `GitRelationCommandApiController`, `ProposalApiController` and `RelationProjectionOperationsApiController` are `owner-api`. Each caller looks up its explicitly selected repository, hands that object opaquely to the membership service and consumes the owner's authorization decision. None reads or mutates the catalog fields in these uses. The concrete service types and HTTP location do not make those calls violations or classify them as orchestration. The surrounding request selection, application-admin alternative and scope conversion still require outer-boundary composition; the measured `WorkspaceResolver` pairs reflect that responsibility. This judgment does not designate the entire catalog service/entity surface as a future exported API.

## Remaining work and constraints

| Group | Measured pairs | Bounded follow-up |
| --- | ---: | --- |
| Request selection and setup | 15 composition | Review the 13 resolver pairs and the two import/search repository-state pairs at their outer boundaries. Preserve request-stable repository/workspace/branch/actor selection, provisioning order and explicit write authorization. |
| Primary identity in knowledge | 6 residual | `PrimaryRepositorySeedRelationListener`, `TaxonomyRelationService` and `RelationProjectionReadService` each depend on both the catalog service and entity, then interpret primary identity fields. Supply the required configured identity through a reviewed owner boundary or composition; no interface design is prescribed here. |
| Implicit proposal context | 1 residual | Migrate the five compatibility methods in `RelationProposalService` to caller-supplied context using the existing `InContext` operations; keep central-write authorization and failed resolution explicit. |

The primary-identity cases differ from opaque HTTP authorization handoff: knowledge itself reads `SystemRepository` fields to bind persisted seeds, synthesize compatibility contexts, or admit a legacy read source. That persistence representation leaks into knowledge policy even though the required identity is legitimate.

`WorkspaceContext.SHARED` contains actor `system`, no workspace, the `legacy-primary` sentinel and literal branch **`draft`**. It is not the configured primary/default-branch identity. `WorkspaceRepositoryContextPort` resolves an already supplied selection; its [legacy resolver](../../taxonomy-app/src/main/java/com/taxonomy/workspace/service/LegacyWorkspaceRepositoryContextResolver.java) preserves a supplied branch. Substituting `resolve(SHARED)` for primary catalog reads would therefore change behavior when the configured default branch differs. Future work must explicitly verify that case, retain `TaxonomyRelationService.primaryContext` actor/workspace normalization, and preserve `RelationProjectionReadService` fallback checks for primary repository **and** configured default branch, central scope, `NOT_BUILT` and no pending recovery.

The seed listener resolves the service through its `ObjectProvider` lazily at `PrePersist`, after JPA bootstrap. Any replacement must retain that timing, accept only the existing unbound built-in Excel/CSV seeds, retain already supplied repository IDs, and fail closed for other unbound writes or unavailable authority. Eager constructor resolution would reintroduce the bootstrap problem.

Legacy request paths still need explicit attention: ArchiMate import uses `WorkspaceContextResolver.resolveCurrentContext` directly; search setup can fall back to `SHARED`; catalog decoration and graph search use workspace-only compatibility visibility. The owner-value labels do not assert that these paths already use fully enriched, fail-closed repository selection. Resolve such behavior in a separately reviewed change. Likewise, preserve exact-head versus ordinary I/O failure distinctions and the existing immediate/after-commit publication timing when changing boundaries.

## Extraction and merge limits

This assessment does not move source, extract a feature module, approve a merge, or complete #1043/#628. Physical Maven extraction remains deferred until the complete proposed module DAG is acyclic. The real current-head external review and main-targeted canonical CI, database, JGit consumer, UI, security, product and recovery gates remain required, alongside genuine human review. A documentary classification or agent report cannot attest that human review occurred.
