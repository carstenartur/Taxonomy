# Inventory and planning evidence comparison

Open a saved requirement analysis on its requirement page or in the Projects
snapshot history. **Compare inventory and planning** works with selected scored
nodes from the exact recorded catalogue tree. Architecture elements and generated
relationships are optional. The comparison does not rerun or rescale analysis.

Select the recorded nodes, using organization, optional environment and a time
question: exact date, start window, or continuous period. End dates are exclusive.
Curated sourced products work without vendor credentials. Add an explicit
availability, capability, support, organization or environment assertion to an
existing product, with citation, planned/declared/observed kind and date boundaries.
An assertion accepts `YYYY` or `YYYY-MM-DD` and retains that precision. Missing
boundaries remain unknown; product ACTIVE and verification dates do not establish
organizational availability. End of support concerns product support only.

Each candidate has separate criterion findings: SUPPORTED, CONTRADICTED, UNKNOWN,
CONFLICT, or UNEXAMINED. A partial start-window overlap remains unknown. A documented
start/end that prevents continuous coverage contradicts that period. Relevant
positive and negative evidence remains a conflict. Hard contradictions/conflicts
prevent selection regardless of any taxonomy coverage percentage.

Review requires a reason. An optional existing project solution and an explicit
reviewed coverage percentage apply the candidate and requirement link through the
existing portfolio services, with the exact saved snapshot and comparison evidence
reference. This does not mark implementation, satisfaction or procurement. Source
objects retain their identities. An external object must first be explicitly mapped
to an existing sourced product, with rationale and a frozen target-product version.
Names never establish this identity. Start a new comparison to use the mapping or
new assertions. Mapped aliases share admissible evidence without losing source rows.

Retained comparisons, their reviews and JSON downloads are local reads. They do not
refresh a vendor source. Current grants are checked again; changed demand, catalogue,
assertions, profile, plugin artifact or locally known source observations require a
new comparison before applying a review. Identical review IDs/payloads replay once;
a changed reused ID conflicts. Retained hard exclusions are outside browser-editable
candidate fields.

## Optional operator-approved sources

The server binds `taxonomy.inventory.profiles.<profile-id>` to an exact repository,
organization, approved principals and a nonempty selected-ID read selector. Each
profile declares a revision, extension ID, administrative instance ID, trusted HTTPS
origin, type projections, budgets and adapter-specific nonsecret source options.
`credential-environment` and optional `principal-environment` are environment variable
references; their values are never persisted or exported. No configured authorized
profile means no outbound call. Select at most three sources, with combined budgets
at most 100 requests, 10,000 objects, 16 MiB and 120 seconds; individual adapters may
be stricter. Candidate results are bounded to 100.

Optional `assertions` projection rules explicitly name the neutral criterion,
positive boolean field, start/end fields, organization/environment fields,
optional node field and assertion kind. Unmapped raw fields stay evidence and do
not become positive suitability findings. Unknown types, missing/null/unsupported
fields and non-atomic limitations stay visible. Errors publish a failed outcome
and preserve prior successful evidence; no failed refresh becomes an empty success.

Source reads use the installed extension's lease and retain its exact artifact
identity. Operator profiles/grants and credential environment references are not
backup data. The interop backup owner captures typed assertions, observations,
comparisons, mappings and reviews; portfolio captures immutable selection evidence
references. Restored evidence never activates a source grant or a remote read.
The application's existing closed restore-owner matrix remains authoritative.

No real vendor acceptance is claimed by deterministic fixtures. A live installation
still requires its approved configuration and multi-page, permission-denied and
failed-refresh acceptance with its actual vendor data.

### Review input fence and retained evidence limits

Review application holds the existing project aggregate lock and then the scoped
product row version fence (`PESSIMISTIC_FORCE_INCREMENT`) from input revalidation through the portfolio write and review
commit. The existing project aggregate lock also forces its technical row version, so it takes an actual write fence under MVCC. Requirement mutations observe the project lock; product edits, confirmed
coverage edits and inventory assertion appends observe the product lock. An input
writer that commits first invalidates the frozen fingerprint; one that waits for
an already applying review either commits afterward or receives a concurrent-input conflict and can retry. Neither rewrites that historical decision. The technical row versions are excluded from the semantic fingerprints. Older evaluator versions require a new comparison before a new review; an identical retained review still replays without reapplication.

At most 1,000 immutable assertions per scoped product can be evaluated. The journal
fetches one extra row and fails with `ASSERTION_LIMIT_EXCEEDED` if that bound is
exceeded, so comparison and review never treat a truncated subset as complete.

## Reviewed implementation dependencies

The inventory comparison uses the existing module boundaries. Its application
adapter implements the neutral `InventoryEvidencePort`; portfolio does not acquire
a dependency on the interop implementation. The adapter delegates persistence and
source reads to their existing interop owners. Backup selection uses the same
canonical tenant identity and scope helpers as the other feature backup owners.

The architecture dependency baseline records these reviewed class pairs:

| Source | Cross-context dependencies | Purpose |
|---|---|---|
| `InventoryEvidenceAdapter` | `IntegrationJson`, `IntegrationProblem`, `InventoryJournal`, `InventorySourceHost` | Compose neutral evidence operations, deterministic fingerprints and typed failures with their interop owners. |
| `IntegrationBackupContributor` | `BackupRowScope`, `RepositoryTenantIdentity` | Restrict inventory rows to the authorized backup selection and validate repository/branch ownership. |
| `InventoryComparisonController` | `WorkspaceResolver` | Resolve the authenticated request identity and pass its workspace context into the portfolio service. |
| `InventoryComparisonService` | `WorkspaceContext` | Preserve the explicit workspace identity through comparison, review and application. |

`ArchitectureContextDependencyRatchetTest` checks these eight class pairs exactly,
as well as the complete package dependency inventory. The new inventory boundary
check fixes these cross-context dependencies exactly; the baseline update changes
only the six package edges containing the pairs above.
