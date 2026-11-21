# Architecture focus quality implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Repair the architecture lens's reproduced context/state defects, improve evidence-grounded suggestions, and verify the actual user workflow.

**Architecture:** Preserve the existing focus session, server suggestion engine, exact-revision editor command port and immutable source workbench. Repairs remain at their owning boundaries.

**Tech Stack:** Java 25, Spring Boot, Maven, modular JavaScript, existing browser verification.

**Spec:** `docs/superpowers/specs/2026-11-21-focus-quality-design.md`

## Global constraints

- No invented catalogue identities, silent relation acceptance, quota increase, gate exclusion or assertion weakening.
- Keep actual context routing, source hashes, journal identities, readonly snapshots and manual model content authoritative.
- Work in this isolated worktree; root coordinates publishing and integrated Maven execution.

## Review focus

Private `__shared__` routing and response context comparison; independent investigations sharing a triple; successful empty assessments; availability changes with queued/running work; accepted/dismissed history and stale sources.

## Task 1: Session correctness

Files: `taxonomy-app/src/main/resources/static/js/architecture-focus.js`, `taxonomy-app/src/test/resources/architecture-focus-session.cjs`, and the existing source-lens/shared-renderer tests only where their real contract is exercised.

Interface: `ArchitectureFocusSession` keeps its existing public methods and wire request shape. Preserve a non-enumerable literal workspace pin when copying working contexts. Match the response's semantic Context separately from this transport-only pin. Track investigation ownership internally for replacement decisions.

- [ ] Add failing cases for the four reproduced defects, plus independent and accepted suggestions.
- [ ] Run the failures and retain the original assertion messages.
- [ ] Repair context copying/identity, assessed-result replacement and canceled queued-node state.
- [ ] Verify existing and added session/browser fixture tests with no weakened assertions.
- [ ] Document exact behavior and return an uncommitted diff for independent review.

## Task 2: Evidence-grounded candidates

- [ ] Complete and review the concrete candidate/evidence design against actual retained source DTOs.
- [ ] Write regression cases for unrelated pairs, shared boilerplate, bounded counterpart diversity and frozen requirement evidence.
- [ ] Implement the reviewed narrow server/context change and verify its Maven-owned tests.

## Task 3: Application acceptance and publication

- [ ] Exercise a real working editor and retained snapshot through HTTP/browser with source identity and stable layout checks.
- [ ] Verify adoption, replay and undo, preserving accepted evidence.
- [ ] Inspect fresh screenshots, document limitations, run relevant integrated and canonical gates.
- [ ] Independent whole-diff review; publish a focused PR after fixing material findings.
