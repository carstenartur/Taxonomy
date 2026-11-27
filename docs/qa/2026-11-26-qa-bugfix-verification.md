# Initial verification of the confirmed QA fixes — 2026-11-26

This is the historical first reconstruction checkpoint. For subsequent source corrections, fresh visual/report checks and integration evidence, see the [completion record](2026-11-26-qa-completion.md).

## Scope and source

This change reconstructs fixes for the ten functional findings in the user-provided QA report against `89c32b5adba6d319c04352a412d2bc9c257aedb2`. It also repairs two adjacent broken API paths discovered while checking the real browser/server contracts. The implementation has new regression evidence; previous work whose source was lost is not used as proof that this code passes.

The first fixes were published incrementally on `fix/qa-export-workflow-20261126`. The verified code at this checkpoint was `187d71fd35809a7761ee549966ee97f20bc2d015`; its immediate closing documentation did not change production or test code. Main was unchanged at that checkpoint. Later implementation and merge status belong to the completion record above. This historical record covers functional verification, not a complete release or Enterprise Architect acceptance.

## Original findings

| Finding | Corrected behavior | Regression location |
| --- | --- | --- |
| UX-01: mapping Confirm/Reject fails | Render actual `SnapshotDetail.summary` and `elementMappings`, use the supported snapshot routes, and take the review status from the selected button. The dialog sends the entered evidence/comment to the mapping endpoint without inventing an action classification. Reject an obsolete decision after navigation; preserve the originating tab's project identity and literal human input through the transport adapter. | `PortfolioFormActionsTest`, `portfolio/form-actions-contract.cjs` |
| UX-02: solution/product coverage forms fail | Field clicks remain interactive. A valid submit saves one proposed coverage entry using the actual field names, integer percentage and catalog identity. Solution coverage is read from the nested catalog solution and saved to `/api/solutions/{id}/taxonomy-coverage`. | `PortfolioFormActionsTest` |
| UX-03: product input click writes early | Only a valid submit adds a candidate. Reject empty/nonpositive or undisplayed product IDs and invalid percentages. Send the DTO's required numeric confidence as the existing unassessed value zero, with proposed/candidate statuses. Permission and busy guards prevent unintended writes. | `PortfolioFormActionsTest` |
| UX-04: whitespace text disappears silently | Report a localized textarea validation error and focus before intake review or a mutation command. Clear the error after correction or dismissal; unchecking the optional first requirement still permits creating only a project. | `requirement-intake.test.mjs`, `analysis-session-requirement-dialog.test.mjs` |
| UX-05: empty-project analysis rejects invisibly | Disable Analyze all with a visible, accessible explanation. A forced invocation reports a useful error without an unhandled rejection. Populated-project analysis remains available. | `PortfolioFormActionsTest` |
| UX-06: a late suggestion overwrites an authored key | Apply a suggestion or its fallback only while the request generation, project, source context, dialog lifetime and input remain current. Manual editing retains ownership even when the user restores the old text. | `analysis-session-requirement-dialog.test.mjs` |
| QEA-1: new object ID captures an old orphan | The shared writer reserves IDs referenced by preserved native rows for automatic and frozen allocations. Validate declared foreign keys before any mutation as well as before commit, so an opaque vendor reference cannot become valid merely because a newly allocated object supplied its missing target. | `SparxQeaWriteTest` |
| QEA-2: new package ID captures an old owner | Both writers reserve package references, including qualified package mirrors and saved-report `RootPackage` references. Existing unresolved owners remain unresolved or the basis is explicitly rejected under the declared-FK policy. | `SparxQeaWriteTest`, `AdmbwQeaExportTest` |
| QEA-3: unsupported native edits vanish | Reject supplied changed native evidence with a specific export error before the no-op path. Missing evidence means preserve. Sparse matching evidence on preserve-only connectors remains valid, including unresolved endpoints; explicit endpoint or evidence edits remain errors. | `SparxQeaWriteTest` |
| QEA-4: memo source is overlooked | For Source/Quelle, resolve only the exact `<memo>` marker with nonempty Notes. Explicit binary comparison handles native NOCASE schemas safely. Missing, NULL or empty Notes retain the original value. Existing text limits and ambiguous-source handling remain effective. | `AdmbwBasisInspectionTest` |

## Adjacent API corrections

**Candidate review.** The existing browser buttons now have a matching `PATCH /api/projects/{projectId}/product-candidates/{candidateId}` controller. The partial request changes supplied statuses while preserving coverage, confidence, strengths, weaknesses, evidence, exclusions and identity. It checks the authorized project and product scope. Explicit selection still requires confirmed review and no manual or retained inventory exclusions. Unsupported names and numeric enum ordinals are rejected. Confirmation uses the browser's shortlist intent; it does not silently select a product. The client also protects the operation from repeated, disabled, unauthorized and stale clicks.

