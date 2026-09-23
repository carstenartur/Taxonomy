# Immutable reformulation report exports (package 7a)

This increment exports an explicitly selected saved proposal revision or a specific
adoption receipt. It does not save, adopt, reanalyse, delete or create Git commits.
It is the textual/structured export slice of package 7, not completion of portable
Git evidence or Word/architecture-diagram reports.

## API and historical meaning

Authenticated GET-only routes, using the existing project/requirement/offer scope:

- `/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/revisions/{revision}/export?format=json`
- `/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}/adoptions/{commandId}/export?format=json`

`format` is `json`, `md` or `html`; default is JSON. Unsupported formats are
rejected. Downloads use UTF-8, attachment disposition, no-store, nosniff and a
SHA-256 digest of the exact response bytes. This digest is an integrity aid, not
a signature or independent certificate.

A proposal-revision export says "not an adoption receipt", rather than asserting
that the revision has never subsequently been adopted. Its content never changes
because another answer or adoption happened later. A receipt export reads the
exact saved preview and its verified hash, not today's offer text. It identifies
the historical adoption but makes no assertion about today's active version or
expert approval. Changed/normalized adoption wording comes from preview.finalText;
the complete original proposal revision remains in the structured evidence.

Included are the original requirement, source version/hash, analysis snapshot ID,
selected revision, taxonomy sections, statement conditions and provenance, question
contracts/options, discoveries and merged origins, answers including superseded
history, validation, and (for adoption) full stored preview and receipt. The raw
frozen-context archive and snapshot payload are not included: this report is not
a prompt log, workspace backup or complete portable architecture checkpoint.

Read authorization remains current even for old reports. Receipts use the existing
scope+command identity and are checked against the requested offer. Reading a
receipt does not replay its command. Application composition maps Portfolio-owned
snapshots into the framework-free Export renderer; no reverse module dependency,
new Maven dependency, migration, schema change or test-policy exception is added.

## UI and safe rendering

The selected offer has JSON/Markdown/HTML download controls, explicitly labelled
as exporting the *saved* revision. Unsaved text and pending answer edits are neither
submitted nor reset. Receipt-history entries have corresponding download controls.
A control captures its exact identity when rendered; changing the selection cannot
retarget an already issued read. Responses for detached controls do not trigger
late downloads. Double-click does not duplicate the same in-flight read. HTML/login
responses without matching content type and attachment/digest metadata are rejected.

HTML output escapes every external string and includes an offline CSP; no scripts,
external assets or active links are generated. Markdown renders external text in
literal fences longer than any contained backtick sequence. Heading text is escaped.
No clock value or currently active requirement value is added during rendering.
The full structured JSON is appended so no historical fields are discarded by the
human-readable presentation.

## Tests and scope of evidence

The initial RED `ReformulationReportTest` from PR #1109 established the endpoint
and non-mutation contract. The CI repair below changes only the source of its
pre-export comparison snapshot; no field is excluded from equality. Its JSON contract is
`schemaVersion: "reformulation-report-v1"` with `proposal.id` and `proposal.revision`.
The full revision also remains available under `revision`. A porting regression
first failed against the local patch's former numeric schema version, then passed
after correcting the implementation instead of weakening the published test.

`ReformulationReportHistoryTest` adds two ordinary JUnit entry points: the
application/restart contract and actual API/download-control contract.
`ReformulationReportRendererTest` supplies four renderer cases, including literal
tilde headings. Together with the preserved initial test, this is seven JUnit methods, not a claim that they have all
run locally under JUnit. Normal child JVMs forward an existing JaCoCo agent.

This continuation uses a complete source checkout whose baseline tree is
`18531654cdb2c9e672bc2c7e268b7970c2c7ef18`, exactly current main `ae4a0d13749c3d1fb340b7bc48cbac13c950a956`.
The source artifact 10718150898 and runtime artifact 10717449058 were downloaded
and independently SHA-256 verified. Their hashes are respectively
`122380ba11cc8925633d1cd9987e661c9b3dbfa93dcb3ae48049ed845a4381c8` and
`62e7043ceb88b7d9df76f3212ff93a1566b75b5e6f30c5f52985b25ec8062aa9`.
The runtime's source tree matches the baseline, preserving the integrated LLM
failure diagnostics, resource-policy and recovery fixes. No old source tree is
substituted for the newer application.

Tests first reproduced the missing authenticated endpoint (404) on the unchanged
server and the schema mismatch on the port. Fresh Java 21 compilation of all new
production classes and executable checks succeeded. Real Spring/HTTP/file-HSQL
write and restart processes exited zero with `REFORMULATION_REPORT_HTTP_OK` and
`REFORMULATION_REPORT_RESTART_OK`. All six saved report bodies (revision and receipt,
each JSON/Markdown/HTML) remain byte-identical across later answer/adoption/text
changes and a fresh application process. Full typed revision, preview and receipt
comparisons, current authorization, wrong workspace/offer, unknown identities,
GET-only behavior and no mutation are checked. Authored model/analysis fixtures
use the real BP catalogue root; no live model is called.

