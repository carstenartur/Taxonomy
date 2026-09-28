# Planning profiles: verification and delivery evidence

## Current correction set — 2026-09-28

PR #1138, branch `feature/planning-profiles`. These corrections are based on
`500ef55dd79da44f05219a235f309e0030a61485`; they do not change `main`, test timeouts,
CI rules, required checks, or the agreed first-step feature scope.

### Publication boundary

The connector's security check blocked publication of the modified
`CompleteCopilotSessionIT.java`. That browser-test correction and its additional
JUnit regression remain local and are delivered separately as a patch; they are
**not in this commit**. The block was not bypassed. Only the three review
corrections, their focused contract tests and this report are being published.
The PR is back in draft and must not merge until the browser correction is applied
and the resulting exact-head checks pass normally.

### Actual CI failure on the preceding head

CI/CD run `36388503592`, core job `108819348006`, contains one failing test:
`CompleteCopilotSessionIT.completeSessionSupportsCancelReconnectReloadResultsAndEquivalentExports`.
Its Selenium exception waits 300 seconds for `newVersionModal` to become visible,
at `exerciseRequirementWorkspaceControls`, after the successful Copilot result.
The method's total elapsed time is 405.4 seconds. This is not an LLM timeout,
ReqIF failure, or the earlier editor-rationale defect.

The click helper inherited Bootstrap's smooth scrolling and immediately issued
native WebDriver input. An isolated Chromium reproduction using the actual
rendered requirement template and packaged assets showed that input can miss the
moving button. Immediate scrolling resolves that reproduced race. The locally prepared JUnit
regression creates a real long requirement through the UI, checks the synchronous
click target geometry, and opens/closes the actual Bootstrap modal with native
WebDriver input. Closing also waits for the existing `shown.bs.modal` marker;
visibility alone can precede completion of Bootstrap's opening transition.
No timeout was increased, test skipped, or JavaScript click/show substituted.

The CI artifact does not contain a pointer-event trace, so the isolated race
reproduction is not claimed to be a recording of the original CI click.
The complete corrected Selenium/PostgreSQL session still requires exact-head CI.

### Review corrections

- An unsupported incoming profile version no longer invokes the previous known
  profile's removal hook. Its canonical source link stays intact while the new
  envelope remains uninterpreted.
- Planning commands are derived only from explicitly accepted incoming requirement
  envelopes, before local omissions are retained. Rejected, metadata-free and
  empty-envelope imports do not re-import local planning entries. The external
  baseline continues to use `change.after()`; the local result is distinct.
- Envelope comparison ignores entry order, preventing false omission notices for
  valid unsorted input. Duplicate identities are still rejected by the codec.

### Fresh local checks

| Check | Executed result |
|---|---|
| Three modified Java production classes | Compile with Java 21 against the exact `500ef55` application classes/dependencies. Not a full reactor build. |
| `PlanningProfileContract` | **54 assertions passed**, including preservation of a known source link during an unknown-version import. |
| `ReqifPlanningContract` | **21 assertions passed**, executing the unchanged current source with blob `67e8a09c715f379012a0e4f9ac6babda583b0ebd`. |
| `PlanningIntegrationContract` | **39 assertions passed**, including accepted-input selection and order-independent omission reporting. |
| JavaScript planning/editor tests | **11 tests passed**, zero skipped; both production modules and the conflict-rationale regressions are exercised. |
| Isolated real Chromium modal checks | **12 cases passed**: desktop/mobile, ordinary/reduced motion, three repetitions. Uses the new JUnit test's exact embedded scroll/geometry script, rendered template and packaged Bootstrap/application CSS/utilities. |
| Diff and syntax checks | `git diff --check` and JavaScript syntax checks passed. |
| Full prescribed Maven command | `./mvnw verify -DexcludedGroups="real-llm"` cannot start in the recovered partial workspace: `mvnw` is absent (exit 127). No weaker command is presented as a substitute. |

The current executable total is **54 + 21 + 39 = 114 Java assertions**.
Earlier PR prose stated 22 ReqIF assertions; this fresh count comes from the
hash-verified current test source, rather than copying that stale total.
The added browser JUnit method has not been executed through Selenium locally.
Direct browser navigation to the local server is blocked by the environment;
the offline Chromium check is not a connected browser/server end-to-end test.

Red-before-green evidence was recorded for the synchronous scroll check,
source-link removal and false order-dependent omission. The selection regression
first exercised the previous selected-state decision rule, then the corrected
accepted-input helper. It is a focused contract, not a full IntegrationService
HTTP transaction test. No installed vendor-product certification is implied.

The exact-head packaged application is from artifact `10955721043` in run
`36388503592`; its archive SHA-256 is
`dc32cd32209458fa7796a9a2f99c107a22299c0119db7441c1c12e0e31d4166f`.
The failed core report artifact `10957136672` has SHA-256
`eef2a0d7b57ab83b7cc8b199ee4a3af702d6840420e4dec47ddf894191b9fad1`.
These identify input evidence, not successful CI for the new corrections.

## Historical delivery evidence

The initial patch was based on
`6f7f1e9c02ee6bcbd07186dd3c4ffb688ba4edeb`, using recovered sources and packaged
application dependencies rather than a complete checkout. That delivery reported
20 compiled production classes, 48/21/14 contract assertions, six Node tests,
separate HTTP/database-restart checks, ReqIF re-export/omission checks, and isolated
German/English desktop/mobile panel checks. Those HTTP/restart checks have not
been re-executed for this correction set. The old no-PR/no-write statements
applied to that initial delivery only: PR #1138 now contains the implementation.

On `dc1f0cc`, run `36380054908` passed the core reactor, application packaging,
UI contracts, six browser lanes and existing interoperability tests, but its
aggregate intentionally failed the draft gate. On `500ef55`, the ready-for-review
run reached the actual Copilot-session failure described above. Neither run is
verification of the new corrections, and neither justifies bypassing a check.

## Required completion

Run the normal repository verification on the new exact head, including the
complete Copilot session and added modal regression, and address outstanding
review discussions. In a complete checkout the prescribed full command is:

```sh
./mvnw verify -DexcludedGroups="real-llm"
```

Use the repository-owned CI suites and documented Java 21 / Node 24 toolchain.
Do not remove tests, relax required checks, or treat schema-valid Taxonomy ReqIF
round trips as certification of arbitrary vendor custom-field behavior.
