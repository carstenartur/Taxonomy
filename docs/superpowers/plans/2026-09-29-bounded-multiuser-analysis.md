# Bounded multi-user analysis implementation plan

> For agentic workers: use superpowers:executing-plans for inline execution.

**Goal:** distinguish queued and running full analyses, bound per-user live work and every physical provider attempt, and preserve scoped results/cancellation.

**Architecture:** extend AnalysisProgressRegistry and the existing gateways; do not introduce a queue service or replace portfolio persistence. Pure policy cores remain independent of Spring; adapters use existing run control and transport metering.

**Tech stack:** Java 21, Spring, Jackson 3, Node test runner and existing JUnit/Maven lifecycle.

**Spec:** [operator and scope contract](../../en/MULTIUSER_ANALYSIS.md).

## Constraints and review focus

Keep user/workspace/branch/repository isolation, immutable requirement evidence,
existing retry/claim semantics and final-prompt budget enforcement. Never contact
external providers in regression tests. Preserve all canonical CI gates.
Review cancellation before registration/worker attachment, stale workers after
queue expiry, retry accounting, shared 429 cooldown and cleanup of released owners.

## Task 1: live admission and observation

- [x] Add AnalysisAdmissionQueue with typed state/rejection/limits, separate running and queued capacities, per-user limits, ready-owner round-robin and idempotent release.
- [x] Integrate reservations/worker admission with AnalysisProgressRegistry; include QUEUED and execution/queue timing without changing existing elapsed-time semantics.
- [x] Stop canceled or expired reservations before any late worker can invoke HTTP; preserve thread-local cleanup and terminal status.
- [x] Reproduce old queued-state/cancellation behavior and retain durable-backpressure regressions with explicit queue sizes.

## Task 2: actual HTTP attempts

- [x] Add ProviderRequestLimiter and ProviderRetryPolicy with physical-attempt RPM admission, bounded concurrency/backlog/wait, and Retry-After handling.
- [x] Use LlmRequestAdmission in both GeminiGateway and OpenAiCompatibleGateway, inside the retry loop and outside held persistence transactions.
- [x] Configure raw transports once in LlmGatewayRegistry, preserving production budget wrappers.
- [x] Exercise real loopback HTTP, reversed responses, provider concurrency, retry admission and permanent quota handling.

## Task 3: user-visible waiting and operator settings

- [x] Update taxonomy-analysis-progress.js to distinguish queue/provider waits, retain cancellation and separate queue/execution time.
- [x] Reproduce four failures against the exact original UI blob, then pass all six new Node cases; retain ambiguity-safe cancellation.
- [x] Add opt-in multiuser-analysis profile; leave existing deployment worker defaults unchanged.
- [x] Add English/German configuration and explicit single-process / non-durable-direct-POST / non-TPM boundaries.

## Acceptance

- [x] Compile changed production sources with Java 21 and the actual application dependencies.
- [x] Execute 29 pure Java checks, 10 real Spring/HTTP checks and six new Node checks locally.
- [x] Register the same checks in JUnit and the existing npm CI scripts.
- [ ] Run full canonical `./mvnw verify -DexcludedGroups="real-llm"`, all existing UI contracts and required PR checks on the published candidate.
- [ ] Independent review and merge decision after all required candidate checks pass.

This plan records a bounded first implementation. A single fair persistent dispatcher
across existing executor queues, restart-safe direct 202 jobs, shared multi-pod leases
and token-throughput budgeting are not implemented by these changes.
