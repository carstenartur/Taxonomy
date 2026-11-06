# Sparx QEA writing implementation and verification

The user authorized implementation and merging. Follow the approved [file-export specification](../specs/2026-11-06-sparx-qea-writing.md). Preserve the single integration UI and keep the production PCS interface read-only.

## Work packages

- [x] Restore the additive SDK and execute its fresh tests: 24 passed.
- [x] Restore the JDK-only shared semantic mappings and execute their fresh tests: 23 passed.
- [x] Restore the native writer and its preservation protections; standalone `verify`: 87 passed, including SQL-conflict and empty/NULL memo regressions.
- [x] Independently re-review the native writer. The two original Important findings are closed.
- [x] Complete the source/native ordering correction with local siblings, parent changes and interleaved requirements; 53 projection tests passed on the reader branch.
- [x] Repair the reader PR's narrowly evidenced WebKit reload classification and stable error attribution; 228 Maven-owned checks passed.
- [ ] Complete and freshly verify the restored host lifecycle, leases, storage, backup wiring and migration contracts.
- [ ] Complete the based-file controls in the existing integration form and run their Maven/JUnit tests.
- [ ] Verify the shared XMI facade regressions and module/packaging boundaries.
- [ ] Independently review host authorization, persistence, backup and UI behavior.
- [ ] Run the installed-plugin HTTP acceptance for both file modes, exact retry, restart, changed artifact and absent plugin.
- [ ] Run the complete required packaging and CI gates on the final commit.
- [ ] Merge the reader increment and the completed writer increment, then verify the resulting main commits.

## Recovery and evidence

The original transient worktree was lost during an execution-environment outage. The published reader was restored from GitHub. Recorded patch payloads restored the native writer and most acceptance sources; missing host and UI sections are being reconstructed against the approved contracts and require new verification. Earlier successful logs alone are not evidence for restored source.

Remote `feat/sparx-qea-write` checkpoints preserve the recovered source before final integration. They are work in progress and do not imply merge readiness. Reader PR #1398 now runs the complete checks on `dda18708c4b7480e13741a77395b7f0ed480c47a`.

## Review and verification rules

Run JavaScript and browser checks through their existing Maven/JUnit owners. Validate both existing import semantics and the additive writer. A failed required check remains a merge blocker. Keep independent source review separate from execution evidence and preserve exact commit and artifact bindings.

When a local JDK cannot dynamically attach Mockito, load the same Mockito agent at JVM startup for local tests; do not remove tests or change product behavior. The task-local Maven runner uses the execution environment's current proxy configuration rather than a port left over from an expired environment.

Native EA/PCS acceptance remains `NOT_EXECUTED`. Use the operator checklist in [the QEA acceptance guide](../../qa/sparx-qea-file-acceptance.md); do not distribute the private official template or infer product validation from synthetic file checks.
