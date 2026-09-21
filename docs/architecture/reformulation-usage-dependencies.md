# Reviewed usage-recording dependencies and verification

The exact-head `2f9fdf2` usage workflow (35641256880, artifact 10659116616)
executed 2,248 tests in its archived reports: 2,247 passed, one failed, no errors
or skips. All new admission, persistence, actual-worker and forced-process-death
JUnit tests passed. The sole failure was the unchanged
`ArchitectureContextDependencyRatchetTest.managedContextDependenciesMatchReviewedBaseline`.
This is not a claim that the canonical full-reactor or database matrix passed.

The test generated exactly these three changes to already permitted package edges:

| Package edge | Previous | Actual |
| --- | ---: | ---: |
| composition.reformulation -> analysis.service | 7 | 16 |
| composition.reformulation -> portfolio.reformulation | 24 | 31 |
| portfolio.reformulation -> workspace.service | 5 | 6 |

The new composition adapter must translate optional transport events into durable
Portfolio-owned recording under the existing workspace identity. The concrete new
class pairs are:

- `ReformulationExecutionService` -> `LlmTransportMeter`, `LlmTransportMeter.Journal`,
  `LlmTransportMeter.Scope` (3).
- Its journal implementation, `ReformulationExecutionService$1`, ->
  `LlmTransportMeter.Attempt`, `.Journal`, `.Observation`, `.Outcome`, `.Source`,
  `.Usage` (6).
- `ReformulationExecutionService` -> `ReformulationUsageService` (1).
- `ReformulationExecutionService$1` -> `ReformulationUsageService`,
  `ReformulationUsageService.Start`, `.Completion`, `ReformulationRecoveryService.Claim` (4).
- `ReformulationProgressController` -> `ReformulationUsageService`,
  `ReformulationUsageService.Summary` (2).
- `ReformulationUsageService` -> `WorkspaceContext` (1).

The adapter remains in application composition. Portfolio owns persistence and
authorization, Analysis owns transport instrumentation, and Workspace is not made
to depend on any feature implementation. No new Maven dependency, context,
allow-list exception, test threshold or checker change is introduced.
`jdeps` supports the class-pair review but also reports nesting metadata that
ArchUnit does not count. The committed inventory is taken from the actual ArchUnit
report, not from those larger bytecode totals. The complete generated inventory
was parsed and compared with the replacement baseline; only the three counters
above changed. Current-head CI must still rerun the unchanged check.

The artifact SHA-256 is
`44c96cd2dd5862c5abdf46d3f1e126debda9ae5876713d1c05ab7bdefa086ee1`.
It contains the complete source, logs and 311 JUnit XML reports. Nine new tests in
this slice passed: three durable transport checks, three validation checks, one
two-process service/persistence check and two worker checks including forced kill.
Earlier observation tests are also retained and passing in this run.

## Review correction: visible error evidence

Copilot comment 4065408557 identified that the API exposed `httpErrors`,
`transportErrors` and `invalidUsage`, but the panel did not display them. The
production panel now renders all three with German and English labels. The
existing actual-adapter/production-action contract uses nonzero values (1, 2, 3)
and asserts each is visible. It failed before the correction with
`HTTP error evidence must be visible` and passes after it. Existing stale-response,
large-integer, draft-protection, pagination, markup and cancellation checks remain.
No counter is inferred from checkpoints and no error is relabelled as success.

Identical start writes remain idempotent only while their captured lease is valid.
The active-run/owner check deliberately precedes duplicate lookup: a stale worker
must not treat an old admission as authority to send new work after cancellation.
Late receipt recording has a separate, narrower path. This is a deliberate safety
boundary, not a reason to bypass authorization for old start IDs.

This is author dependency review and recorded test evidence, not independent
approval of the whole branch. Broader recovery/shutdown and large-input review
concerns remain separate acceptance work; no successful merge is claimed.
