# Requirement reformulation — completion and recovery plan

Status: implementation in progress, not product acceptance.
Source specification: the user's approved eight-package bottom-up reformulation plan (2026-09-20).
Recovery base: `025197b3193f4a86815408a61ed6fb3368967259` (includes analysis recovery PR #1133).
Branch: `feature/reformulation-durable-completion`.

## Non-negotiable constraints

- Proposal generation, saved drafts, questions and answers never change the active requirement or architecture.
- Adoption remains a separate previewed, scoped, version-checked, idempotent command.
- Preserve original text, source anchors, questions, decisions, provenance and manual edits.
- Model-generated repetition is not user approval. Rejected wording must not silently reappear.
- Reanalysis of adopted wording retains ancestry, decisions and review states.
- Historical exports read frozen evidence, not live architecture, catalogue or LLM state.
- Preserve module boundaries, provider budgets, scope isolation and existing checkpoint boundaries.
- Remote model playback replaces only outbound LLM replies; use real application paths.
- Real-language quality is a separate gate, never inferred from playback or schema success.

## Durability protocol

1. Work only on this feature branch. Never force-push or merge automatically.
2. Before any long test or switch to another task, commit and push the current bounded change as WIP if necessary. WIP is not reviewed or merge-ready.
3. Confirm the remote branch SHA after every push; record it in the controller response/progress.
4. Keep this plan, task briefs, outcome summaries and next exact command versioned on the branch. Do not rely on ignored agent memory.
5. After focused green tests and independent review, append the evidence and push again.
6. If remote persistence fails, stop new implementation immediately and report the blocker. Do not accumulate hours of local-only work.
7. No secrets, full user data, provider credentials, cache directories or build output in commits.
8. Limit unsaved editing batches to one coherent change; send the controller a checkpoint request at least every 10 minutes.
9. Resume from the remotely verified commit and ledger; do not repeat completed work.

## Recovery facts

The previous temporary worktree disappeared after an execution-environment outage.
Five local completion commits were not published; their recorded hashes are not recoverable on GitHub.
Earlier packages already merged into main must not be reimplemented.
Prior locally reported green test counts are historical notes, not evidence for reconstructed code.

## Tasks

### Task 1: Suppress rejected generated wording
- Inspect the existing rejection, synthesis, reconciliation and coverage paths.
- Write failing regression tests first: rejected statement remains evidence but not active detail or ancestor summary; a later model reply cannot reintroduce exact rejected wording.
- Enforce the same rule across parser/full synthesis/reconciliation/coverage using existing contracts.
- Keep semantic paraphrase limits explicit; do not claim a semantic guarantee.
- Focused tests, independent review, durable push.

### Task 2: Preserve adopted lineage and review guards
- Add content-addressed nonrecursive ancestry with stable portable evidence; preserve v1 bytes/hash compatibility.
- Use strict schema/hash/closure/duplicate-key/trailing-input validation; reject duplicate roots.
- Keep adopted source distinct from original/user approval. Carry origins, reviews, questions, answers and rationales into new prompts.
- Exclude raw archives and obsolete source spans from prompts; fail visibly if context is too large.
- Version checkpoint input fingerprints so lossy cached results cannot be reused.
- Block positive review for exact-current scoped adopted blocking conflicts/structural errors; permit ordinary requirements and nonblocking open/deferred questions.
- TDD, focused tests, independent review, durable push.

### Task 3: Frozen DOCX export
- Support exact proposal revision and adoption-receipt DOCX with correct MIME, disposition, no-store/nosniff and binary hash.
- Use an export-owned frozen model/port and an architecture adapter, not new portfolio-to-architecture-report dependencies.
- Build architecture solely from frozen snapshot bytes, details, catalogue and mappings; validate IDs, directions, multiplicity and scope.
- Keep workspace branch and analysis basedOnBranch distinct, each bound to its own immutable source; bind baseline scope to the physical persisted proposal scope.
- Preserve frozen gap details and distinguish unavailable from explicitly empty analysis.
- Include readable DE/EN text, questions, provenance, links and graph; parse with a real DOCX parser and inspect an independent render.
- TDD, review, durable push. No ratchet increases without an explicit recorded justification.

### Task 4: Civilian acceptance and quality comparison
- Extend semantic order-independent playback by task/source/node/child/edge/answer identities; unknown/ambiguous calls are fatal.
- Preserve the sourced civilian flood case; authored time-recording and precise terminal/no-browser/numeric cases are labelled test assumptions.
- Real path: analysis -> stored snapshot -> bottom-up -> merged/separate questions -> answers/deferral -> targeted revision -> explicit adoption -> reanalysis/ancestry -> four exports -> checkpoint -> fresh-process restart.
- Initial execution evidence is NODE/RECONCILE; targeted regeneration uses REWORD.
- Keep production budgets; use explicitly authored zero remote scores to select relevant process/application/service fronts, not prepared graphs.
- Desktop and 390px browser checks, keyboard/focus/live progress/draft preservation/download and real screenshots.
- Add explicit profile/suite/CI selectors, DE/EN help, README and architecture documentation.
- Separate real-provider walk-up versus one-prompt comparison with five authored cases, usage/time/schema/quality rubric. No provider means explicit NOT RUN, never a quality claim.
- Independent review and final whole-branch verification.

## Preflight

| Tasks | Shared interface | Finding |
|---|---|---|
| 1 / 2 | statement provenance, synthesis prompts | rejection and inherited decisions must be included without granting approval |
| 2 / 3 | portable evidence and historical report model | report reads frozen ancestry; no live fallback |
| 2 / 4 | adopted baseline and checkpoints | E2E must inspect inherited decisions, not only archive presence |
| 3 / 4 | export routes and snapshot branch identity | real analysis may originate from draft while current workspace is main |
| 1 | tests versus behavior | exact wording suppression is testable; semantic paraphrases are not guaranteed |
| 2 | tests versus behavior | review guard applies only to matching current scoped adoption |
| 3 | tests versus behavior | unavailable gaps differ from an empty inventory |
| 4 | tests versus behavior | playback proves safety and plumbing, not model intelligence |

Ruling: persist WIP checkpoints before long verification — explicitly requested data-loss protection takes precedence over keeping a branch always green — cost if wrong: extra WIP history, but no mutation of main.