`ProductCandidateReviewContractTest` exercises the real controller and service with MockMvc, real domain objects and repository test adapters. Before the implementation all 17 cases failed at the missing route; afterward they passed. These are HTTP binding and service tests, not a live database or deployed-application test.

**Snapshot comparison.** The named API now reads `/api/projects/{id}/snapshots/diff?older=...&newer=...`, preserving the old/new order and workspace header. The UI counts the returned score-change map and uses the actual fingerprint fields. It displays a current read failure and suppresses results or errors belonging to a detached snapshot.

## Test ownership and execution

Portfolio event regressions follow the repository's existing JUnit-owned Node-VM pattern. `PortfolioFormActionsTest` loads the packaged template, API and production handlers through its resource driver. The fixture checks real event handlers and transport payloads against controlled DOM and HTTP boundaries. No second portfolio Node/Playwright workflow or production test hook was introduced.

The independent Node suites cover the shared intake, suggestion dialog, canonical API, session, lifecycle, localization and continuation consumers. The new requirement-dialog suite is registered in `.mvn/javascript-junit-sources.txt`.

Java verification used Java 25 and the repository's Maven wrapper. Mockito was loaded as an explicit test JVM startup agent because this host cannot self-attach. An initial run failed in Mockito initialization before assertions; no assertions or production guards were relaxed. The successful runs retain the normal compiler, module-boundary and selection policies.

The standalone native suite ran with `-Dmaven.build.cache.enabled=false` against actual SQLite files. The original independent adversarial audit was also rerun: both dangling references stayed unresolved, Alias was explicitly rejected, the memo source matched, and the two input-byte digests remained unchanged.

### Verified suites

| Suite | Result |
| --- | --- |
| Affected non-browser JavaScript suites | 1,097 passed |
| Portfolio API/controller/service suites, including the new candidate review | 96 passed |
| Final portfolio form-action suite | 23 passed |
| Existing portfolio job lifecycle suite | 18 passed |
| Standalone Sparx plugin suite | 151 passed |
| Repository/module architecture policy gates | 226 passed |

The selected suites total **1,611 passed tests**. The final form-action and job-lifecycle rerun passed all 41 cases. Independent whole-branch review and the scoped final re-review approved the changes with no unresolved material findings. Earlier focused counts are subsets of these runs and must not be added again.

### Reproduction commands

With Java 25 and the normal Maven network configuration:

```bash
./mvnw -pl taxonomy-extension-api,taxonomy-sparx-mapping -am -DskipTests install
./mvnw -f plugins/taxonomy-sparx-plugin/pom.xml -Dmaven.build.cache.enabled=false test
./mvnw -pl taxonomy-app -am \
  -Dtest=ProductCandidateReviewContractTest,PortfolioControllerDelegationContractTest,ProductCatalogPolicyContractTest,SolutionCatalogPolicyContractTest,InventoryRetainedExclusionsTest,PortfolioFormActionsTest,PortfolioJobLifecycleTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false test
./mvnw -pl taxonomy-build -am -Dtest=PortfolioTestArchitectureContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false test
```

The selected app command ran here with an additional `-DargLine` that starts the installed `mockito-core-5.23.0.jar` as a Java agent, sets a 2 GiB test heap and enables native access. This is a host-specific test launch setting, not a production configuration change. The repository-policy command also executes its four required architecture guards.

## Review findings resolved

Independent reviews covered the intake and native memo changes, the full native integrity package, the portfolio forms and comparison, and the complete integrated branch. Follow-up fixes addressed authentic SQLite NOCASE behavior, missing memo Notes, declared-FK capture, saved-report roots, sparse preserve-only evidence, delayed permissions, shared tab preferences and post-adapter evidence loss. The final review additionally required guarding the newly enabled candidate-review buttons against competing writes; this was fixed, verified in the final 23-case suite and approved in the scoped re-review.

## Limits

- Actual Chromium acceptance could not start: the installed launcher had no browser binary, and both the available-runtime and project-pinned browser downloads returned HTTP 403. Two selected real-browser tests failed at browser startup and are excluded from the passing non-browser count. No visual GUI acceptance is claimed.
- Enterprise Architect desktop and PCS were not run. The native tests prove the checked SQLite/export contracts, not target-product acceptance.
- No new vLLM inference or full live A0/A1-to-export workflow was performed in this bugfix round.
- This is a selected regression and architecture-gate run, not the complete CI matrix. No new user-facing QEA delivery replaces the original report's export artifact.