Three renderer checks and the actual API/download-control check passed again on
the final sources. Existing progress, both cancellation, adoption-dialog and
active-detail-refresh Node contracts also pass. These check safe literal fields,
exact captured identities, stale detached controls, errors and duplicate clicks;
they are not a complete browser-to-server acceptance result. The HTML/Markdown
fixtures retain complete evidence rather than claiming visual or semantic quality
of a live model. The browser checks presence/format of the digest header, not a
cryptographic recomputation of the response body.

## Required integration gates

The canonical local `./mvnw verify -DexcludedGroups='real-llm'` was attempted and
failed before compilation while downloading the pinned Maven distribution.
Local Java checks are compiled source overlays on actual runtime libraries, not
a complete Maven reactor build. Current-head Maven/JUnit, coverage, unchanged
ArchUnit inventory rules, database matrix and full browser acceptance remain
required. No test selector, threshold, workflow, dependency or migration is
changed by this export increment. Any measured dependency-inventory change must
be reviewed against actual class pairs, not guessed in advance.

The old local patch is now integrated into the existing report branch rather
than creating a duplicate PR. Publishing the feature does not certify its final
CI or independent review. Review receipt binding, current authorization, immutable
historical identity, UI capture/error handling and markup containment.

Remaining package 7: portable evidence inside atomic Git checkpoints, import/round
trip, rich Word reports including the selected architecture/decision-tree graphics.
Package 8 live-model and full civilian acceptance remains separate.

## CI repair on 23 September 2026

The actual `11d8fe36` core reports (run `35795678923`, artifact `10725511098`,
independently checked SHA-256
`caefc4256dcf7df89ccb73229be4d0a6b96471360bf72482e4d36a664bc37aff`)
contain two failures. Both are addressed without changing export production code.

### Persisted before/after state

The original non-mutation assertion compared the creation call's in-memory proposal
with the later database read. Its only mismatch was `createdAt`: the creation
object retained nanoseconds (`.603000329Z`) while the column returned milliseconds
(`.603Z`). The revised test reads the persisted proposal before exporting, and
compares that complete value with the same persisted read afterwards. It still
checks all fields, the original requirement, schema, exact revision, no-store and
absence of synthesis runs. It does not ignore or round timestamps during equality.
The separate historical-body/restart checks remain unchanged.

### Reviewed dependency inventory

The unchanged ArchUnit test reported four existing package edges with additional
class pairs. The controller and service source and compiled dependencies were
reviewed: application composition still depends on Portfolio and Workspace;
Portfolio retains its established Workspace scope contract. No reverse edge,
new package direction or module dependency is introduced. Only these four exact
measured counts are recorded; all other entries and the ratchet test stay intact:

| Origin package | Target package | Before | After | Reason |
| --- | --- | ---: | ---: | --- |
| composition.reformulation | portfolio.reformulation | 37 | 43 | Report service, Report/Source values, saved revision and adoption preview/receipt access |
| composition.reformulation | portfolio.service | 3 | 4 | Existing PortfolioException for invalid report formats |
| composition.reformulation | workspace.service | 9 | 10 | Existing authenticated WorkspaceResolver in the new report controller |
| portfolio.reformulation | workspace.service | 7 | 8 | WorkspaceContext parameter in the new report service |

The counts come from the actual canonical ArchUnit output, not from a jdeps count
or a guessed allowance. The increase is nine class pairs within four already
reviewed directions. The generic context-policy validation, source coverage and
new-edge/growth/reduction checks remain unchanged. A normal fresh source-built
CI run must execute both corrected tests before merge.


## Persisted adoption identity binding (review 4079267281)

The report lookup now binds the computed scoped command ID to the stored proposal,
requirement and scope columns **before deserializing** the receipt JSON. The
receipt's preview and target-version IDs must match the physical row. Load the
preview by that stored ID, then verify its own ID/proposal and embedded
requirement/project identities as well as the receipt's previous version. All
prior source-version, source-text, snapshot, revision, authorization and hash
checks remain. The entity gains read accessors only; no schema, writer or
adoption operation is changed. These are consistency checks, not a claim that
arbitrary database-administrator tampering is cryptographically prevented.

Ten new controlled corruption cases run inside the existing historical report
JUnit entry point. They retain valid foreign-key combinations and touch only its
private temporary database. Two prove that mismatched stored owners are rejected
before malformed JSON is decoded; the others cover repointed stored/serialized
preview IDs, target/previous versions and correctly hashed preview identities.
Every changed row is restored in finally, and the original byte-identical report
plus existing source/answer/requirement/receipt no-mutation assertions remain.

On the 6bd5203 predecessor the new cases reproduced ten intended failures: two
HTTP 422 responses instead of the required pre-decode 404, and eight incorrect
HTTP 200 successes. With the correction, real Spring/authenticated HTTP/file-HSQL
write and restart processes both exit zero. The ten new binding cases, thirteen
existing rejection cases and all six byte-stable historical outputs pass.
Production/test sources were compiled with Java 21 and -parameters against the
packaged application libraries; these are supplemental runtime-library checks,
not a full local Maven/JUnit build. The current-head ordinary CI remains required.
No coverage threshold, dependency inventory, test selector or workflow is changed.
