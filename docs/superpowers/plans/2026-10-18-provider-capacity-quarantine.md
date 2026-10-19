# Provider capacity quarantine implementation plan

> Implement with superpowers:executing-plans. Preserve all existing Maven/JUnit gates.

**Goal:** retain uncertain HTTP capacity durably after worker or broker loss.
**Architecture:** transport-neutral lifecycle hooks, a persistent receipt in the
existing Artemis permit queue, and explicit administrative reconciliation.
**Tech stack:** Java 21, Jakarta JMS/Artemis, existing Maven/JUnit.
**Spec:** ../specs/2026-10-18-provider-capacity-quarantine.md

- [x] Add regression cases for admission ordering, terminal responses, failures,
  observer isolation and one permit per physical retry; show failures first.
  The directly executed case bodies are not a JUnit/Maven run.
- [x] Wire the lifecycle through LlmRequestAdmission and LlmTransportMeter.
  Preserve it through the real metrics wrapper; four additional failures were
  reproduced before adding the lifecycle-aware close observer.
- [x] Implement atomic available-to-in-flight conversion and exact-receipt return.
- [x] Add bounded inspection and operator-confirmed, nonduplicating reconciliation.
- [x] Add real persistent-broker/loopback-HTTP loss and restart regressions.
  Written for the existing JUnit owner; not executed in the restricted environment.
- [x] Verify compilation of the six production classes, original source hashes,
  49 directly executed case bodies, Java syntax and patch application. This is
  not a full JUnit/Maven or broker result. See the external delivery report.
- [ ] Run the full Maven reactor, broker integration tests and required CI before merge.

No reduced assertions, exclusions, timeout/coverage changes or workflow additions.
