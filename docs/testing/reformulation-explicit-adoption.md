# Explicit draft adoption — package 6

This change adds a separate review/confirmation path; generating, editing or
answering a reformulation still never calls the requirement versioning service.
It is based on the index branch (#1103); additive migration V28 follows V27.

## Product contract

The saved preview contains the exact source text/version, selected analysis
snapshot, current requirement version and metadata, complete proposal revision
(including statements, questions, answers, findings and provenance), normalized
final text, warnings and blocking findings. It is insert-only and has an integrity
hash. The UI shows original, current and proposed text separately. Exterior
whitespace normalization matches existing versioning and is visible in the preview.

Only the separate confirmed command activates text. It requires a rationale and
explicit acknowledgement of warnings/open decisions. Structural errors prohibit
adoption; unresolved questions do not prevent knowingly saving a draft. They
remain in the immutable preview and linked receipt, not stripped from history.
Changed text becomes DRAFT/PROPOSED. An identical active text/status is left alone;
an identical historical version is reused only by an explicitly confirmed command.
Old versions and snapshots remain stored. No new analysis or architecture mutation
runs here; the existing portfolio staleness check detects version mismatch.

The dialog never copies text automatically into another editor. Unsaved local
edits block opening/confirming. Closing an unfinished preview does not adopt it.
Once a command has been sent, closing does not undo it. Uncertain-response retries
reuse the same UUID and exact body; a failed refresh after a successful command
cannot turn that success into a retryable adoption. Text is rendered with textContent.
The existing generic revision API does not gain an unrestricted adoption operation.

## Transaction and replay boundaries

The order is existing project lock, requirement lock, proposal lock. The expected
proposal revision and current requirement row/version must still match the stored
preview. Changed text, answers or requirement metadata require a new preview.
Current authorization is rechecked for previews, history, confirmations and retries.

Receipt insertion and version selection/reset are one transaction. Duplicate
commands return their original receipt WITHOUT replaying the version change, even
when a later independent edit has become active. Different contents under an old
command ID conflict. Each preview is adoptable once. The preview scope/proposal and
target version/requirement/scope have physical foreign keys. A failed receipt write
rolls back the version, pointer and metadata update together. Neither Git nor an
external provider is involved. Audit includes actor, time, rationale and exact review
material. Portable checkpoint/export integration is still package 7.

## Verification evidence and limits

RED: the real authenticated preview POST returned 404 on the predecessor. GREEN:
fresh real Spring/HTTP/file-HSQL processes exercise preview immutability, explicit
confirmation/acknowledgement, wrong hashes, changed draft/answer/requirement state,
current/historical identical text, review reset, concurrent duplicate submission,
old retry after later edits, foreign scope, anonymous reads and history.
A deliberately rejecting constraint in the PRIVATE test database proves complete
transaction rollback. A second application process reads identical preview/receipt
and repeats the old command without changing current state. The shared JDBC schema
contract runs there and in both PostgreSQL fresh/adoption migration tests.

The production API/dialog Node contract checks preview-only/cancel, stale responses,
safe text, unchecked defaults, separate warning acknowledgement, double-click,
exact UUID/body reuse after an uncertain response and failed post-success refresh.
Both cancellation contracts and the existing progress contract continue to pass.
The refresh case was observed failing before its correction. Fixture syntax and a
constraint initially affecting earlier fixtures were corrected before interpreting
them as product defects.

Java 21 compilation and the plain checks run against the green #1101 runtime with
changed production sources overlaid. The normal JUnit wrappers call these same
checks and inherit JaCoCo into normal-exit child JVMs. Analysis/model outputs are
explicit stored fixtures with the real BP catalogue root, not live LLM quality
measurements. No paid model is called.

Full local Maven verify cannot fetch the pinned Maven distribution. A real Chromium
browser-to-server attempt was blocked at navigation by administrator policy; it is
NOT reported as passed, nor bypassed. Node DOM-boundary checks and real Java HTTP
checks are separate evidence, not an invented full browser result. Current-head
CI/coverage, PostgreSQL and independent review remain required. No test thresholds,
security rules or architecture baseline counts are weakened in this change.

Remaining: live-model/full civilian acceptance, rich Word/portable evidence,
large irreducible reconciliation inputs and retention policy. This is not a claim
that all eight plan packages are finished.

## Active detail refresh

The successful command also refreshes the existing requirement detail renderer
without navigating away. Values in the separate new-version form are captured
immediately before synchronous rendering and restored, including edits made while
the request was in flight. Read generations prevent an earlier read from replacing
a later result. The default existing version-creation refresh keeps its behavior.
A production-loadAll contract failed first by erasing an unsaved edit and now passes
input preservation and reordered-response checks. The actual adoption-dialog test
also asserts that its dedicated read-only refresh hook is invoked after success.
