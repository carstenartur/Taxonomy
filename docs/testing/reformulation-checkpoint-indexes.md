# Run-scoped checkpoint inspection indexes

Package-5 follow-up to merged PR #1101 (`549d3f7`, source tree `17f75c2`).
This change indexes the existing saved-partial-result queries. It does not change
their predicates, pagination, data, authorization, retention or adoption behavior.

## Indexes and scope

| Index | Ordered columns | Existing read path |
| --- | --- | --- |
| `idx_reform_cp_run_order` | `run_id, created_at, id` | Run-specific chronological/keyset pages and last-checkpoint time |
| `idx_reform_cp_run_kind` | `run_id, task_kind` | Run-specific counts grouped by checkpoint kind |
| `idx_reform_checkpoint_proposal` (retained) | `proposal_id, scope_key` | Counts across an authorized proposal |

Run IDs are globally unique in `reformulation_run`; checkpoint rows reference that
primary key. The narrow new indexes do not need to repeat the potentially long
scope key. Every existing query still checks the proposal, scope and run. An index
is not an authorization mechanism. Full result payloads are deliberately absent
from index keys and metadata pages still avoid those payloads.

PostgreSQL migration V27 only creates the two non-unique indexes. V1--V26 remain
unchanged. Standard transactional Flyway index creation can lock the table while
building the indexes; this is not a zero-downtime online-index migration. Hibernate
schema-update databases use the same definitions in the entity's `@Table` mapping.
No uniqueness rule, table, foreign key, query or retention/deletion policy changes.

## Regression coverage

`ReformulationCheckpointIndexContract` reads real JDBC index metadata and checks
all three names, ordered columns and non-unique behavior. It also checks the JPA
mapping against those independently specified expected definitions. The existing
progress application checks call it on both creation and restart; PostgreSQL's
fresh-install and baseline-adoption migration tests call the same contract and
now require migration history through 27, preserving every prior assertion.

`ReformulationProgressTest.existingResultsAndQuestionsSurviveSchemaIndexUpdate`
uses its private temporary file database, stores the normal five partial outputs,
and removes only the two new indexes to simulate the prior index layout. A fresh
application process must restore them while retaining the original requirement,
manual proposal revision, question IDs, source revision and partial output text.
The normal pagination test includes equal timestamps and verifies no repeated or
lost result. Existing foreign-workspace, wrong-run, unauthenticated access,
no-store, and no-payload-in-metadata assertions remain intact.

## Evidence from this implementation

Tests were changed first. On the unchanged green production classes, the actual
Spring application failed with `Missing checkpoint inspection index`. After the
entity change, a fresh Java 21 compilation succeeded; the write/legacy-index
fixture exited zero with `REFORMULATION_INDEX_UPGRADE_FIXTURE_OK` and
`REFORMULATION_PROGRESS_HTTP_OK`. A second fresh process exited zero with
`REFORMULATION_PROGRESS_RESTART_OK` and verified restored indexes and unchanged
saved evidence. These plain checks are called by the normal JUnit wrapper.

The unchanged actual-API/production-progress Node contract and cancellation API
contract also pass. Production code other than the index annotations is unchanged.
One initial combined terminal invocation was interrupted by the tool's time limit;
the successful repetitions used the existing 180-second per-process test budget,
without changing the test timeout or product behavior.

The local classpath is the exact green #1101 application artifact (10670380386),
with only the changed entity and test checks compiled as an overlay. The canonical
`./mvnw -B verify -Pci` attempt cannot download the pinned Maven 3.9.16 distribution
in this environment; no successful full local Maven/JUnit or PostgreSQL run is
claimed. Current-head full CI, database compatibility and review remain gates.
No measured latency/speedup or PostgreSQL query-plan result is claimed here.
