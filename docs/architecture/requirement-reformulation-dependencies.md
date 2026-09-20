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
