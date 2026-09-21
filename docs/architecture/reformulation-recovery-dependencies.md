# Reviewed recovery dependency inventory

The exact `3f6a255` database lane failed in the shared application test
`ArchitectureContextDependencyRatchetTest`, before its database-specific tests.
This is a structural inventory mismatch, not evidence of a PostgreSQL migration
failure. The explicit recovery composition was added without updating its inventory.

This correction changes exactly three existing counts. The direction and the
individual class pairs are reviewed here; neither the checker nor its policy is
relaxed. The unchanged Maven module graph already permits these directions.

| Existing package edge | Count | Exact additional pairs |
| --- | --- | --- |
| `composition.reformulation` -> `portfolio.reformulation` | 18 -> 21 | `ReformulationExecutionService` -> `ReformulationRecoveryService`, `ReformulationRecoveryService.Claim`, `ReformulationRecoveryService.Dispatch` |
| `composition.reformulation` -> `portfolio.service` | 2 -> 3 | `ReformulationExecutionService` -> `PortfolioException` |
| `portfolio.reformulation` -> `workspace.service` | 2 -> 4 | `ReformulationRecoveryService` and `ReformulationRecoveryService.Dispatch` -> `WorkspaceContext` |

The application owns dispatch composition, while Portfolio owns persisted run
claims, authorization and proposal publication. The stored dispatch uses the
existing exact workspace context instead of inventing an incompatible second
scope object. Nothing introduces a feature-library dependency on the application,
a new Maven dependency, a new context or a broad exception.

The counts above are the actual ArchUnit failure output. A separate `jdeps`
comparison of the pre-recovery `71703` classes and `3f6a255` classes confirmed these
six added class pairs. Its overall totals differ for some pre-existing compiler
edges; it is supporting evidence, not a replacement for rerunning ArchUnit.

The bounded-parent change adds no additional managed cross-context dependency:
its helper lives in the existing analysis context, uses existing shared domain
records and the existing gateway budget boundary. Subgroup persistence extends
the existing checkpoint kind allowlist without a schema change.

This is an author architectural review. Exact-head CI and independent review
remain merge gates. Do not infer database compatibility from correcting this
inventory: PostgreSQL, SQL Server and Oracle suites must actually execute.
