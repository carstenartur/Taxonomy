# Explicit budget continuation for project analyses

Regular local project analyses and individual Copilot verification passes use the
existing persistent analysis continuation store. Each job item owns its own
question journal. A separate `CONTINUE` action can extend a saved partial result
when an execution budget prevented independent remaining work. Already accepted
domain clarifications remain open and their original answers are replayed without
another provider call. Continuing does not answer, retry or skip these questions.

The procedure retains the configured limits. With the normal custom completion
provider, each cycle admits at most 24 new relation evaluations, offers up to
three candidates per batch, and keeps the 16,000-character prompt limit. The
existing 32-source cohort and work limits also remain. Classification uses its
existing separate limits; successful classification and relation answers are
replayed from the journal before further provider work is admitted. An
independent verification pass has a different journal and performs its own
assessment.

## API contract

`GET /api/projects/{projectId}/analysis-jobs/{jobId}/items/{itemId}/continuation`
only reads the current item, saved snapshot, retained provider admission and durable continuation state. It
does not enqueue work or make provider requests. The response has these fields:

| Field | Meaning |
| --- | --- |
| `snapshotId` | The item's currently referenced immutable snapshot, if any. |
| `requirementVersionId` | The immutable requirement version admitted for this item. |
| `continuationId` | The saved question journal's UUID, if present. |
| `continuationVersion` | The exact journal revision recorded by that snapshot. |
| `state` | The recovery state recorded in the snapshot, such as `STOPPED` or `COMPLETED`. |
| `available` | Whether that snapshot currently authorizes a budget continuation. |
| `reason` | Explanation of availability or the condition preventing continuation. |

The next action uses the reviewed snapshot and revision:

```http
POST /api/projects/41/analysis-jobs/{jobId}/items/7/continue
Content-Type: application/json

{"snapshotId":"<saved-snapshot-uuid>","continuationVersion":123}
```

A successful admission returns **202 Accepted**, the existing `AnalysisJobView`,
and `Location: /api/projects/41/analysis-jobs/{jobId}`. It prepares only that item
for one further bounded worker attempt. The job ID, item ID, requirement version,
provider binding, reason length and continuation ID remain the same. The item's
existing `attempt` advances. Its `snapshotId` remains the prior immutable snapshot
while the newly authorized attempt is `PENDING` or `RUNNING`; it changes only when
a new snapshot is committed. Earlier snapshots remain readable and unchanged.

Missing request fields are validation errors. A stale snapshot or continuation
revision, changed requirement version, ineligible partial result, or cancelled
operation produces a conflict and authorizes no provider work. Exact project,
repository, workspace, branch and user boundaries also apply. The worker checks
the retained version and snapshot again before opening the journal. The store
independently verifies its frozen requirement, catalogue, prompt, provider and
policy signature.

The provider retained by the job remains authoritative. Admission checks the
existing project binding for exactly this item and immutable requirement version
in the continuation transaction; it never selects a new default provider. A closed
binding or a binding that now admits another provider returns **409 Conflict**
before queue, attempt or journal progress. The worker checks the binding again
before opening the journal, so a binding closed after enqueue cannot admit provider
work. The eligibility read reports the unavailable reason using an isolated
admission check, without making its surrounding read transaction rollback-only.

## Status and restart semantics

