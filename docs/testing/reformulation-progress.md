# Inspecting saved reformulation progress and partial results

This package-5 increment adds read-only inspection to the existing offer workspace.
It does not adopt a proposal, answer its questions, change a requirement version,
write the architecture, start model work or change run/checkpoint persistence.

## User behavior

Each displayed run, including cancelled or failed runs, has **Saved partial
results / Gespeicherte Teilergebnisse**. The panel shows the exact source requirement
version, source proposal revision, run state, creation time and last newly saved
checkpoint time. Its metadata list is paginated; a complete typed result is fetched
only when selected. Node, subgroup, parent aggregate and rewording outputs show text,
questions, choices, original discovery evidence, unresolved source spans and retained
reference IDs. Reconciliation checkpoints show their affected sections, source
resolutions and findings. All of these remain explicitly unreviewed partial results,
not a reconstructed complete offer or approval.

Two counts have deliberately narrow meanings:

- `runCheckpointCount`: checkpoint records first stored by the selected run.
- `proposalCheckpointCount`: checkpoint records available across this exact offer.

These are not architecture node counts, model requests, progress percentages,
token usage or money. Parent wrappers and subgroups are separate checkpoint records.
A retry can reuse old records without increasing either count. Failed attempts and
provider repair requests are not inferred from successful checkpoints. The UI says
so; it does not invent a completion denominator or a cost estimate. Actual usage
accounting and a retention policy remain separate work. No history is deleted here.

The panel is independent of editable text and question drafts. Late responses are
fenced by selected offer/run and detail-request generation. Unchanged background
polling preserves the detail DOM. Untrusted strings use textContent, including
model-generated markup. The panel never offers an adoption or answer action.

## Read contracts

The base is the existing project/requirement/offer path followed by
`/synthesis-runs/{runId}/progress`.

- `GET ?limit=20&after={checkpointId}` returns metadata and the two counts.
  Limit is 1..50. The cursor is validated inside the exact run and scope; ordering
  uses creation time followed by the stable ID, including equal timestamps.
- `GET /checkpoints/{checkpointId}` returns one typed, immutable node or
  reconciliation result and its source revision. It is not resolved against a
  newer manual revision. Unsupported/legacy untyped payloads return a safe 409,
  never arbitrary raw data or provider prompts.

Both responses use Cache-Control: no-store. Existing requirement authorization is
rechecked before scoped proposal/run queries. Foreign workspaces, wrong offers,
wrong runs and out-of-run cursors cannot read the result. Metadata queries select
checkpoint identifiers/types/times, not checkpoint result payloads. Authorization
selects the proposal's source-version ID instead of loading its full baseline and
current revision. The existing selected run envelope is still read for its state.
Progress is a live observation, not an atomic completion certification.

## Verification

`ReformulationProgressTest` runs the same plain Java checks in fresh application
JVMs and a Node control contract. Java checks use real Spring, authentication,
HTTP, file-backed HSQL persistence, workspace provisioning and production endpoints.
Only the stored analysis/checkpoint fixtures are authored; no live LLM is called.
The catalog root is obtained from the real TaxonomyService.

Observed RED: missing progress endpoint (404), and a browser-DOM fixture with no
inspection control. A later regression showed full proposal/revision entity loads
during polling; scalar authorization fixed it and Hibernate statistics verify it.

The final checks cover empty and populated runs; all five checkpoint kinds;
pagination including equal timestamps; safe incompatible-output errors; unauthenticated,
foreign-workspace, wrong-run and wrong-cursor access; unchanged original/version
history and proposal; preservation of a newer manual revision; cancellation;
old results not counted as work of a new run; and identical inspection after a
complete application restart. The actual API adapter and UI action code are used
in the Node contract with only fetch/DOM boundaries supplied. The prior cancellation
contract retains every assertion and supplies the new read-only progress collaborator.

Supplemental local Chromium DOM checks executed the entire workspace script and
actual API adapter with an injected route/fetch fixture, retaining unsaved text and
rendering hostile markup as text. Browser navigation is disabled in this environment;
this is not a full browser-to-server acceptance result. The separate Java HTTP checks
exercise the real server. Java 21 source compilation and both JVM phases passed on
CI runtime tree `072427ed4ff3f309769fc652004bb00746fb5bad` (the 6c08375 PR merge tree).

The isolated workspace has no complete Maven checkout/wrapper or dependency cache.
The attempted canonical `./mvnw verify -DexcludedGroups='real-llm'` exits 127 there.
Full reactor/JUnit, coverage, ArchUnit, database and browser suites remain exact-head
CI gates. Standalone evidence and author self-review do not replace independent review.

## Reviewed dependency increment

No Maven dependency, migration, execution guard or test threshold changes. Exactly
six new cross-context class pairs extend three already permitted inventory entries:

| Package direction | Count | Added source/target pairs |
| --- | --- | --- |
| composition.reformulation -> portfolio.reformulation | 21 -> 24 | ProgressController -> ProgressService, ProgressService.Progress, ProgressService.Partial |
| composition.reformulation -> workspace.service | 7 -> 9 | ProgressController -> WorkspaceResolver, WorkspaceContext |
| portfolio.reformulation -> workspace.service | 4 -> 5 | ProgressService -> WorkspaceContext |

Names in the table abbreviate their `Reformulation` prefix. Bytecode dependencies
confirm these exact pairs. This records the intentional read composition; the
ratchet itself, dependency directions and unrelated entries stay unchanged.
