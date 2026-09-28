# Planning profiles: verification and delivery evidence

Date: 2026-09-26. Target baseline:
`6f7f1e9c02ee6bcbd07186dd3c4ffb688ba4edeb`.

## Initial patch delivery: source and environment

The local environment could read the repository through the GitHub connector but
could not clone/push using Git and offered no GitHub write action. Production
sources were recovered from the source HTML in the exact-baseline CI coverage
artifact; application resources and dependency JARs were recovered from the
exact-baseline built application. The source-recovery manifest was retained.
The browser package manifest was fetched separately at that exact commit and its
Git blob was verified as `32aeb0acd7ffe0815915b6240f7ccea2ee662b1f`.

Reference workflow: `36233612828` (CI/CD on the baseline, **before these changes**).
Reference application artifact: `10902769459`, SHA-256
`cc75fa253bb3d92f1c49cfc8c938e618f61e53e81e153d32760f2a4111b49ffd`.
Reference quality artifact: `10904820526`, SHA-256
`e757d373a109164530ce0d0850b43c05da6d9c39c8495903c1b1e5101f8acfda`.

The local Git baseline is reconstructed and is not upstream commit ancestry.
No issue, pull request or remote commit was created. Delivery is a patch for the
pinned baseline, not a claim of a merged or released feature.

## Executed checks

| Check | Actual result |
|---|---|
| Compile all 20 new/changed Java production classes | Passed with Java 21.0.11, `javac -parameters`, against the baseline application's actual classes and dependencies. |
| `PlanningProfileContract` | 48 assertions passed: precision, canonical source/edition/section links, preservation, unknown versions, third profile, DSL changes/diff/undo, shared-source and malformed-data cases. |
| `ReqifPlanningContract` | 21 assertions passed: official-schema-validated read/write, current-value overlay, new requirements, unknown profiles, malformed envelopes and string-length limits. |
| `PlanningIntegrationContract` | 14 assertions passed: canonical overlay, internal versus observed external state, omission retention, scope/identity and command planning. |
| JavaScript unit tests | Six tests passed with Node 22.16.0. They are registered in both existing CI UI chains through `test:integrations-review`. |
| Actual application HTTP import | Reviewed multipart ReqIF import completed through the real application, file-backed HSQLDB, existing integration journal and a Git checkpoint. Both profiles appeared in the editor API. |
| Actual application editor HTTP | Non-mutating preview, save, same-command replay, stale-revision rejection (412), unsupported manual profile rejection (400), undo, redo and historical read-only state passed. Original requirement text versions remained at version 1. |
| Actual process restart | Restarted the JVM using the same file-backed HSQLDB. Revision 5, planned year 2034 and source edition 2026 were retained. |
| Actual application export/reimport | Downloaded ReqIF contained the changed canonical year. A reimport with the planning attribute removed retained local entries without falsely recording them as present externally. Original text versions remained unchanged. |
| Isolated Chromium panel | Actual server-rendered panel HTML, production JS/CSS and real API projection tested in German/English at 1440x1000 and 390x844. Valid intent, read-only state, controls within viewport and zero page errors checked. |
| Syntax and patch checks | JavaScript syntax checks and `git diff --check` passed. Delivery also checks patch application and changed-file equality against the reconstructed local baseline. |

The contract runners contain real assertions and execute actual production code;
the Java unit-test wrappers use those same runners. Red-before-green evidence was
recorded for missing profiles, undo, fresh ReqIF metadata loss, stale overlay,
UI and hardening cases. The interop contract uses a small portfolio-port fixture;
the separate HTTP runs use the actual application and database.

## Verification limitations

This is **not** a full Maven reactor build. Root build tooling and the complete
repository test tree were not available locally; Maven/dependency access was
blocked. JUnit wrappers were added for normal module-owned execution, while their
underlying runners were executed directly here. Node CI expects version 24; local
Node was 22.16.0.

Direct Chromium navigation to the local server was blocked by the browser
administration policy (`ERR_BLOCKED_BY_ADMINISTRATOR`). The policy was not bypassed:
the browser check was an isolated panel check with a deterministic UUID fixture,
while HTTP/database operations were exercised separately. It is not a connected
full-browser E2E run or an axe accessibility certification.

No installed StrictDoc/DOORS/Polarion or other vendor product was run on the new
profile. Schema-valid Taxonomy round trips do not establish vendor compatibility.
No full PostgreSQL/MSSQL/Oracle matrix, ONNX/LLM evaluation, repository-wide coverage
or independent reviewer execution was performed. The final review was self-review.
The normal exact-head CI checks and review remain necessary before merge.

## Commands for a full checkout after applying the patch

```sh
./mvnw -B -pl taxonomy-dsl,taxonomy-export,taxonomy-interop,taxonomy-workspace -am test
(cd .github && npm ci && npm run test:integrations-review)
./mvnw -B verify -Pci -DrunOnnxTests=true -Dtaxonomy.ui.skip=true
```

Use the repository's documented Java 21 / Node 24 toolchain and normal database,
product-compatibility and browser lanes. These commands are the next verification
steps in a complete checkout; they are not reported as executed locally.

## Continuation: exact-baseline recheck and publication preparation

The subsequent session has GitHub Git-data write actions available. It created
`feature/planning-profiles` at the same upstream baseline without changing `main`
or creating any issue. The initial patch-only statements above describe the
earlier delivery, not this subsequent publication work.

The baseline application artifact was downloaded again and its SHA-256 verified.
All 20 changed/new production classes were recompiled against those exact baseline
classes and dependencies. The three contract runners initially repeated their
48 + 21 + 14 successful assertions, and all six JavaScript tests passed.

A further regression exposed integer narrowing in the transport-envelope schema
check: the unsupported schema number `4294967297` was accepted as schema 1 after
`intValue()` overflow. The new test failed before the fix. Requiring
`canConvertToInt()` before comparing the number rejects this value. The same
contract runners then passed **48 + 22 + 14 = 84 assertions**, and the six
JavaScript tests remained successful. This is a bounded validation correction,
not an additional planning feature. One Java constructor call has a formatting-only
line join during transfer; its arguments and behavior are unchanged.

Git blob identities are used to compare transferred content with the locally
verified files before publishing a complete commit. An uploaded, unreferenced Git
tree is not a commit or a published implementation. Full Maven, connected-browser,
database-matrix and installed-product verification remain outstanding; historical
checks on the baseline application do not verify the new feature.
