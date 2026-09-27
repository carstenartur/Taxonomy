# PR #1135: positive-review guard coverage correction

## Goal and scope

Close the observed CI branch-coverage gap without changing production behavior,
thresholds, test selection, or earlier accepted reformulation work. This is the
only implementation task before merging this useful intermediate delivery.

CI/CD run 36334840431, core job 108663857464, on PR head
`e89ebdf4389558dd977c62fc1eefbf2d72370c59` failed the changed-source coverage
policy for `ReformulationPositiveReviewGuard.java`: **50 covered / 88 branches
(56.82%), required 60.00%**. All browser/export/database jobs passed. The downloaded
authoritative JaCoCo XML shows the gaps mainly in rejection branches for damaged
local and imported adoption evidence. This failed policy is the RED evidence;
new tests of existing correct behavior need not themselves fail beforehand.

## Task

1. Extend the existing real Spring/API/database
   `ReformulationPositiveReviewGuardTest` with focused, independently named or
   parameterized scenarios for inconsistent/incomplete adoption receipts. Use
   actual adoption and persisted evidence, then JDBC corruption as in its
   existing `mismatchedLocalPreviewCannotAuthorizePositiveReview` test.
2. Meaningfully exercise several distinct previously untested rejection paths
   (e.g. mismatched preview hash, absent revision/current requirement/version,
   swapped preview/proposal/project/requirement/source identity). Prefer a small
   coherent matrix with coverage margin over the minimum. Add an imported-evidence
   hash/identity case if straightforward with the existing fixture.
3. Assert HTTP 409 **and unchanged persisted requirement metadata/version** after
   rejected positive review. Keep positive and scope-isolation controls intact.
   Do not add artificial mock-only counter tests or weaken a production guard.
4. Make a recoverable local checkpoint of test changes before a long run, tell
   the controller, and wait for remote checkpoint confirmation before continuing.
   Controller alone publishes through the configured GitHub connector. No force
   push, no rebase, no merge, and no changes to another task/worktree.
5. Run the complete focused guard test class with Java 21/Maven, record positive
   test counts and zero failures/errors. Use existing JaCoCo instrumentation and
   generate a report against the current portfolio classes with app execution
   data, then report the source-level BRANCH and LINE counters. Confirm at least
   60% branches, with useful margin. The final authoritative reactor CI must still
   pass; local focused measurement is not a claim of full CI success.
6. Self-review the diff and write
   `docs/implementation/reformulation-guard-coverage-report.md` with exact commands,
   counts, measurements, CI RED evidence, and any limitations. Commit the tested
   changes and report paths/SHAs to the controller. Do not spawn other agents.

## Environment

Worktree: `/workspace/scratch/38625e9262ff/Taxonomy-guard-coverage`

Local branch: `fix/reformulation-guard-coverage`, initial head e89ebdf4.

Maven runner: `python /workspace/scratch/38625e9262ff/toolchain/run-guard-maven.py`
handles the configured network proxy, Java 21, local dependency cache, and
Mockito startup agent without printing credentials. Focused command starts with:

```
python /workspace/scratch/38625e9262ff/toolchain/run-guard-maven.py \
  -pl taxonomy-app -am test -Dtest=ReformulationPositiveReviewGuardTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Authoritative failing artifact:
`/workspace/scratch/38625e9262ff/attachments/7c577910-a9a7-4e4a-8319-4145f7f5e637/pr1135-core-coverage.zip`
(contains `coverage/jacoco.xml` and `evidence/coverage-gate.txt`).

The controller will independently review this focused change, publish the final
commit to `feature/reformulation-durable-completion`, and handle CI/merge.
