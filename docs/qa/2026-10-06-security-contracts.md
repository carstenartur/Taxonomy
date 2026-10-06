# Proposal authorization and diagnostic logging contracts — 2026-10-06

Reviewed production source: `1de21a0a202b4f7f92ce0dbcee6afeb8237697b7`
on `main`. The changes accompanying this report add executable authorization
contracts and reconcile the CodeQL baseline; they do not change the reviewed
production authorization or logging behavior.

## Authoritative scan evidence and baseline decision

[CodeQL Source Analysis run 37411135910](https://github.com/carstenartur/Taxonomy/actions/runs/37411135910)
completed successfully for the reviewed main commit. Its Java artifact
`11388949286`, downloaded as `qa4-main-codeql-java.zip`, contains
`codeql-java/java.sarif` and `target/codeql-java-gate.json`. The complete Java
SARIF has 400 results, zero `java/sensitive-log` results, and exactly the three
previously accepted `java/user-controlled-bypass` fingerprints below. The
artifact's gate report records security threshold `7.0`, no blocking results,
and the three logging entries as unused baseline entries.

Only those three unused logging entries are removed from
[the baseline](../../.github/codeql-sarif-baseline.json). The gate's severity
threshold, fingerprint matching and fail-closed handling remain unchanged.
Absence from this complete scan, combined with the existing executable logging
contracts, supports retirement of these specific exceptions. It does not prove
that all application, framework or provider logs disclose no sensitive data.

| Decision | Rule | Source | Fingerprint |
|---|---|---|---|
| Remove unused exception | `java/sensitive-log` | `TaxonomyRelationService` | `9c9cc85f29714170:1` |
| Remove unused exception | `java/sensitive-log` | `DslMaterializeService` | `435739d0edc19d1e:1` |
| Remove unused exception | `java/sensitive-log` | `DslMaterializeService` | `64c585095996ba6b:1` |
| Retain reviewed exception | `java/user-controlled-bypass` | `ProposalApiController` | `fddf021f421e71e8:1` |
| Retain reviewed exception | `java/user-controlled-bypass` | `ProposalApiController` | `bc1e87a17bceed95:1` |
| Retain reviewed exception | `java/user-controlled-bypass` | `ProposalApiController` | `65e7cc55cc484ad9:1` |

## Authorization finding review

The three retained findings flag user-controlled bulk IDs/actions influencing
which items execute. Source review found an ordered, per-item review contract,
not a route around authorization:

1. `ProposalApiController.writableContext` obtains routing from the resolved
   server context. For central writes, it checks the selected repository's
   maintainer/owner membership or application-admin authority before querying
   proposal state or current head. Request IDs, actions and extra routing fields
   do not grant this authority.
2. `GitAuthoritativeProposalReviewService` validates writable scope and calls
   `ProposalReviewStateStore.require`. The store queries by repository ID,
   proposal ID and exact workspace ID. An admin override permits a central
   write; it does not make a foreign repository/workspace proposal visible.
3. Each accepted/rejected item invokes the Git mutation with that same context
   and expected head. A successful item's authoritative commit becomes the next
   item's expected head. An invisible item is rejected in place and supplies no
   relation decision or bookkeeping effect.
4. `ArchitectureRelationGitCommandService` checks writable scope and Git head
   before the mutation returns authority. The mutation service projects only
   after Git success. Proposal bookkeeping then locks the exact tenant row,
   rechecks status and saves. A head conflict stops the bulk sequence without
   advancing proposal state.

Workspace/fork write authority comes from the request context resolver; these
focused controller tests assume an already authorized resolved context. The
service's writable-scope validation is a routing contract, not a replacement
for authentication or membership evaluation. SQL query behavior, actual JGit
head enforcement and the full HTTP security filter chain have their separate
repository/application tests. The focused doubles do not constitute a new
external-database or authenticated-browser acceptance run.

## Executable coverage

The new `ProposalApiControllerAuthorizationHttpTest` uses the real controller,
membership evaluation, proposal review service and state store. Membership and
proposal persistence and Git mutation are boundary doubles. It would detect
moving the central role check after proposal/head access, dropping repository or
workspace predicates, using supplied routing instead of the resolved context,
or advancing bookkeeping after a stale-head error.

| Contract | Executable evidence |
|---|---|
| No member, reader and contributor cannot probe IDs or reach effects | New authorization HTTP matrix: accept/reject/revert and both supported bulk actions, with and without explicit `If-Match`; proposal, head and mutation ports remain untouched |
| Maintainer/owner/admin can review only selected repository and branch | New authorization HTTP matrix: all three single actions; exact context reaches mutation, then exact locked proposal row is updated |
| Admin authority does not expand proposal visibility | New authorization HTTP matrix: all single actions against existing foreign repository or workspace rows |
| Arbitrary absent IDs produce no mutation | New authorization HTTP matrix: negative, zero and maximum signed 64-bit IDs for all single actions |
| Foreign/own batches retain per-item isolation in either order | Expanded `ProposalApiControllerBulkHttpTest`: foreign first/middle/last; other repository, other workspace and central row; accept/reject; only own rows change |
| Unsupported action or malformed ID fails before effects | Existing malformed-ID matrix and expanded arbitrary-action matrix in `ProposalApiControllerBulkHttpTest` |
| Stale heads cannot advance bookkeeping | New authorization HTTP matrix for all single actions; expanded accept/reject bulk tests; existing real-JGit stale-command and stale-no-op tests |
| Proposal bookkeeping cannot overwrite a concurrent status | Existing `ProposalReviewStateStoreTest` checks locked exact row and status conflict |
| Relation logs retain operational counts without private identities | Existing `TaxonomyRelationLoggingTest` executes create, delete and error cases with private sentinels |
| DSL logs retain counts/bounded failure reason without content or exceptions | Existing `DslMaterializeLoggingTest` executes full/incremental materialization and accepted/proposed/provisional failure paths |

The logging source now emits operation/scope/count diagnostics. Existing tests
also confirm private relation/document data remains in the intended result or
stored document, rather than deleting useful business data to make logging safe.
No duplicate logging suite or speculative production fix is introduced.

## Verification and remaining evidence

Focused Maven verification commands for these contracts:

```bash
./mvnw -B -pl taxonomy-knowledge -am test -Dtest=ProposalApiControllerAuthorizationHttpTest,ProposalApiControllerBulkHttpTest,ProposalApiControllerRepositoryScopeTest,ProposalReviewStateStoreTest,ProposalReviewStateStoreContextTest,GitAuthoritativeProposalReviewServiceTest,TaxonomyRelationLoggingTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -pl taxonomy-app -am test -Dtest=DslMaterializeLoggingTest -Dsurefire.failIfNoSpecifiedTests=false
```

The coordinating Java 21 Maven package run selected these security contracts,
`PreferencesUiContractTest` and the four existing SARIF gate suites across
`taxonomy-app,taxonomy-tooling -am`: **168 tests passed, zero failures, errors or
skips**. This includes 57 authorization-matrix cases and 61 bulk HTTP cases.
The packaged, unchanged SARIF gate then read both complete main reports with the
reduced baseline: **412 results, three accepted exact fingerprints, zero unused
entries and zero blocking findings**.

These are working-tree iteration results, not substitutes for `./mvnw -B verify -Pci
-DrunOnnxTests=true`, its browser evidence, database compatibility lanes or a
CodeQL analysis of the final resulting commit. The retained findings remain
explicit reviewed exceptions under issue #857; this report does not claim a
blanket closure of that issue.
