# Initial integrations navigation evidence implementation plan

**Goal:** Close the observed browser-audit gap with complete passive evidence for one initial navigation, preserving all actual error blockers.

**Spec:** `docs/superpowers/specs/2026-11-23-integration-navigation-evidence-design.md`.

**Base:** merged runtime main `3aba1558d093c73d91744e78790faa2de740d5bf`. Worktree `.worktrees/integration-navigation-evidence`, branch `fix/integration-navigation-evidence-20261123`.

**Global constraints:** No application JS, endpoint behavior, polling or timer changes; no generic CORS/network suppression, added network requests, timeout increases, reduced scenarios or gate bypass. One source implementer at a time. The packed Task4 owns Java/Maven/benchmark CPU until released; this task waits before execution and uses focused Node tests. Root owns docs/publication and the user has authorized fixes/PRs/merge. No assisted-by footer.

## Task 1: Observe and classify the initial integration transition

**Files:** `.github/scripts/ui-navigation-poll-evidence.mjs`, `.github/scripts/ui-navigation-poll-evidence.test.mjs`, `.github/scripts/integration-acceptance.mjs`, `.github/scripts/ui-role-state-acceptance.mjs`. Prefer a focused new `.github/scripts/ui-integration-navigation-evidence.mjs` and `.test.mjs` for the independent classifier if it avoids further growing the existing large classifier; keep only passive collection/dispatch in the shared owner. A narrowly scoped helper-wiring test may be added beside those scripts. Root owns QA/design/plan updates.

1. Read the spec and controller diagnosis including the exact17-key departure/4-key destination mapping. The actual profiles wire request was absent from the old artifact; collect and verify it on future executions rather than presenting a prediction as evidence.
2. Add a focused RED through the current observer/reconciliation entry point for the exact departure order with complete healthy old startup and original destination profiles/render evidence. It should fail because no initial integrations proof exists. Add table-driven negatives for wrong origin/path/scope/header/duplicate or extra destination query keys, old-vs-source workspace confusion, stale/mismatched document, undeclared/reload/canceled navigation, ordering, forged native frames, missing or mismatched startup/profile DOM adoption, duplicate profile identities, redirects, pending/unparsed/failed reads, later requestfailed, native errors/CSP events and duplicate error IDs. Keep existing predicate tests as controls; avoid timing assertions.
3. Add optional initial-navigation callbacks to `runIntegrationAcceptance`, using a declaration immediately before its first page.goto and an end callback in try/finally. Verify the first destination after original200 profiles and the matching rendered option/ready controls, before creation, mutation or reload. Hook the callbacks into the role-state observer and normal error-ledger reconciliation. Preserve USER/read-only/403 behavior: it supplies no proof when the success contract is unavailable, and existing assertions still apply. Do not silently route later reloads through this new kind.
4. Extend the passive observer only as required to collect the original profiles request/body, original source startup render and the first destination profile DOM. No interception/replacement/retry/network call. Record unique document identity and sequence attribution, actual query/header scope and exact source/destination declaration. Preserve current body-task settlement and failure veto behavior.
5. Implement the independent strict classifier to the spec. Use the existing error ledger and include every proof component in the reconciliation report. A global startup health observation from the same epoch may precede private adoption; its earlier query must never authorize private destination scope. The declaration, exact later source URL and real profiles request prove current scope separately. Keep source provenance pins distinct. Re-check live read failure/pending states on each classification; do not cache a prior success for reuse.
6. Run focused RED/GREEN during iteration, then the complete affected Node selector below once. Read actual executed totals and retain logs. Add a minimal helper-wiring case that checks declaration and verification occur around the actual first navigation and before integration actions/reloads, including a failed navigation. No local Maven/browser run while packed measurements own CPU. Root will inspect hosted live evidence on the exact published head. Self-review diff, `git diff --check`, commit only scoped scripts/tests and write `task-1-report.md` in this plan's SDD ledger with commands/counts/limitations. Stop and ask root about a concrete proof defect instead of relaxing the spec.

```sh
node --test .github/scripts/ui-navigation-poll-evidence.test.mjs \
  .github/scripts/ui-integration-navigation-evidence.test.mjs \
  .github/scripts/navigation-poll-lifecycle.test.mjs \
  .github/scripts/integration-startup.test.mjs \
  .github/scripts/ui-role-state-http-failure-policy.test.mjs
```

Omit only the new classifier test filename if the same cases were intentionally placed in the existing suite; include any new helper-wiring test. Do not run browser-named scripts as ordinary Node tests without their actual harness. Root dispatches one independent whole-branch review for this single cohesive task before publication, then exact-head hosted gates and authorized merge.
