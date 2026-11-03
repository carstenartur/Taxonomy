# Unified architecture: reviewed dependency additions

The dependency ratchet on `fcf0d571ae554c05502973dba81a18840a1561c2`
reported eleven changed package edges in the
[Reformulation usage contract run](https://github.com/carstenartur/Taxonomy/actions/runs/41635400295).
Source implementations and compiled call targets were reviewed before recording
the exact twenty added class pairs below. These are measured inventory changes,
not allowances for future dependencies. Independent integration review remains
required.

All package names in the table start with `com.taxonomy.`. `Focus` means
`composition.architecture.ArchitectureSourceFocusService`, `FocusController` means
`composition.architecture.ArchitectureSourceFocusController`, and `Workbench`
means `portfolio.workbench.ArchitectureWorkbenchController`. Nested class names
use the bytecode `$` separator used by the ratchet.

| From package | To package | Count change | Exact added class pairs and purpose |
| --- | --- | ---: | --- |
| composition.architecture | analysis.cluster | 0 → 3 | Focus → `ClusterAnalysisObservation`, `ClusterAnalysisObservation$RecoveryInput`, `ClusterAnalysisView$ObservationScope`: read retained operation input/result and validate their original scope. |
| composition.architecture | analysis.recovery | 0 → 2 | Focus → `AnalysisContinuationService`, `AnalysisContinuationStore$Snapshot`: read retained continuation evidence and compare its exact recovery version. |
| composition.architecture | analysis.session | 0 → 2 | Focus → `AnalysisWorkingDraftService`, `AnalysisDraftDtos$AnalysisDraftView`: read the owned draft, version, branch and retained server reference. |
| composition.architecture | portfolio.dto | 16 → 18 | Focus → `PortfolioDtos$SnapshotDetail`, `PortfolioDtos$SnapshotSummary`: consume frozen graph evidence and its identity/provenance. |
| composition.architecture | portfolio.model | 1 → 2 | Focus → `PortfolioTypes$AnalysisStatus`: require a successful or partial retained result. |
| composition.architecture | portfolio.service | 8 → 9 | Focus → `PortfolioAnalysisPersistenceService`: invoke the authorized `getSnapshot` reader. |
| composition.architecture | workspace.model | 11 → 12 | Focus → `UserWorkspace`: inspect owner, source repository, shared/archive flags and current branch. |
| composition.architecture | workspace.repository | 3 → 4 | Focus → `UserWorkspaceRepository`: only the existing `findByWorkspaceId` lookup for source authorization. |
| composition.architecture | workspace.service | 26 → 31 | Focus → `RepositoryContext`, `RepositoryMembershipService`, `SystemRepositoryService`, `WorkspaceContext`; FocusController → `WorkspaceResolver`: resolve authenticated identity, read repository membership and construct the explicitly pinned source context. |
| portfolio.workbench | editor | 0 → 1 | Workbench → `ArchitectureWorkspaceRedirect`: reuse `snapshot(MultiValueMap)` to encode the legacy route into the single application workspace. |
| portfolio.workbench | workspace.service | 4 → 5 | Workbench → `WorkspaceContext`: copy the authorized workspace/repository/branch into the redirect, replacing stale incoming pins. The nested request-scope record's existing dependency is unchanged. |

## Ownership and read boundary

The retained-source bridge belongs to `taxonomy-app` composition: it joins three
feature owners without moving their evidence, persistence or authorization into
the UI. Analysis retains draft/operation/continuation ownership; Portfolio retains
snapshot ownership; Workspace retains identity and repository membership. The
bridge constructs a separate in-memory DSL model and enters the existing bounded
focus engine. Optional AI assessment does not adopt suggestions or change the
retained source.

The reviewed calls are `AnalysisWorkingDraftService.read`,
`ClusterAnalysisObservation.request/result`,
`AnalysisContinuationService.readEvidence`,
`PortfolioAnalysisPersistenceService.getSnapshot`,
`UserWorkspaceRepository.findByWorkspaceId`, and the existing authorization
readers. In particular, ordinary continuation `read` is **not** interchangeable
with `readEvidence`: it can expire claims and flush lifecycle bookkeeping.
Neither that method nor cancellation, draft save/reset, snapshot publication,
repository save/delete or editor mutation belongs to this bridge.

`ArchitectureWorkspaceRedirect` remains in `taxonomy-workspace`, alongside the
legacy editor route that owns the working-architecture compatibility entry.
Portfolio already depends on Workspace; reusing this stateless URI encoder adds
no module dependency or reverse edge. It returns a fixed context-relative shell
location and knows no feature service, persistence implementation or application
class. It is not an editor-command dependency. Moving it solely to hide the
measured edge would weaken the inventory; expanding it to domain operations would
require a separate ownership review.

## Locked boundaries and verification

`ArchitectureContextDependencyRatchetTest` retains its strict package-count and
source-coverage checks and adds two focused guards. The source-focus guard checks
the exact eighteen cross-context class pairs, the small existing composition
engine boundary, and exact method/constructor signatures, including method
references. Cross-context field access must remain read-only. Direct JPA,
Spring Data, JDBC, Hibernate and JGit dependencies are rejected. Thus substituting
a mutating method on the same already-counted class cannot pass unnoticed.

The redirect guard fixes the controller's exact workspace class pairs and calls,
and requires the shared helper to remain stateless with no other Taxonomy class
dependency. Existing snapshot-adoption and other guards are unchanged.

`git diff 44c62f54 fcf0d571 -- '*pom.xml'` is empty: the application already has
Analysis, Portfolio and Workspace dependencies, and Portfolio already has its
Workspace dependency. This change modifies no POM, context classification,
exception ledger, production Java code or ratchet comparison logic. Only the
eleven reviewed baseline entries above are added or changed. The focused Maven
ratchet and a same-class mutation check provide local evidence; current-head CI
remains the integration gate.

The focused command was executed through the checked-in wrapper with Java 25:

```bash
./mvnw -B -ntp -pl taxonomy-app -am test \
  -Dtest=ArchitectureContextDependencyRatchetTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false
```

The negative run temporarily replaced only `continuations.readEvidence(...)`
with `continuations.read(...)`. All package counts and exact class pairs still
matched; the new read-boundary guard was the sole failure among 34 cases and
identified precisely that changed method signature. After restoring the original
source, the same command passed all **34 cases** (32 existing checks and two new
guards), with no failures, errors or skips. No temporary production change is
included. This is focused architecture evidence, not a full CI-equivalence claim.
