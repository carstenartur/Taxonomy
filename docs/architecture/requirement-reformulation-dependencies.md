# Requirement reformulation: reviewed dependency additions

The first reformulation package adds only the following exact class-pair counts to
`.github/architecture-dependency-baseline.json` (20 September 2026). Imports and
compiled class references were inspected before recording the observed ArchUnit
counts. These are explicit composition and existing portfolio-to-workspace
relationships; no Maven dependency or reverse module relationship is introduced.
The strict ratchet and its negative fixtures remain unchanged.

| From package | To package | New class-pair count | Reason |
| --- | --- | ---: | --- |
| composition.reformulation | analysis.service | 1 | Baseline adapter captures PromptTemplateService template content. |
| composition.reformulation | portfolio.controller | 1 | Composition advice reuses PortfolioExceptionHandler responses. |
| composition.reformulation | portfolio.dto | 1 | Baseline adapter reads the exact SnapshotDetail. |
| composition.reformulation | portfolio.reformulation | 7 | Adapter implements the context port; controller delegates to lifecycle service and uses its request/view/error contracts. |
| composition.reformulation | portfolio.service | 2 | Adapter uses PortfolioJsonCodec and PortfolioException. |
| composition.reformulation | workspace.service | 6 | Adapter reads WorkspaceArchitectureReadPort document/state under RepositoryContext/WorkspaceContext; controller resolves authenticated authority with WorkspaceResolver. |
| portfolio.reformulation | workspace.service | 2 | Lifecycle service and baseline port accept the established WorkspaceContext authority. |

All package names above are prefixed with `com.taxonomy.`. Counts are ArchUnit's
direct class-pair metric, not import-statement counts or arbitrary upper bounds.
New code remains in its owning module and package; existing packages were not
reused to obscure new edges. Domain contracts depend only on `java.base`.

The adapter freezes selected snapshot evidence separately from current workspace
and prompt context. It cannot change workspace state, source text or requirement
version pointers. Portfolio lifecycle keeps its existing downward workspace
context dependency. Question synthesis and adoption will require separate review
of any additional relationships they introduce.

Package 2 adds the app-owned asynchronous formulation composition. The controller
inspected its imports and authorized exactly these four further class-pair deltas:

| From package | To package | Count change | Reason |
| --- | --- | ---: | --- |
| composition.reformulation | analysis.reformulation | 0 → 3 | Freeze prompt content in baseline and run; invoke the frozen-input formulation engine. |
| composition.reformulation | analysis.service | 1 → 7 | Capture provider/model readiness; set/clear the existing provider thread override; classify existing rate-limit, timeout and configuration failures. |
| composition.reformulation | portfolio.reformulation | 7 → 13 | Guard expected revision and start/publish scoped persisted runs and consume proposal/run contracts through portfolio transactions. |
| composition.reformulation | workspace.service | 6 → 7 | Capture the already authorized WorkspaceContext and pass it explicitly to the bounded worker. |

No Maven dependencies, reverse edges, ratchet tests or broad allowances change.
Model calls run outside transactions, via the existing LlmGatewayRegistry budget
and provider transport. Independent package review checks these exact additions.

Package 3 adds one further reviewed count change:

| From package | To package | Count change | Exact new class pairs |
| --- | --- | ---: | --- |
| composition.reformulation | analysis.reformulation | 3 → 5 | ReformulationExecutionService → CrossTaxonomyReconciler invokes Phase B in the actual worker; ReformulationExecutionService → ReconcilePromptBuilder freezes its prompt/schema content in the durable run before any gateway call. |

The controller inspected and authorized these two pairs before the baseline edit.
All other counts and dependency rules remain unchanged. Reconciliation uses the
same node service, gateway registry, provider budgets and bounded executor.

Package 4 adds one further reviewed count change:

| From package | To package | Count change | Exact new class pairs |
| --- | --- | ---: | --- |
| composition.reformulation | portfolio.reformulation | 13 → 17 | ReformulationController → ReformulationDtos.AnswerRequest; ReformulationController → ReformulationDtos.StatementRequest; ReformulationController → ReformulationDtos.VariantRequest; ReformulationController → ReformulationAnswerException. |

The controller inspected and authorized these four pairs before the baseline edit.
They expose authenticated, scoped portfolio answer, statement and variant
operations and their HTTP 422 validation responses over the established
application-to-portfolio boundary. Portfolio retains append-only persistence and
guarded publication; application composition retains asynchronous gateway
coordination. No new Maven dependency, reverse edge, ratchet-test change or broader
allowance is introduced. The strict 22-case dependency ratchet passes; independent
package review must verify the exact four pairs.

## Integrated metadata-list correction

The corrected baseline package introduces the exact additional pair
`ReformulationController → ProposalSummary` in the existing
`composition.reformulation → portfolio.reformulation` direction. Combining it with
package 4 changes that one count from **17 to 18**, not the unrelated edges. The
list endpoint now returns the explicit metadata projection instead of loading
frozen baseline/revision payloads. The interactive client fetches selected details
separately. The compiled controller signature and actual authenticated HTTP output
were checked before recording this pair. The unchanged strict ratchet remains the
full integrated CI gate; no arbitrary allowance or test exclusion is introduced.

## Package 6: explicit draft adoption

The failed `managedContextDependenciesMatchReviewedBaseline` run on PR #1107
(head `1975ed3c03ca8d24b6e5bb181fc41a353b29c58d`) measured the following three
additions. The adoption controller, DTO signatures and service authority
parameter were inspected before recording these exact values:

| From package | To package | Count change | Exact new class pairs |
| --- | --- | ---: | --- |
| composition.reformulation | portfolio.reformulation | 32 → 37 | ReformulationAdoptionController → ReformulationAdoptionService, ReformulationAdoptionDtos.Preview, ReformulationAdoptionDtos.ConfirmRequest, ReformulationAdoptionDtos.Result and ReformulationPreconditionException. |
| composition.reformulation | workspace.service | 8 → 9 | ReformulationAdoptionController → WorkspaceResolver resolves current authenticated authority for preview, read, confirmation and history. |
| portfolio.reformulation | workspace.service | 6 → 7 | ReformulationAdoptionService → WorkspaceContext carries the existing explicit authority into the guarded portfolio transaction. |

All package names are prefixed with `com.taxonomy.`. The baseline is copied from
the unchanged ArchUnit failure's generated inventory, with a structural comparison
confirming that only these three counts differ. These are direct class-pair counts,
not new upper bounds. No new package direction or Maven dependency is introduced.
The composition controller delegates to portfolio; portfolio continues to own the
preview, receipt, requirement-version and authorization transaction boundaries.

Generating or reviewing an offer still cannot adopt it. Only the separate
confirmation command can change the active version. The existing dependency rule,
context coverage and negative fixtures remain unchanged. A fresh integrated CI run
must verify the updated inventory; this review does not substitute for that run.
