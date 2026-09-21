# Provider transport usage observation

Plan: `docs/superpowers/plans/2026-09-20-requirement-reformulation.md`, task 5.
Initial base: `759de0483d6ca17e5e92a0f7d200de6b7a23e3c2` (PR #1098).
PR #1101 originally stacked on #1098 and now targets main after that PR was
squash-merged. The earlier base synchronization preserved its corrected dependency
inventory and documentation without changing application behavior.

## Implemented foundation

The optional `LlmTransportMeter` observer is attached to the real exchange inside
both provider gateways. One gateway invocation gets an identity, while each HTTP
attempt carries its own retry index. A parser-repair request is a new invocation.
Transport errors and rate limits retain their original exceptions and retry policy.
HTTP success is not a claim that the returned formulation passes semantic/schema
validation. The observation is emitted before downstream parsing or recording.

Recording replay is an explicit separate source and carries no historic token
numbers as current usage. Configuration/budget rejection before exchange and
checkpoint reuse do not invent HTTP attempts. An attempted client exchange does
not prove the provider received or billed a request. No billing inference is made.

Provider-reported input, output, total, cached-input and reasoning token fields are
kept separately. Unknown values remain null, a reported zero stays zero. Negative,
non-integral, out-of-range or structurally invalid metadata is flagged; valid fields
remain available. Reported totals are never recomputed by adding components.

The event contains no prompt, response text, URL, credential or exception message.
A failed observer cannot replace the provider result or trigger another paid request.
Failures of the observer are therefore a possible observation gap; this is expressly
best-effort telemetry, not an authoritative accounting ledger.

The observer scope nests and restores on the owning thread. Parallel walk-up tasks
capture it explicitly alongside the selected provider; pooled workers do not inherit
unrelated scopes. Consumers must support concurrent callback delivery. Without a
subscriber, gateways do not parse token metadata or emit events.

## TDD and verification ledger

- `42e0103`: scaffold and eight tests, before gateway emission. The first CI exposed
  two fixture errors (custom URL shape and nested Mockito stubbing); those were fixed
  without counting them as meaningful RED.
- `298be16`: actual Maven run 35626460403 compiled the ten-module selected reactor
  and ran nine tests: **eight assertion failures for absent observations, zero errors,
  zero skipped**. The existing provider operations themselves completed as intended.
- `20b98ef`: gateway emission and parallel scope capture. The same nine tests passed
  in Actions run 35627345947, including transport retry versus parser repair,
  Gemini/OpenAI metadata, replay separation, missing/invalid usage, observer failure
  isolation and four serial plus four parallel real-gateway calls.
- `5000e08`: six further boundary tests and the broad analysis/upstream regression
  run 35627839403 completed successfully. The 305 archived JUnit reports contain
  **2,215 tests, zero failures, zero errors, zero skipped**, including all 15 new tests.
  The executed command is `./mvnw -B -ntp -pl taxonomy-analysis -am test`.
  The source archive identifies PR merge commit `592656a80c78b03ace835674f2fcfb85bd837614`.
  Artifact 10652634949 contains the source, full Maven log and JUnit XML reports.
  Artifact SHA-256: `cdc23a2d4141fdc30351fe7e153cab29fbcb3eaa8e86a26fae1bd646acf53563`.
- Base synchronization changes only the existing architecture inventory and its
  documentation, plus this ledger. Production and test code remain exactly those
  exercised by the 2,215-test run; new-head CI is still evaluated separately.

Additional boundary checks cover network errors, ambiguous JSON, zero/long-limit
values, partial metadata, actual callback failure, empty capture and scope cleanup.
The broad unit-test job covers analysis, architecture, domain, DSL, export, knowledge,
templates and workspace. It is not the complete application/build/coverage reactor.

Initial local execution services returned TransportTimeoutError even for echo.
After they recovered, the complete source was recovered from the verified CI archive.
The local canonical verification attempt could not fetch Maven 3.9.16 from
`downloads.apache.org` and exited 1 before compilation. Therefore the actual Maven
execution evidence is GitHub Actions, not an invented local reactor run. The
supplemental workflow has read-only repository permissions, no provider secrets
and no push/merge action. It preserves JUnit reports and the exact public source.

MockRestServiceServer replaces only HTTP responses in the transport contract tests;
real gateways/retry/parser code execute. The parallel test uses the existing real
loopback gateway/engine fixture. No cloud LLM is called. Test token numbers and
requirements are authored fixtures, not live-model quality evidence.

## Integration status and limits

The transport-only foundation described above is historical: the current PR now
installs the durable journal in the real reformulation worker, persists run-owned
attempt starts and receipts, exposes the authenticated `/usage` endpoint, and
renders DE/EN counters. See `reformulation-usage-persistence.md` for current
recording, crash/unknown-outcome, late-receipt and UI semantics and tests. The
original observation consumer remains optional; it is not a replacement for the
worker's durable journal. Checkpoint counts retain their different meaning.

Original requirement versions, active architecture, user proposal text and
answers remain unchanged by accounting. Retention, large reconciliation inputs,
explicit adoption and final acceptance remain separate plan items. The historical
transport foundation introduced no migration; durable recording subsequently
added V26 as documented in the persistence report.

The canonical `./mvnw -B verify -Pci` remains required before merge. Focused
unit-test evidence is not full-reactor/database/browser/coverage approval. The
supplemental Maven profile writes to its own report directory, and both phases
are archived separately. Reports from the first phase cannot satisfy the second
phase's checks.
Author self-review is not independent approval.
