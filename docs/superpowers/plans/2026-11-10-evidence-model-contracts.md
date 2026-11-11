# Evidence Model Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Implement the reviewed project lessons as coherent, extensible model, evidence, merge, exchange and evaluation contracts.

**Architecture:** Keep the modular monolith and its existing Git/editor/projection authorities. Centralize pure semantic contracts in the DSL foundation, use explicit evidence at feature boundaries, and replace ambiguous consumers together.

**Tech Stack:** Java 25, Maven Wrapper, Spring Boot, JUnit, existing JavaScript editor renderer and Maven-owned browser contracts.

**Spec:** `docs/adr/0017-evidence-model-contracts.md`.

## Global Constraints

- Java 25; Maven Wrapper owns compilation, tests and verification.
- Extend existing modules and boundaries; no parallel graph store, editor, validation engine, Python product tooling or duplicate CI selector.
- No compatibility adapters solely for obsolete contracts. Update all owned consumers together. Preserve actual identity, authorization and provenance.
- Use the real catalogue for application acceptance. Authored unit fixtures are labelled as such. No paid or shared remote model calls during this work.
- Do not weaken assertions, coverage, security gates or timeouts.
- Independent changes in PRs 1438 and 1440 remain separately owned. Integrate qualified main changes and preserve overlapping transport/tooling work; do not import an unqualified parallel branch wholesale. PR 1440 is integrated through main `47d94664f`; PR 1438 remains separately owned.

## Review Focus

- A forbidden relation source must never become an unrestricted search domain.
- Incomplete evidence must not become either a confirmed gap or a quality success.
- A concurrent type change and property edit must retain one semantic identity.
- A selected branch change during graph read or preview acceptance must fail closed.
- Diagram-only edits must not delete the represented semantic element.

## Task 1: Shared metamodel and evidence-based obligations

**Files:** DSL validation rules and tests; knowledge relation compatibility/candidate services and tests; architecture gap/recommendation services, controllers and tests; associated gap/recommendation DTOs. Do not edit graph query services or browser resources in this task.

**Interfaces:** Introduce one public framework-free metamodel in `com.taxonomy.dsl.validation`, with a version and explicit target-domain state; make `DslValidator` and `RelationCompatibilityMatrix` delegate to it. Gap requests select an explicit obligation profile and evidence completeness; results include rule/profile identity and SATISFIED, VIOLATED or INCONCLUSIVE judgments. Preserve no misleading 'expected allowed relation' API.

- [x] Add regression tests proving forbidden-source candidate queries do no retrieval, RELATED_TO is unrestricted, and validator/service rules agree.
- [x] Add gap tests proving an allowed relation alone creates no obligation, alternative target types satisfy one cardinality, incomplete evidence is inconclusive, and a high score is not accepted evidence.
- [x] Run the selected Maven tests and retain the expected failing assertions.
- [x] Implement the single rule contract, explicit profiles/evaluation, and replace all Java consumers; remove duplicate rule tables and ambiguous empty-set APIs.
- [x] Run DSL, knowledge and architecture tests through Maven; report exact commands and results. Commit the task.

## Task 2: Stable identity and semantic collection merge

**Files:** `taxonomy-dsl` AST identity, semantic patch/diff/merge implementation and corresponding tests. Do not edit validation/metamodel files owned by Task 1.

**Interfaces:** A single kind-aware identity contract is consumed by `TaxDslSemanticMerger` and `ArchitectureSemanticPatch`. Mutable type/header values are merged as values, not identities. Known view membership is an unordered set; unknown/ordered repeated properties remain atomic.

- [x] Add failing tests for concurrent type/property changes, independent membership additions and removals, deletion versus modification, unknown extension preservation and conflicting ordered repeated values.
- [x] Run the focused Maven test selection and confirm the intended failures.
- [x] Implement shared identity and three-way merge of declared unordered collections, retaining final validation and meaningful conflicts.
- [x] Run the complete DSL suite. Commit and describe any affected downstream API.

## Task 3: Context-bound graph answers

**Files:** architecture graph query controller/services/tests; graph result DTOs in domain; smallest necessary read-contract addition to knowledge. Do not edit gap/recommendation or metamodel files.

**Interfaces:** Use existing authorized repository selection and `RelationProjectionReadService`/readiness proofs. Every graph response identifies repository, workspace scope, branch, exact commit, read authority, traversal policy and bounded completeness. Traversed relationships retain source provenance/evidence. HTTP callers must explicitly choose a context; no silent primary-context fallback.

- [x] Add failing tests for two branches with different graphs, stale/unbuilt projections, head movement, hop/score limits, empty results and edge evidence.
- [x] Run the tests and verify their failures describe the missing contracts.
- [x] Implement selected-context traversal from one frozen authoritative snapshot, propagate proof metadata and update all Java callers.
- [x] Run graph/authority/service tests through Maven. Commit and document the browser request contract for Task 4.

## Task 4: Coherent browser presentation and graphical reconciliation

**Files:** existing lifecycle/editor projection and renderer, analysis/portfolio graph-gap UI and their Maven-owned JavaScript/browser contracts.

**Interfaces:** Consume Tasks 1 and 3 contracts using the named API transport on the integrated current branch. Reconciliation uses the frozen `ReconciliationPreview` and existing editor projection/renderer; acceptance fingerprints and command history stay unchanged.

