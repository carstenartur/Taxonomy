# Task 4 — independent final review and execution boundary

Reviewed on 2026-09-27 by an independent read-only reviewer. Scope was the Task 4
follow-up diff from PR #1135 head `43992e1d3784641a3bff76433f403f1fe90df546`, not a
repeat review of the previous implementation packages.

Initial review identified two Important test-authority gaps: permissive semantic
answer binding in the application corpus and the absence of an actual in-flight
publication race. It also identified a weak/vacuous inherited-lineage assertion.
The correction at local `38943f6ad71720cdddf6701e39d68aaa0af7b025` (same tree as remote
`bf8e472695de2282840a6e665643714858fdd8b4`) closes all three. The targeted rereview found
no remaining Critical, Important or Minor findings in the correction diff.

A subsequent actual document-CLI invocation exposed an unregistered
`--reformulation-only` flag. Its one-line registration and regression test were
independently reviewed with no findings. This closes the command-dispatch defect;
it does not change existing document budgets or validation checks.

The reviewer confirmed the existing logs for 15/15 semantic playback tests and the
final 18/18 HTTP/race/reanalysis/exports/checkpoint/restart plus authored scenarios.
The completed current-run LibreOffice/Poppler check measured 25 revision pages and
26 receipt pages, no empty body pages, and 7/8 paired-JSON content assertions. The
first revision page and final receipt page were also visually inspected.

The small production correction extends the existing lossless request-local context
dictionary to inherited question discoveries and origins, and advances input encoding
identity to v5. Unique evidence, provider budgets and module directions are preserved.
Remote playback replaces only provider exchanges. No prepared analysis result or
final database state is inserted into the acceptance application.

Implementation review is accepted. Current-head browser execution and full CI are
separate required gates; this document does not assert they passed. A local browser
could not start because the runtime denied a required socket operation. The opt-in
real-provider comparison recorded NOT_RUN and human quality remains NOT_REVIEWED.
Neither schema validity nor deterministic application acceptance proves language
quality, semantic completeness or superiority over a single prompt.
