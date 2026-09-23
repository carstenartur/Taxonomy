# Historical report boundary verification

Continuation of PR #1109 on `e812eeca01537c92726f1319f57f656d19d7b743`.
That revision already contains the persisted-time fixture and measured dependency
inventory corrections. They are retained unchanged.

## Remaining gate and test coverage

Exact-head CI/CD `35812317403` completed the application suites but failed
`ReactorCoveragePolicyIT` at the critical source branch policy: ReportService
covered 8/16 branches (50%, required 60%). The downloaded core artifact
`10731694149` has independently verified SHA-256
`75d03bc9a85ba0041e2254cf83ea21365dcd8d8c36a7a8294f7faeb8ccc8d903`.
Database Compatibility and the usage contract passed at that head. These are
predecessor results, not certification of this correction.

The existing real HTTP/history scenario now includes 13 rejected requests:
nonpositive revision numbers, malformed and noncanonical UUIDs, an existing
receipt requested through another offer in the SAME scope, receipt payload
identity/revision mismatches, a broken preview hash, and correctly hashed previews
with a conflicting source version, original text or analysis snapshot.
Each rejection checks the exact error detail, so an earlier unrelated exception
cannot satisfy the test. No report material is returned on these errors.

Payload faults are injected solely in the scenario's private temporary database.
Each change is restored in finally and the original report is re-read unchanged.
The physical lookup keys and foreign keys remain valid. Requirement state,
proposal/decisions, version count and receipt count are unchanged; no model usage
is created. The subsequent historical export/restart checks still compare all six
saved outputs byte-for-byte. The ordinary ReformulationReportHistoryTest already
calls this scenario; no test selector, timeout or additional JVM is introduced.

A supplemental local mutation removed the source-consistency gate from a PRIVATE
compiled overlay, not from this branch. The new test then failed at the expected
409-versus-200 assertion. Its database and log are separate from the passing run.
The unmodified gate passes all checks. The new instrumented ratio still requires
normal CI; no percentage is fabricated from the number of assertions.

## Literal Markdown headings

Apply the prepared one-line tilde escape to dynamic Markdown headings. The new
ordinary renderer case checks single/double tildes in DE/EN titles and questions,
unchanged HTML and unmodified input objects. It failed against the predecessor,
then passed after the correction. A separate check using the application's actual
Flexmark strikethrough extension also passes, with an unescaped positive control
that really renders as del. No Markdown dependency was added.

## Fresh verification and limits

Source and runtime artifacts were verified; the complete extracted baseline tree
matches `3b40ce13ba3244b31c600a44d95e3d8d7e866e80`. Fresh Java 21 compilation,
four renderer checks, real Markdown consumer, real Spring/HTTP/file-HSQL write
and second-process restart checks, and six existing production JavaScript
contracts pass. Both final application processes exited zero; a prior tool-level
timeout is not substituted for these captured exit statuses.

These are source checks against the actual packaged runtime libraries, not a
complete Maven source build. Local canonical Maven stops before compilation
because the pinned distribution cannot be downloaded. Full new-head CI, branch
coverage, database/browser checks and independent review remain required. No
policy, threshold, workflow, dependency inventory or production persistence code
is changed. The separate narrow-viewport H1 follow-up remains outside this repair.
