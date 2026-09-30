# Analysis work progress

Approved scope: the user's 2026-09-30 request to implement the discussed node and
relationship progress plan. Extend the existing traversal, progress registry and
durable continuation; do not introduce another search engine.

## Contract

- Freeze the node denominator from the catalogue at analysis start. Report direct
  assessments, descendants excluded by a successful zero assessment, and open
  nodes separately, both overall and by taxonomy. Failed answers never complete
  work. Retries and checkpoint replay never count a node twice. Preview eviction
  must not change counters.
- Before relationship extraction, plan every concrete positive source against
  each target taxonomy supported by the existing typed compatibility rules.
  Preserve current incoming/outgoing semantics and explicitly permitted same-root
  dependencies. A score alone never proves a relationship.
- A source/target-taxonomy task completes only after all its contribution/type/
  direction searches finish (or the source contribution is explicitly rejected).
  Unresolved answers, invalid responses and resource limits remain open.
- Source and call limits bound new work per execution, not the plan. Continuation
  reuses validated relation responses under the existing owner/workspace/input
  checks. A budget stop retains the complete denominator and completed evidence.
- Show source, target taxonomy, relation type/direction, navigation/verification,
  depth, completed tasks, unresolved tasks, proposals and new calls separately.
  Generic provider phases must not hide the relationship phase.
- Keep DE/EN presentation and the viewport feedback, final report and persisted
  recovery consistent. Existing reports without progress remain readable.

## Implementation plan

1. Add immutable node/relationship progress DTOs and a node tracker in the analysis
   module. Connect planning and accepted/cached scores to AnalysisRunControl and
   AnalysisProgressRegistry. Test >8192 scores, repeats, zero-pruning and failures.
2. Plan all source/target pairs in RequirementRelationSearch; add engine callbacks
   for current work and completed searches. Persist the final task plan in
   RelationSearchReport. Test multiple types per pair, rejection, unresolved and
   budget-limited work, and >32 eligible sources.
3. Extend AnalysisCheckpointSession's existing durable question protocol to
   validated relation exchanges. Isolate relation failures from node coverage.
   Count only newly executed exchanges against each execution's budgets. Test
   resume with no repeated successful calls, invalid-answer retry and isolation.
4. Render counters and current work in the live panel and viewport feedback, and
   retain the plan in final reports. Run behavioural JS tests and Maven analysis,
   recovery and relation tests; review the complete diff; publish a PR.

## Review focus

Repeated and cached answers; large preview eviction; zero ancestors with failed
children; budget exhaustion between navigation and verification; malformed and
skipped relation answers; owner/workspace isolation; old JSON compatibility.

## Evidence / execution ledger

- Base a0da6b20e1980ae2e71d4100b2d1bbd951802a1d, isolated fresh checkout and feature branch.
- Baseline JS: 59 tests passed (live progress and relation report).
- Ruling: preserve same-taxonomy routes explicitly allowed by compatibility rules;
  removing these technical dependency searches would alter existing semantics.
- Ruling: reuse the existing bounded question journal, including its storage limits,
  rather than add a second persistence subsystem.
- Independent review found and the regression suite reproduced three defects:
  loss of earlier verified evidence on a later extraction failure, skipped answers
  hiding independent budget-limited work, and replay consuming the work-item limit.
  All three were corrected. The semantic relation phase also now survives generic
  provider headings.
- The one reviewed dependency increase is analysis.relations →
  knowledge/relations.service (2 → 3 class edges): RelationWorkPlan consults the
  existing RelationCompatibilityMatrix, exactly as RequirementRelationSearch does.
  No reverse dependency or architectural-rule exception is added.
- Java regression tests cover 9,000 scores with an 8,192-entry preview, failed
  fallback zeros, pruning, 40 sources with a 32-source execution limit, multiple
  types per task, budget/work-limit continuation without repeated provider calls,
  skip-then-continue, interrupted evidence retention and old/new JSON snapshots.
- UI contract suite: 700 tests passed. The complete reactor test was attempted;
  its Docker-backed ReformulationBrowserTest cannot run in this environment
  (`Could not find a valid Docker environment`). This is not a full-suite pass.
- Final analysis module suite: 675 tests passed. Targeted application architecture
  and template/i18n gates: 38 tests passed, including the exact dependency ratchet
  and cycle rules. The focused regression set was rerun with those gates on the
  final Java sources. `git diff --check` passed.
- Local Java 21 used Mockito's startup agent because self-attachment is unavailable
  in this execution environment. No project test skip or weakened rule was added.

## PR #1153 CI correction

- CI exposed four reformulation scenario failures shared by the core, scenario
  and database lanes. The complete relation plan needs 32 calls for these authored
  fixtures; the default 24-call budget correctly returned PARTIAL with eight
  unfinished searches before the reformulation assertions could run. Both
  authored scenarios reproduced this failure locally.
- Give the reformulation lifecycle fixture the same explicit 256-call test budget
  as ScenarioArchitectureAcceptanceTest. Keep the production default, SUCCESS
  requirement and budget/continuation contracts intact. Also assert that the saved
  relation report has a positive plan, every task completed, no pending or unresolved
  tasks, no unfinished search batches, and no warnings or stop reason.
- Focused Java 21 verification: 59 relation integration tests, 17 fixture playback
  tests, both authored scenarios and the full restart/export lifecycle passed.
  All four scenario snapshots, including the browser prerequisite, record 48/48
  completed tasks after 32 calls. The browser scenario then failed to start Chrome
  because this local environment denies its socket creation; browser acceptance
  therefore still requires CI. No browser assertion or test was skipped in code.
- The required Maven verification also deliberately rejects draft pull requests.
  Publish this correction with the pull request ready for review so that the new
  commit can satisfy that existing gate.
