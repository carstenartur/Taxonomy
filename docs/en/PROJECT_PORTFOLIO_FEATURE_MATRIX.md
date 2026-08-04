# Project Portfolio Feature Matrix

A portfolio capability is marked complete only when its user interface, API/service contract, browser or integration test, documentation and accessibility evidence exist. A check mark does not mean that every conceivable future enhancement is implemented.

| Capability | GUI route / surface | API / service | Automated evidence | User documentation | Status |
|---|---|---|---|---|---|
| Project creation and selection | `/projects` | `/api/projects` | Portfolio primary browser workflow | Project Portfolio Guide §1 | Complete |
| Manual requirement creation | `/projects` | project requirement API | Portfolio primary browser workflow | §3 | Complete |
| PDF/DOCX reviewed import | `/projects/{projectId}/import` | document parser + atomic `import-review` | Real-PDF browser workflow and transaction tests | §2 | Complete |
| Immutable requirement versions | requirement detail + import | requirement version API | Service and detail browser tests | §5 | Complete |
| Separate asynchronous analysis | project workspace and requirement detail | persisted `202` analysis jobs | Three-requirement browser workflow | §3–4 | Complete |
| Persistent job centre and retry | `/projects` | job status and `retry-failed` | Reload/recovery browser workflow | §4 | Complete |
| Requirement detail and deep link | `/projects/{projectId}/requirements/{requirementId}` | requirement, version and snapshot APIs | Route and browser acceptance | §5 | Complete |
| Snapshot history and baseline | requirement detail | snapshot/detail/diff APIs | Browser and service tests | §5 | Complete |
| Evidence-backed mapping review | project and requirement detail | mapping review APIs | Browser decision workflow | §6 | Complete |
| Taxonomy picker | solution/product coverage forms | taxonomy search API | Guided-decision browser workflow | §7 | Complete |
| Solution catalogue and requirement links | `/projects` | solution APIs | Portfolio browser workflow | §7 | Complete |
| Product catalogue and comparison | `/projects` | product APIs | Product decision and comparison evidence | §7 | Complete |
| Conflict detection and guided review | `/projects` | conflict APIs | Cloud/hosting conflict browser workflow | §8 | Complete |
| Interactive matrices and drill-down | `/projects/{projectId}/matrices` | consolidated portfolio API | Matrix filter/drill-down browser workflow | §9 | Complete |
| Filtered CSV/JSON matrix export | matrix workspace | client export from stable matrix IDs | Matrix browser workflow | §9 | Complete |
| Portfolio Git preview and commit | `/projects/{projectId}/versioning` | portfolio Git export/commit | Git browser workflow | §10 | Complete |
| Materialisation preview and apply | versioning workspace | `materialize-preview` and materialise API | No-mutation unit test and browser workflow | §10 | Complete |
| Ordinary and semantic branch merge | versioning workspace | portfolio merge API | Branch/merge browser workflow | §10 | Complete |
| Project and requirement reports | `/projects/{projectId}/reports` | report API | Format tests and browser preview | §11 | Complete |
| HTML, DOCX, Markdown, JSON and CSV | reports workspace | report renderers | DOCX package and cross-format tests | §11 | Complete |
| Role-aware controls | portfolio GUI surfaces | Spring Security roles | role browser suites and API negative tests | Navigation / §12 | Complete |
| Workspace fail-closed isolation | all workspace-bound surfaces | resolver/interceptor/service contracts | negative endpoint and repository tests | Operations/security docs | Complete after #584 merge |
| Release blocker enforcement | release workflow | GitHub issue preflight | release-guard tests | Release documentation | Complete after #584 merge |
| Dedicated metrics credential | deployment/operations | metrics SecurityFilterChain | full-chain security tests | Deployment documentation | Complete after #585 merge |
| Atomic last-admin invariant | administration | pessimistic admin lock | PostgreSQL concurrency tests | Admin documentation | Complete after #585 merge |
| Portfolio query budgets | all portfolio views | batch projections | query-budget tests | Performance documentation | Complete after #586 merge |

## Evidence policy

The authoritative portfolio browser suite covers a complete vertical process rather than only DOM presence:

```text
project
→ independent requirements
→ asynchronous analysis and reload recovery
→ snapshot and mapping review
→ solution and product decisions
→ conflict resolution
→ matrices and deep links
→ reviewed document import
→ Git commit/merge
→ report preview and export
```

Generated HTML, ARIA and screenshots are published as CI evidence. Moderate, serious and critical Axe findings fail the portfolio acceptance run.

## API-only and operational capabilities

The following are intentionally not end-user GUI workflows:

- deployment migration and rollback execution;
- database backup and restore;
- metrics-token rotation;
- release-blocker preflight administration;
- internal worker leasing and recovery configuration.

They are documented in the operations, deployment and API references rather than presented as missing GUI features.
