# Automatic runtime efficiency

This increment removes repeated checkpoint SQL, bounds operation-status object materialization and avoids constructing a discarded distributed report. It builds on main `135de85effa06398732fb340fc01f1903172038a`. It does not change model prompts, provider budgets, schema or payload formats. The broader programme is recorded in the [design](../superpowers/specs/2026-11-23-automatic-performance-program-design.md).

## Checkpoints and the durable relation ledger

Real HSQL transactions and a Hibernate statement inspector establish these isolated paths:

| Path | Before | After | Preserved boundary |
| --- | --- | --- | --- |
| Initialized relation-call debit | 3 statements | 2 statements | Locked owner read, expiry check after the lock, claim-fenced update |
| Accepted local answer after the provider callback | 6 statements | 5 statements | Committed checkpoint, then the caller's fresh claim-scoped state read |
| Initialized ledger consumption | Separate ledger fetch after owner validation | Scalar from the locked owner projection | Negative-value rejection; only a null value triggers legacy bootstrap |

The store now returns the outcome of its completed checkpoint write. A rejected success at the existing storage limit still pauses the owner and retains a failed question with no accepted detail. The local adapter can therefore stop immediately without issuing another state query. The caller still checks current state after commit; a different transaction that pauses or cancels the run remains observable before the answer returns.

The corrected RED run executed 46 tests with exactly two expected statement-count failures. The candidate run executed 163 tests with no failures, errors or skips. It covered real quota rejection, durable `ATTEMPT` before the provider callback, no active database transaction in that callback, independently committed `PAUSED` and `CANCELLED` states, positive/null/negative ledgers, legacy compressed bootstrap, authority, failure recovery and backup. The controller independently checked the ten Surefire XML reports. The task review approved the change without critical or important findings.

## Operation status

The operation ID remains the existing 64-character lowercase hexadecimal value. All 144 canonical v1 key variants are generated with the existing encoder. Lookup first enforces project/workspace access, including for malformed operation IDs. Canonical and compatibility queries return only scalar job IDs and keys. Strict Java parsing and exact operation comparison select the jobs whose existing header/item projection is materialized. Every item of each selected job remains available to the original consistency checks.

The compatibility query preserves directly imported keys with leading or trailing whitespace accepted by `String.strip()`. It searches for the literal operation ID, then applies the same strict parser. Database case, accent or padding collation cannot turn a permissive key match into unrelated item materialization.

| Unrelated history in real HSQL fixture | Original job/item rows returned | Candidate job/item rows returned |
| --- | --- | --- |
| 4 jobs | 16 | 3 |
| 96 jobs | 200 | 3 |

Both candidate cases return two canonical scalar candidates and five literal scalar candidates before exact filtering and deduplication. These are measured row/materialization counts, not an end-to-end latency claim. The initial focused candidate run executed 88 tests across six classes with no failures, errors or skips. The bind-limit regression first failed on the previous single 261-ID query. After bounded batching, all 43 real HSQL status tests passed; they verify that all 261 selected jobs/items, cancellation and duplicate-pass conflict remain visible, with at most 258 parameters per hydration query including project and scope. The six current XML reports cover 89 distinct cases, all passing; the unchanged classes were not rerun after the isolated batching addition.

This path deliberately uses two scalar lookups plus the selected-job projection in the ordinary case. It does not claim fewer statements. HSQL 2.7.3 `EXPLAIN` selects the existing `UQ_JOB_IDEMPOTENCY` index but uses only project/scope as start conditions; the `IN` and `LOCATE` predicates are residual filters. The compatibility path can still scan the scoped keys. Large directly imported matching histories use bounded ID batches to stay within supported database parameter limits; no matches are silently truncated.

## Distributed result validation

Both worker completion paths now call `RelationSearchDistribution.validateResults`, which shares the deterministic scheduler, evidence validation and inexpensive report-admission checks with `combine`. The previous code discarded the returned report. Every prior-result read and scheduler pass remains. A persisted work row is not sufficient proof of its original allowance: the tests change an already-issued grant within its bounds and return a matching result. Success and failure both reject it before writing the result or settling the delivery. Restoring the original grant allows both retries to commit.

