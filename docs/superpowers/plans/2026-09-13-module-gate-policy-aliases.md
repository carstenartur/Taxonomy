# Module gate policy boundary and directory aliases

> For agentic workers: use `superpowers:subagent-driven-development` for this bounded external-review follow-up. Root owns planning, serial native Maven verification, review and publication.

**Goal:** Close confirmed current-head review gaps in PR #1054 without weakening the extraction gate or its documented support for safe checkout-local aliases.

**Base:** `78e391ab2aa5a96978501a70795422c5d6c020ac`, tree `d1422f538583e74b6d872e5a6f10140a2bde3f60`. Main remains `746e1ce6d9c33eabef1cff06618e8802876949a2`.

## Global constraints

- Work only in `/tmp/taxonomy-module-gate-fix-20260913`. Do not mutate Git state, commit, publish, run Maven, edit another worktree, or spawn subagents as implementer. Root owns native verification and publication.
- Preserve all 91 existing fixture methods and all their assertions, separate managed temporary roots, ordinary mandatory downstream Surefire enforcement, synchronized architecture selectors, and the build report owner/path.
- Preserve the pure graph evaluator, in-memory compiler declaration inventory, source-to-module identity, fail-closed effective-POM behavior, support-module presence checks, and independent safe-extraction semantics.
- No production source, POM dependency, workflow, baseline, context-map content, cycle exception, coverage floor, tag, skip, inventory exclusion, waiver, or human/exact-head review-rule change.
- Every consumed policy, source and output must stay in the checkout before reading, compiling or importing. Safe aliases inside the checkout remain supported. Reject external links and directory cycles before traversing them; do not use a blind follow-links traversal that visits outside content first.
- Test real adapter behavior with actual filesystem/compiler fixtures. New external targets must be owned by the existing managed external fixture root. No sleeps, broad cleanup, test-only adapter bypass, or assertions weakened to accept failures.

## Task 1: Validate the policy input and preserve logical inventory for safe directory aliases

Review 5188015344 on the exact base raises these findings:

1. `evaluateRepository` reads `.github/architecture-contexts.json` before applying the checkout-boundary helper. An external policy symlink can provide the policy controlling the gate. Validate the policy path, including linked ancestors, before reading policy content. Retain safe local policy aliases.
2. `repositoryFiles` validates its root but walks with `Files.walk(root)` without following directory links. A safe `src/main/java` or `target/classes` root alias is treated as the directory link alone and its inventory is omitted. Preserve the documented support for safe in-checkout directory aliases, including meaningful nested aliases, while retaining logical paths for package and physical owner mapping and checking boundaries before descending. Directory cycles must fail explicitly rather than hang, overflow, or truncate silently. Confirm the real ArchUnit import as well as the inventory accepts the same safe roots.

First establish fresh RED evidence against the exact published adapter. Cover an external policy file and linked policy ancestor before parsing, safe policy controls, source-root and output-root aliases and a meaningful nested safe alias. Include negative external/cyclic directory controls where the traversal change makes them relevant. Existing controls should continue to pass; report actual RED failure reasons without calling an already-passing case a new reproduction.

Then make the smallest cohesive adapter fix and rerun every fixture, preserving all prior methods/assertions and the unchanged evaluator. Update only directly affected statements in `docs/dev/MODULE_EXTRACTION_GATE.md` if needed to make policy/traversal behavior precise; do not narrow the safe-alias contract to avoid implementation.

Write the full report with exact changed paths, commands, RED/GREEN counts and reasons, warnings, limitations and remaining native gates in the ignored SDD directory. Freeze for root review. Root separately investigates the unrelated CI temporary Git-directory cleanup failure; do not edit tooling tests under this task.

- [x] Reproduce policy/alias findings against the exact published adapter with new regression tests.
- [x] Implement the bounded fix and pass all existing and added fixtures.
- [x] Freeze the source with a complete implementation report.
- [ ] Root runs native covering tests/architecture profile, obtains scoped and final review, publishes the exact reviewed tree and requests fresh external review/CI.

## Existing evidence and remaining integration

The previous tree passed a full clean reactor locally and canonical core CI. New PR1054 review changes and one SQL Server job remain red. That SQL job failed in unchanged `ReleaseRequestAnchorContractTest` during JUnit temporary-directory cleanup (`DirectoryNotEmptyException` for `.git`), before the DB test phase; this is investigated separately, not evidence for changing database code.

D2 PR #1056 retains its stable technically-green head and requires a real human confirmation under the repository review gate. Do not post that attestation, change the rule or merge another PR first merely to invalidate it. #628 and physical feature extraction remain incomplete.

## Controller validation

Fresh standalone RED against the exact published adapter ran 101 tests: all 91
prior fixtures and four new controls passed, while six policy-read, alias-inventory
and cycle cases failed for the expected reasons. The final adapter passed all 101
with real JUnit. The pure evaluator and all prior fixture methods/assertions remain
unchanged. Expected JUnit notices explain deletion of deliberate external links
without following their targets into the separate managed temporary root.

The complete native reactor architecture profile passed in 46.135 seconds on
Java 21: 18 application tests and 102 build tests, including all 101 fixtures and
the real repository gate, with zero failures, errors or skips. This fresh reactor
compile and inventory/import run covers the changed adapter; it is not a new full
`verify -Pci` claim. Canonical CI and current-head review follow publication.