| Situation | Item/job behavior |
| --- | --- |
| More work remains after `CALL_BUDGET`, `SOURCE_LIMIT` or `WORK_LIMIT` | Save a `PARTIAL` snapshot with `analysis.recovery.state=STOPPED`; expose the explicit action when remaining open evidence consists only of already accepted `CLARIFICATION` answers. A snapshot without open questions is also eligible. |
| User submits an eligible continuation | Same item becomes `PENDING`, then `RUNNING`; previously saved answers are retained. |
| A further cycle reaches another budget | Save another immutable `PARTIAL` snapshot at the new recovery revision; require a new explicit action. |
| The complete procedure finishes | Save a `SUCCESS` snapshot with completed recovery state and no unassessed sources or unfinished relation searches under the declared procedure. |
| Independent budget work finishes while accepted domain questions remain | Save a `PARTIAL` snapshot with `analysis.recovery.state=COMPLETED_WITH_GAPS`; retain the same questions and evidence, and offer no further budget action. |
| An assessment failed, is unassessed, was skipped or has an uncertain provider outcome | Retain that partial state; the budget action cannot retry, skip or reinterpret it, even if another part reached a budget. Eligibility checks actual persisted checkpoint states, not similar error texts. |
| Cancellation, memory or time interruption | Do not expose the budget action as a substitute for the corresponding recovery decision. |
| Application restarts after a partial snapshot | Read the same SQL journal and immutable snapshot; no provider work starts from a continuation eligibility read. |
| Application restarts after continuation was admitted but before its pending item ran | The retained snapshot reference is the durable continuation instruction. Re-dispatching the existing pending job resumes that already authorized attempt without creating a new journal or budget decision. |

Copilot operation status and selected snapshot are recalculated from the existing
pass jobs and their current snapshot references. Continuing a partial pass does
not add another verification pass. One successful pass does not mark another
partial pass complete. Result selection continues to use the existing independent
pass selection policy.

## Reading progress without result payloads

Job status, item status, requirement lists and snapshot history use scalar
metadata projections. These queries retain the exact admitted requirement version,
selected snapshot identity, source references and workspace/project scope. They
do not hydrate the four immutable snapshot payloads merely to display progress.
The snapshot detail endpoint still reads the complete requested result; mutation,
admission and claim paths retain their existing entity locks and integrity checks.

Continuation eligibility reads the item, job status/provider/idempotency key,
current and admitted requirement versions, and snapshot provenance together in
one scalar statement. It then validates that provenance and reads only the full
analysis payload, bound to snapshot, job, requirement, version, project and scope.
The complete `AnalysisResult` parser and budget, warning and clarification checks
still apply. The durable store still locks, expires and flushes the run before
checking its revision and question states. Prepare and Begin keep their existing
entity locks and renewed authorization checks, while reading snapshot provenance
through its immutable identity view. All three paths parse the complete analysis
directly from its scoped JDBC CLOB stream instead of materializing a second full
payload String or loading the unrelated enrichment payloads.

The actual HSQL regression reduced this GET from eighteen SELECTs, including
three reads of every snapshot payload, to seven SELECTs with exactly one analysis
payload read and no gap, pattern, recommendation or requirement-text LOB reads.
The unchanged original probe retains all six identity/version/provider controls
and makes no provider call. The CI-owned regression is
`ProjectAnalysisBudgetContinuationTest#continuationReadChecksExactIdentityWithoutHydratingUnusedPayloads`.
It first failed on the unwanted columns; all 38 selected continuation and policy
cases and the original byte-identical probe then passed privately. Exact SQL,
commands and hashes are retained under `target/continuation-view-payload-regression`.

A complete snapshot read selects its four payloads once and projects its element
and relation mappings directly into the existing response records. Each CLOB is
parsed into its complete existing DTO while the transaction owns its locator;
no intermediate full payload String is constructed. The parser drains the source
before returning, so a late JDBC read failure cannot publish a partial read.
Every acquired locator is released even if an earlier payload fails to open,
parse or close. The database JSON format and all response fields remain unchanged.

Snapshot persistence passes the managed instance returned by the repository to
both mapping inserts. Because snapshots have assigned IDs, `save` merges the
new entity; continuing with the original instance makes Hibernate reread all four
CLOBs to check whether the mapping's parent is transient. The real database
regression permits the initial lookup of the new ID, rejects this second payload
read, and verifies every payload field and the tenant-bound element and relation
mappings after commit.