- [x] Add failing UI contracts for inconclusive results, selected-context requests, same-preview diagrams and stale-preview rejection.
- [x] Show property differences and affected graph scenes for local/incoming/proposed state using the existing renderer and stable layout.
- [x] Replace misleading confirmed/missing labels and surface query limits/provenance without exposing implementation jargon in ordinary flows.
- [x] Run the applicable Maven-owned UI contracts/browser profile. Commit.

## Task 5: External profile provenance and exchange operation corpus

**Files:** DSL ADMBw profile, Sparx mapping/plugin and exchange tests/resources; existing interop operation contracts where required.

**Interfaces:** One immutable source-profile descriptor records source revision/hash, profile/generator version, supported subset and native mapping. General QEA reads retain diagram-link visibility/identity. Operation fixtures distinguish semantic objects from occurrences and declare faithful, lossy or unsupported outcomes.

- [x] Add failing tests for source-profile parity/drift and per-diagram connector visibility roundtrip.
- [x] Add explicit operation fixtures for rename, package move, repeated occurrence, occurrence deletion, geometry move, endpoint change and concurrent local edit.
- [x] Implement shared provenance and native mapping consumption; preserve frozen-basis hashes and GUID protections. Unsupported operations fail explicitly.
- [x] Run DSL/Sparx/export/interop tests through Maven. Commit with a precise support matrix.

## Task 6: Reproducible verification artifacts

**Files:** current Java interoperability product runner, its artifact resolver/manifest and Maven tests; workflow adapter only if required.

**Interfaces:** Use the existing Java tooling entry point and PR 1440's qualified current Archi distribution. Resolver accepts a pinned immutable artifact descriptor and verified preseed/cache/download sources. Output records actual artifact hash and producer/profile. Do not duplicate PR 1438's separately owned migration.

- [x] Add failing tests for unavailable upstream with valid cached bytes, corrupted cache, incorrect local override, partial download and producer mismatch.
- [x] Implement bounded download and atomic hash-verified cache population, clear offline failure and provenance receipts within existing tooling.
- [x] Run tooling tests and actual changed command where available; do not create another verification selector. Commit.

## Task 7: Independent architecture-quality corpus

**Files:** existing scenario-quality evaluators/tests and versioned scenario resources; concise evaluation documentation.

**Interfaces:** Extend existing evaluators with a versioned authored reference contract. Cover clear, alternative, incomplete, contradictory, update-existing and no-extra-element cases. Report missing obligations, unjustified relations and uncertainty separately; runtime/cost/human effort remain unavailable unless measured.

- [x] Add independent reference cases and failing negative evaluator tests; use real catalogue codes wherever the application catalogue is exercised.
- [x] Extend the existing quality evaluator and Maven-owned corpus checks. Do not infer expectations from generated answers or turn unavailable inference into success.
- [x] Run the deterministic corpus, record its exact scope, and document the separately executable bounded live/human assessment. Commit.

## Task 8: Integration, review and release-ready verification

**Files:** integration fixes, README and the existing developer/evaluation references only as needed.

- [x] Integrate independent task commits and current upstream changes without duplicating PRs 1438/1440.
- [x] Review every task for specification compliance and code quality; resolve concrete findings with regression tests.
- [ ] Run `./mvnw -B verify -Pci -DrunOnnxTests=true` with Java 25 and the required available runtime prerequisites.
- [ ] Run existing hosted gates on the exact reviewed head, resolve review threads, and publish a coherent reviewable PR with evidence and explicit external limitations.
- [x] Update this plan and the concise implementation documentation to match observed results.

### Evidence at integration

The checked implementation tasks above record their focused test and review
execution. They do not assert that a native browser or full CI run passed.
The full Maven-owned JavaScript contract passed 2,528 tests after the main merge
and scoped-pattern UI correction, with no failures, errors or skips. The focused
identity, metamodel/obligation, graph, reconciliation, exchange, artifact-resolver
and independent-corpus suites have separate retained reports.

Integration review also required scoped live pattern detection and current
portable-reference closure; these corrections use the same evidence and identity
boundaries instead of restoring removed DTO aliases. The scoped-pattern Java
correction passed 53 distinct selected tests across its final two commands,
including 14 database-backed pattern checks. The portable-reference correction
passed 13 tests including the production membership lookup with custom canonical
IDs and genuine catalogue references. Both corrections passed independent review.
The original model-only qualification included main changes through `47d94664f`.
The current PR 1442 also integrates the API and authentication work from PRs 1438
and 1441. Its combined tree requires fresh qualification; the earlier isolated
results are retained as focused evidence, not inherited integration passes.

Local canonical verification exposed environment prerequisites: a process-scoped
Java font configuration is needed for the existing export assertions, and nested
Maven fixtures need the same proxy/settings and local repository as their parent.
The unchanged two export assertions and nine nested-Maven tests passed after
those environment corrections. Native Chromium startup is restricted in this
workspace. No local focused result substitutes for the existing hosted browser,
database, product, coverage or security gates.

The change PR records the final source commit/tree, original CI evidence and
remaining external limitations. Hosted qualification is an operational gate
against that published source, not a result inferred from this checklist.
