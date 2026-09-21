# Provider transport usage observation

Plan: `docs/superpowers/plans/2026-09-20-requirement-reformulation.md`, task 5.
Base: `759de0483d6ca17e5e92a0f7d200de6b7a23e3c2` (PR #1098).
This incremental branch is stacked on #1098 and does not mutate that branch.

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
- Additional boundary checks cover network errors, ambiguous JSON, zero/long-limit
  values, partial metadata, actual callback failure, empty capture and scope cleanup.
  The supplemental workflow now runs ALL analysis and upstream unit tests, not just
  the new tests. Its outcome must be read before reporting that regression run green.

The initial local execution services were unavailable (TransportTimeoutError even
for echo). They later recovered, but external source/dependency resolution still
failed. The actual Maven execution evidence is GitHub Actions, not an invented local
run. The workflow has read-only repository permissions, no provider secrets and no
push/merge action. It preserves JUnit reports and the exact public source tree.

MockRestServiceServer replaces only HTTP responses in the transport contract tests;
real gateways/retry/parser code execute. The parallel test uses the existing real
loopback gateway/engine fixture. No cloud LLM is called. Test token numbers and
requirements are authored fixtures, not live-model quality evidence.

## Remaining integration and limits

This first slice is the transport observation foundation, NOT persistent per-run
accounting or a new UI counter. No application run is subscribed by default yet.
The next integration must bind observations to the exact run/lease, record started
attempts before provider work, handle unknown outcomes after process death and retain
late usage evidence without allowing a stale worker to publish a proposal. Only then
can the UI expose measured counts with explicit completeness and replay/cache meaning.
Checkpoint counters keep their existing, different meaning.

All original requirement versions, active architecture, proposal text and decisions
remain unchanged. Retention, large reconciliation-review inputs, explicit adoption
and final civilian acceptance remain separate plan items. No new dependency, schema,
coverage threshold or architecture-baseline exception is introduced by this slice.

The canonical `./mvnw verify -DexcludedGroups='real-llm'` remains required before
merge. Passing this unit-test job is not full-reactor/database/browser/coverage
approval. The predecessor #1098 still has its own independently evaluated gates.