Durable result serialization writes every field directly into the existing
lossless UTF-16 frame through a bounded character writer. The common compressible
path does not first construct an expanded JSON String. Small or incompressible
values retain their exact plain JSON representation; the same decoded and stored
limits still apply, and failed serialization cannot replace accepted evidence.
The scenario HTTP client likewise parses complete JSON responses from the body
stream, retaining its original request deadline, status checks and immutable
snapshot comparisons. Binary export responses retain their complete byte checks.

Catalogue authority and continuation signatures retain their existing SHA-256
identities, including each field's four-byte UTF-8 length prefix. Catalogue JSON
is serialized through the same configured character mapper into bounded counting
and hashing streams, avoiding a full JSON String and UTF-8 byte array. Large
String fields use a bounded encoder with the same unmatched-surrogate replacement
as `String.getBytes`; ordinary provider prompts keep the small-array path. No
catalogue, signature or completed-result cache is introduced.

Selecting a Copilot result reads only the analysis payload and mapping counts for each pass.
The payload is consumed through an explicit JDBC CLOB locator inside the existing
read-only transaction, without first building a complete intermediate String.
The same scoped SELECT computes both mapping counts from the current rows. Reader
and locator cleanup retain any original read failure and suppressed cleanup errors.
Its streaming reader retains the normal raw, legacy and product/family score
semantics, including hierarchy context, while skipping report graphs and long
descriptions that cannot affect that selection. A full result request still
returns those graphs and descriptions. Selection is recalculated on every
terminal read, including after another continuation; it is not cached.

Strictly overlapping selection reads may share the existing parser's immutable,
ordered score map and warning count. Each caller first executes its own scoped
SQL and retains its own current status, duration and mapping counts. An
instance-local registry admits at most 32 active parse keys, separated by actual
SessionFactory identity, project, scope and snapshot. Further distinct keys use
the ordinary independent parse path. The owner consumes the same complete JSON
stream and closes its Reader and locator before publishing the evidence; the
entry is removed before success or failure is published. Subscribers free their
own locators, retain interruption, and never cancel the owner. Caller-local
failure wrappers keep cleanup errors from modifying another caller's exception.
Completed parses, failures and operation results are not retained for later reads.

The controlled overlap regression first passed 42 of 48 cases, with six failures
showing redundant parsing or the missing active-key bound, and then passed all
48 with identical test bytecode. A separate cancellation cleanup case reproduced
exception contamination (48 of 49 passed); the final correction passed all 49,
again with unchanged test bytecode. The existing codec, whole-envelope, legacy
score and resource-lifetime checks remain included. These private results are
recorded under `target/portfolio-selection-inflight-regression`. Together with the
GET regression, they establish the bounded read contracts; combined reactor,
full-application resource behavior and actual export acceptance require their
own fresh results.

The following solution-enrichment step reads scoped inputs from the currently
selected mappings. It loads a requirement entity only when it actually creates a
new confirmed-coverage proposal link. Existing review decisions are preserved.
Real HSQL regressions exercise this complete terminal-read path, concurrent
requirement-version changes, and the existing pessimistic lock and versioned
snapshot-pointer update.

This addresses a measured failure in the full scenario: repeated status polling
caused Hibernate to resolve a composite-key snapshot association and materialize
its large CLOBs. Actual-HSQL regressions inspect generated SQL and loaded entities,
including explicit older versions, null-pointer latest-version fallback, multiple
projects, pagination and active Copilot reads. The correction changes neither the
heap limit nor the analysis memory guard.

Recovery status follows the same separation. Checking whether persisted questions
block a further budget reads the four owner/scope fields under the existing run
row lock and then counts the relevant states. A recovery view reads question keys,
node scopes, states, errors and attempt counts once, without loading the stored
prompts or answer bodies. The full recovery read still restores its original
request and result, and a question-detail read still restores its complete evidence.
Expired in-flight calls remain uncertain; a smaller metadata query cannot make
them accepted. Real HSQL tests cover those outcomes, exact ownership checks and
the preserved row lock.