The initial baseline executed 33 tests with 32 passes and the expected lookup error for the not-yet-added API. Review then identified rejection checks hidden in report construction: a null unfinished reason encountered at the exhausted frontier, unrepresentable cumulative charges and incomplete source progress fields. The regression run reproduced seven assertion failures, including three real HSQL completion transactions that incorrectly committed before correction. The final implementation passed all 44 tests with no failures, errors or skips. It preserves the historical pending-wave/short-circuit acceptance cases and the exact representable cumulative boundary. The suite also covers reverse-order completions, exact duplicate delivery, conflicting evidence, changed preparation/root/ordinal/allowance, scarce budgets, rollback and final report equality.

The opt-in [probe source](probes/RelationValidationProbe.java) runs both production methods over the same synthetic partial wave: 48 sources, 96 grants, 72 completed grants in reverse order, 216 trace observations, 72 verified edges and 24 pending items. Three fresh Java 25 processes each used a 512 MiB heap, 3,000 warm-up calls per path and seven alternating-order trials of 1,000 calls per path. The Linux x86_64 sandbox reported an AMD EPYC 9V74 host; the probe did not pin a CPU or set `ActiveProcessorCount`. Allocation comes from HotSpot's current-thread allocation counter. These timings describe this shared environment.

| Fresh process | Full aggregation median | Validation median | Full aggregation allocated bytes/call | Validation allocated bytes/call |
| --- | --- | --- | --- | --- |
| 1 | 94.0 µs | 13.7 µs | 201,024 | 31,560 |
| 2 | 80.9 µs | 12.2 µs | 199,768 | 31,520 |
| 3 | 79.9 µs | 12.1 µs | 200,952 | 31,520 |

This fixture shows approximately 84% less allocation for the isolated validation call. Plan/results are already decoded in both conditions. It does not measure database reads, worker latency or model inference. Repeated history reads and scheduler reconstruction can still accumulate quadratic work across many completions.

## Reproduction and release evidence

The local runtime is Temurin Java 25.0.5 with the repository Maven proxy. Mockito is supplied through `JAVA_TOOL_OPTIONS` so the normal JaCoCo agent remains enabled. No timeout, memory gate, model-call budget, assertion or coverage threshold was relaxed. Raw build logs and SQL plans remain outside version control.

```sh
./mvnw -B -ntp -pl taxonomy-analysis -am test \
  -Dtest=RelationCallLedgerTest,RecoveryCheckpointCompressionTest,AnalysisCheckpointDelegationTest,RecoveryDurabilityTest,RelationBudgetLegacyContinuationTest,AnalysisContinuationAuthoritySecurityTest,PartialAnalysisResilienceTest,RecoveryCheckpointPersistenceTest,AnalysisExecutionFailureCheckpointTest,RelationCallLedgerBackupTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false

./mvnw -B -ntp -pl taxonomy-portfolio -am test \
  -Dtest=CopilotOperationKeyTest,PortfolioStatusPayloadReadTest,PortfolioJobStatusConsistencyProbeTest,CopilotOperationConsistencyTest,CopilotAutomationCancellationTest,CopilotAutomationServiceCoverageTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false
```

The local runs used the equivalent environment-specific Maven proxy and an explicit Mockito agent. The focused verification covers 296 distinct cases across the three tasks, including the affected-class follow-up after bounded ID batching and the final distributed-validation regressions. The branch receives independent whole-branch review before PR publication; hosted checks on its exact head then gate the merge. No overall workflow-speed or live vLLM inference claim follows from these measurements.

Run the distribution suite, then reproduce the allocation probe without a test or coverage agent:

```sh
./mvnw -B -ntp -pl taxonomy-analysis -am test \
  -Dtest=DistributedRelationSearchTest,ClusterRelationWorkerPersistenceTest,ClusterRelationCoordinatorTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dmaven.build.cache.enabled=false

probe_classpath=$(python3 - <<'PY'
import xml.etree.ElementTree as E
r = E.parse('taxonomy-analysis/target/surefire-reports/TEST-com.taxonomy.analysis.relations.DistributedRelationSearchTest.xml')
print(r.find("./properties/property[@name='java.class.path']").get('value'))
PY
)
for replica in 1 2 3; do
  java -Xms512m -Xmx512m -cp "$probe_classpath" docs/qa/probes/RelationValidationProbe.java
done
```
