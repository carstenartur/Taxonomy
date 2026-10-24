# Release 1.5.1: confirmed authority blockers

## Goal and boundaries

Prepare the existing 1.5.1 release request from verified current source. Fix
demonstrated defects in supported paths; retain the existing Maven, JUnit and
GitHub Actions gates. The open issue list is not a request to complete every
future feature. No new workflow, test runner, identity system or projection
framework is needed. Merging the release request publishes artifacts and is a
separate consequence from preparing its reviewable commit.

## JWT recipient validation

The shipped Keycloak resource-server configuration verifies issuer, signature
and lifetime but accepts tokens addressed to another application or lacking an
audience. Configure Boot's existing audience validator with
`${KEYCLOAK_CLIENT_ID:taxonomy-app}`. An explicit standard Spring audience
override supports a distinct API audience. Configure the shipped and test realm
clients to emit that audience in access tokens and document upgrade/custom-client
configuration. Keep issuer, signature, lifetime, browser CSRF, login, principal
identity modes and application credentials intact. Real decoder tests must
distinguish `aud` from `azp` and prove a custom client ID is enforced.

## Hypothesis branch authority

Persist a required normalized branch on each hypothesis, include it in business
identity and all scope queries, and bind evidence to its parent's complete
repository/workspace/branch tuple. Read overlays may include the selected
repository's central baseline on the same branch; mutation is exact workspace.
Remove context-less repository reads instead of guessing a branch. Carry the
same context through analysis-session replay/linking, review, materialization,
publication and public summary counts. Status must not select a publication
branch. Preserve existing transaction timing, expected-head and replay behavior.

Add the next migration, V43; never edit published migrations. Derive legacy
branch authority only from explicitly linked, consistent recorded snapshot and
project identity. Ambiguous or conflicting data stops startup with bounded
numeric row diagnostics and remains available for an operator to correct.
Do not assign the current/default branch or discard user evidence. Mirror this
contract in the portable migrator and verify interrupted/repeated upgrades.

## Graph semantic search

The legacy relation embedding index has repository/workspace authority but no
branch or commit authority. Reuse the canonical relation read boundary: allow
legacy KNN only when it selects its existing central/default-branch legacy
fallback. A proven empty current projection may return global catalogue node
hits and empty relation statistics. A nonempty projection without a matching
semantic index, or any unready source, returns explicit unavailable status.
Capture and recheck source/head using fresh persistence state. Preserve actual
KNN semantics and model filtering where supported. Document the scope limit;
do not imply that global model readiness proves a particular branch searchable.
Full branch semantic indexing remains the broader #741 work.

The graph HTTP read route must expose that supported explicit central source.
It uses the existing central GET/HEAD validation of the request-bound repository,
branch and actor, with no personal-workspace provisioning for that selection.
The exception is limited to the graph read route; mutations, analysis and Git
operations retain the isolated-workspace requirement. Source/head validation
still decides whether the selected graph is available.

## Reviewed context dependencies

The authority corrections use the following workspace-owned APIs and values.
The workspace-aware summary HTTP controller belongs in application composition,
alongside the existing report and architecture HTTP bridges. The independent
architecture module must not resolve the current workspace implicitly. Its
summary service instead accepts the explicit context value. Moving the HTTP
controller preserves all three routes and retains the architecture feature gate.

| Caller | Workspace API or value | Reason |
| --- | --- | --- |
| `SchemaContractMigrator` | `RepositoryTenantIdentity` | Validate the recorded repository/workspace identity before accepting linked legacy branch provenance. |
| `ArchitectureSummaryApiController` in application composition | `WorkspaceResolver` | Resolve the authenticated request's canonical scope for hypothesis counts outside the independent architecture module. |
| `ArchitectureSummaryService` | `RepositoryContext` | Carry that scope through the existing summary operation. |
| `GraphSearchAuthorityService` | `RepositoryContext` | Bind relation-source and head inspection to the canonical branch context. |
| `DslDocumentApiController` | `RepositoryContext` | Return the same selected branch used by materialization when the optional branch parameter is omitted. |
| `SearchApiController` | `RepositoryScope` | Skip personal-workspace provisioning only for the validated `CENTRAL_READ` graph selection. |

These calls use the workspace-owned resolver API and identity/context values.
They keep authorization and identity normalization in their existing owner.
The baseline records only independently reviewed, measured class-pair changes,
including the summary controller's relocated catalogue and architecture-service
dependencies. Context classification, module topology, the prohibition on
implicit workspace resolution inside architecture, cycle checks and the exact
ratchet remain in force.

## Verification and release request

Use failing regressions at real production boundaries, then focused existing
owners and the existing required CI lanes. Reuse evidence on identical source;
do not execute inherited tests repeatedly. Report unavailable infrastructure
honestly. After fixes pass review and required gates, recreate the existing
request-only PR commit on the final source with 1.5.1, 1.5.2-SNAPSHOT,
`skip_tests=false`, `dry_run=false`, `resume_staged_release=false`, revision 17,
and its exact immediate parent as `requested_after_commit`. Preserve existing
draft releases and tags.