When accepted evidence resolves an earlier corrective question, the validated
singleton node layout first excludes unrelated open questions. Only matching
questions load their complete prompts for the unchanged input-hash and exact
correction checks. The run lock, claim, operation namespace and revision checks
still apply, including when no question matches. Excluded questions keep their
existing evidence and state.

## Storing immutable intermediate snapshots

Each accepted continuation still creates a complete, separately addressable
snapshot. The standard [HSQL MEM profile](../../taxonomy-app/src/main/resources/application-hsqldb.properties)
enables native LOB compression and 1 KiB LOB blocks (`hsqldb.lob_file_scale=1`)
when it creates its new database. JSON payloads,
snapshot identities and the data returned to callers retain their existing
formats. An explicit `TAXONOMY_DATASOURCE_URL` takes precedence unchanged, including
file-database URLs; startup does not attempt to switch an existing LOB store.
The [HSQL storage contract](https://hsqldb.org/doc/2.0/guide/management-chapt.html)
allows both settings only before LOBs exist. The supported LOB block scales are
1, 2, 4, 8, 16 and 32 KiB; the engine's default is 32 KiB. The explicit URL
override is preserved even when it specifies a different block scale.

The default MEM URL also sets `hsqldb.log_size=1` to enable automatic database
checkpoints. HSQL memory databases otherwise keep obsolete LOB generations until
a checkpoint, including old versions of an overwritten continuation result. The
engine can then reuse those blocks while preserving every still-referenced
immutable snapshot. The log-size setting triggers database storage maintenance;
it does not increase the JVM heap or change analysis admission, provider budgets
or evidence retention. Explicit database URLs and the file profile keep their
configured settings.

An actual HSQL 2.7.3 regression repeatedly commits large CLOB/BLOB replacements
alongside checkpoint journal updates, checks bounded allocated LOB storage, and
reopens all current and immutable historical payloads exactly. The regression
does not issue a manual database checkpoint or force JVM garbage collection.

A controlled Hibernate/HSQL comparison inserted 40 snapshots containing the same
four actual scenario payloads, committed and cleared each session, and measured
heap after collection. The additional retained heap was approximately 640.4 MiB
without compression and 95.7 MiB with it. Both runs then restored all 160 payloads
exactly, including their UTF-8 SHA-256 values. This is a bounded storage measurement,
not a capacity guarantee for arbitrary projects. Compression adds read/write work;
it does not authorize more analysis calls, remove intermediate evidence or raise
the application's resource limits.

A separate read-allocation probe used 6,432 complete values from four actual
snapshot payloads, the catalogue descriptions, and captured provider prompts and
responses: 17,840,672 characters as counted by Java `String.length()`. Both fresh,
compressed MEM databases held the same values. The unchanged Hibernate
`DataHelper.extractString(getClob/getNClob)` path verified every value exactly
during one warmup and three measured reads. Across those three reads, allocation
on the reading thread fell from 1,303,165,720 bytes at 32 KiB to 699,245,592 bytes
at 1 KiB, a reduction of 46.34%. No alternative JDBC extraction path is required
for this result.

That probe measured reading-thread allocation. Its elapsed times were 686.9 ms
and 503.5 ms in one fixed-order comparison; they do not establish general
throughput. Database population, concurrent application work and retained heap
were outside the measurement. A complete application run must still demonstrate
that it remains within the existing memory guard while retaining every snapshot
and decision. Smaller blocks change storage granularity and allocation behavior;
they do not authorize another provider call or change any analysis limit.

The Maven-owned profile regression reads the shipped configuration and checks the
active native compression and block-size settings, explicit URL overrides, and
full Unicode-CLOB/binary-BLOB integrity after updates through fresh JDBC
connections. It checks the text through both direct JDBC reads and Hibernate's
CLOB/NCLOB extraction helper. The block-size assertion fails with the prior
32 KiB profile and passes with the new MEM default.

## Complete checkpoint evidence within the stored-payload limit

Each question retains its complete original prompt and diagnostic JSON. New
question rows use the explicit `analysis-question-payload-v1` object in
`node_codes`; the former JSON array still means an unchanged legacy plaintext
row. Prompt contents never determine their storage encoding. Both layouts are
readable, and an unknown layout or damaged payload fails visibly.

The versioned envelopes compress the original Java UTF-16 code units and retain
their original length and SHA-256. This preserves every code unit, including
control characters, combining characters and unpaired surrogates. Inflation is
bounded before copying, and both GZIP integrity and the original text hash are
checked. The existing limit of 2,000,000 characters applies to each complete
decoded prompt and detail field, including a reconstructed detail with a prompt
reference. Compression cannot bypass that limit.

When a diagnostic's prompt exactly duplicates the question prompt and the
original JSON string token matches the fixed version-one escaping, the detail
envelope may reference that prompt. It retains the exact surrounding JSON
fragments. Decoding splices the original token back into those fragments; it
does not reserialize a current DTO. Future DTO or serializer changes therefore
cannot silently change historical evidence. A null or different diagnostic
prompt, or an ineligible JSON representation, is retained as a complete field.

The run budget remains 24,000,000 characters in the actually stored
`prompt_text` and `detail_json` fields, including their envelope overhead.
The existing counter does not include `node_codes`. A write that upgrades a
legacy question changes both fields, its layout and the counter atomically.
Accepted replay, status reads and state-only changes do not rewrite historical
payloads. Existing legacy runs are not silently migrated or given a new budget.

Corrective-question scans exclude terminal `SUCCESS` and `SUPERSEDED` history
before evaluating the original eligible-state set. These redundant exclusions
avoid HSQL's allocating temporary `IN`-table comparisons for terminal history;
the eligible states, complete inputs, ownership checks and state transitions are
unchanged.

Portable analysis archives retain schema version 1 and contain the complete
original plaintext prompt, original diagnostic JSON and legacy node array.
Capture decodes one question at a time. Restore validates each logical field
before compression and checks the actual stored total before writing the
aggregate; it retains the existing review requirement and creates no execution
authority. This preserves the wire representation, not full downgrade
compatibility: older application releases cannot edit the new database layout
and may reject an archive whose decoded aggregate exceeds their old plaintext
storage limit. A database containing new-format rows requires a reader that
understands this explicit version.

## Deliberate boundary

This project integration enables continuation only for **local execution in the
original owned, active workspace**. The Artemis project path does not yet have a
compatible advancing source/call grant protocol for this action. Selecting it
returns an explicit unavailable reason; opening a local journal must never
silently bypass the configured cluster executor. Existing shared or central
project analyses retain their current execution path and cannot claim this owned
workspace continuation capability.

No client polling loop automatically submits `CONTINUE`. Both the Copilot result
surface and the project job surface expose the explicit action and keep partial
results available for review.

## Reviewed module dependencies

The dependency ratchet records eleven concrete additional class pairs across six
package edges; its matching rule and every unrelated count remain unchanged.
Both local and frozen cluster relation computation read the catalogue-owned
`RelationAssessmentDescriptions` value. The portfolio uses the analysis-owned
continuation service and execution lifecycle, reads its snapshot result record,
and calls the pure relation-question-key helper. It does not call continuation
repository or store mutations directly. Its additional workspace reads establish
the original owner, workspace and shared/archive status before admitting work.

| Package edge | Previous count | Reviewed count |
| --- | ---: | ---: |
| `analysis.cluster` → `catalog.service` | 4 | 5 |
| `analysis.relations` → `catalog.service` | 2 | 3 |
| `portfolio.service` → `analysis.recovery` | 0 | 5 |
| `portfolio.service` → `analysis.service` | 8 | 9 |
| `portfolio.service` → `workspace.model` | 1 | 2 |
| `portfolio.service` → `workspace.service` | 33 | 35 |

The analysis-service increment is the existing interruption-reason enum, used to
keep memory, cancellation and time failures distinct from continuable budget
stops. None of these dependencies introduces a reverse module dependency or a new
architectural exception.
