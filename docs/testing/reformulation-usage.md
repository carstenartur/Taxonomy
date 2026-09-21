# Transport usage observation: execution ledger

Plan: `docs/superpowers/plans/2026-09-20-requirement-reformulation.md`, task 5.
Base: `759de0483d6ca17e5e92a0f7d200de6b7a23e3c2` (PR #1098).

This incremental branch does not mutate #1098 or the active requirement. The first
commit introduces a scoped observation contract and tests BEFORE gateway emission.
The gateway tests must fail with missing observations until emission is wired.
No claim of implemented metering or passing tests is made at this test-first stage.

Ruling: start with the real HTTP/replay boundary, not checkpoint counts. One
logical gateway call may retry transport, and a parser repair is another logical
invocation. Replayed usage belongs to an earlier request and is not current spend.
Missing/invalid provider usage is unknown, never zero. Reported token totals are
retained, not inferred from components or converted to money.

The scoped observation carries only provider, invocation ID, attempt index,
HTTP/replay kind, status/outcome, monotonic elapsed time and reported numeric usage.
It must never carry prompts, responses, URL query credentials or exception messages.
Consumers must be thread-safe when explicitly captured into parallel child work.
This first slice is observation, NOT a restart-safe accounting ledger or billing
reconciliation. Persistence, unknown in-flight outcomes and UI aggregation are
separate implementation steps; old checkpoint counters keep their current meaning.

The local container and Python execution services returned TransportTimeoutError,
including for a plain echo. Therefore the supplemental GitHub Actions contract
executes the actual Maven tests remotely. It does not bypass or replace the full
canonical verification command, any coverage threshold or database gate. Its shell
commands cannot be claimed locally tested; Actions logs and positive JUnit counts
are the execution evidence. The full reactor remains required before merge.

Test transport uses Spring's mock HTTP server with the real gateway/retry/parser
logic; it never contacts a cloud LLM and needs no provider key. Test requirements
and token numbers are authored fixtures, not evidence of live model behavior.
