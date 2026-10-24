# Release 1.5.1 blocker implementation

Spec: `../specs/2026-10-24-release-blockers-design.md`.

## Global constraints

- Use the existing isolated release worktree and native Maven/JUnit/CI gates.
- Preserve browser security, principal identity modes, tenant authorization,
  review CAS/replay and after-commit publication semantics.
- No default-branch guessing, silent data deletion, historical migration edits,
  fabricated successful search results, new workflows or duplicate test runners.
- Changes must be reviewable and validated before updating the release request.
- Preparing the release request does not execute its publication workflow.

## Task 1: enforce JWT audience

Use the existing six-method real-decoder red proof. Add the Keycloak audience
property, update shipped/test audience mappers and bilingual setup/migration
instructions. Adapt existing local signed-token fixtures. Prove correct and
list-valued audiences pass; wrong/missing audience and azp-only do not; issuer,
signature and expiry checks survive. Prove custom client/audience behavior with
the actual configured decoder. Run the full existing bearer owner and affected
focused security owners. Preserve real-provider test mode; native container CI
must verify mapper output. No decoder replacement or new runtime dependency.

## Task 2: enforce hypothesis branch identity end to end

Follow the reviewed bounded design in the release-preparation design note.
Extend real persistence/service tests with same repository/workspace and
overlapping main/draft identities. Carry branch through the two entities,
repositories, lifecycle/review/linking services, materialization and summary.
Narrow unscoped read APIs and migrate fixtures without reopening productive
bypasses. Preserve the existing publication port; correct its caller's target.
Add V43 and the equivalent portable upgrade with deterministic linked-provenance
backfill and fail-closed ambiguity. Verify known/ambiguous retained schemas,
evidence constraints, branch uniqueness, restart, rollback and foreign-ID review.
Run affected existing owners; the native database lanes own real-dialect proof.

## Task 3: reject graph search without exact source authority

First use the existing real-Lucene ready-model fixture to reproduce branch-blind
results. Pass canonical RepositoryContext through controller/facade/service.
Reuse relation count/source selection and fresh final source/head checks. Keep
the existing authorized central fallback and proven empty projections useful;
return unavailable for unproved nonempty or unready projection scopes. Test
main/draft, stale head and source transition during inference, plus existing
model/tenant isolation. Update relevant help/status wording and release limits.

## Task 4: integrate and prepare the existing release request

Review each task and the complete diff. Publish the fixes through the existing
PR/check process, resolve actual feedback and obtain required exact-source
checks. Update release notes with verified changes and concrete migration
requirements. Refresh main and PR1287; recreate its sole release-request commit
on the final source using the exact values from the spec and a guarded ref
update. Run existing request/metadata validators and inspect CI/review state.
Stop before publication unless the user has separately authorized that action.
