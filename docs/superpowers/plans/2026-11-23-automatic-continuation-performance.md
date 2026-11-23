# Automatic Continuation Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Reduce repeated immutable snapshot binding while retaining all validation and continuation authority, and prove the fresh assistant relay path.

**Architecture:** A continuation-only reader returns compact immutable policy inputs validated through the existing full DTO. A bounded memo requires each current CLOB to match the validated bytes. Live journal and authorization checks remain independent and fresh.

**Tech Stack:** Java 25, Spring/Hibernate, Jackson 3, JUnit/Maven, HSQLDB and existing hosted database/browser gates. Temporary Python standard-library HTTP relay for QA.

**Spec:** docs/superpowers/specs/2026-11-23-automatic-continuation-performance-design.md

## Global Constraints

- Exact source/actor/workspace/project/job/requirement/version and current journal checks remain enforced.
- Maximum 16 memo entries and conservative aggregate 2 MiB input retention; no full JSON/result retention and no static cache retaining SessionFactory instances.
- Publish fingerprints only from the fully validated read pass and only after successful reader close and CLOB release.
- No timeout, heap, provider budget, assertion, verification selector, architecture/coverage/security baseline weakening.
- No remote paid model fallback; fresh relay integration and inference performance are separate claims.
- Raw data stays under target; no assisted-by footers or large evidence commits.

## Review Focus

- Corrupt same-ID/same-length replacement must fail rather than hit a cached decision.
- Later authorization, journal, provider or cancellation changes must still govern the next read/prepare/begin.
- Legacy/null/model-binding inputs and exception precedence must match the existing policy.
- Late JDBC read/close/free failures must be surfaced even on a memo hit.
- Concurrency, changed SessionFactory and oversized inputs must remain isolated and bounded.

### Task 1: Establish evidence (root and QA agents)

- [x] Recover historical live-bridge setup and run fresh opt-in dialogue through production transport; audit hashes and stop bridge.
- [x] Run 66 baseline policy/evidence/real-HSQL tests and warmed full-binding benchmark with JFR.
- [x] Record reproducible baseline, approach decision and useful application metrics in concise QA documentation.

### Task 2: Validated continuation reader (implementer)

**Files:** existing ProjectAnalysisContinuationService and PortfolioSnapshotEvidenceReader; one purpose-specific immutable input class; corresponding existing portfolio policy/evidence/HSQL tests.

- [x] Add behavioral red tests for repeated reads, corruption, identity, current-state changes and cleanup; confirm expected failures on base.
- [x] Introduce compact immutable policy inputs and one shared decision implementation without premature evaluation.
- [x] Add bounded validated-content memo and continuation-only reader with complete CLOB consumption and resource cleanup.
- [x] Switch only the three immutable-snapshot continuation read paths; leave journal and full snapshot APIs intact.
- [x] Run focused tests and report cold versus warm binding behavior; the independent measurement agent compares real CLOB A/B allocation, time and queries on the resulting code.
- [x] Commit the cohesive implementation with measured evidence.

### Task 3: Review and release (root)

- [ ] Independent review of authority, input parity, exact-content verification, memory bounds and resource lifecycle; fix findings.
- [x] Measure real CLOB A/B allocation, time, query count and characters with a long-lived reader and fresh persistence contexts. Verify hits on the actual final payload and disclose mismatch overhead.
- [x] Keep fresh-relay QA and application benchmark scope explicit in documentation.
- [ ] Publish reviewed branch, run all current required checks, resolve failures and expected-head squash merge.
- [ ] Verify main tree and report measured gains plus remaining measurement limits.
