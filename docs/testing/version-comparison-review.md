# Version comparison review — 22 September 2026

Follow-up to `version-comparison-ergonomics.md` and PR #1102.

The unchanged initial head `f7f4153` completed the canonical core Maven check and
all six UI shards in run 35660935859. Only the final aggregate stopped at its
intentional draft guard. Artifact 10666808201 has SHA-256
`acf4c7c59b1c78ef48b8188b72388f39499b5108fda4153f3a78a1982bfa2be1`.
The real ADMIN comparison screenshot and its error-free report were inspected.
This is historical evidence, not certification of the following review changes.

## Review corrections

- Accept only the 13 actual Java SemanticChangeType values, with the matching
  element/relation category. Unsupported strings are not treated as MODIFY.
- Convert both element before/after values to strings before the shared HTML
  escape function. Numeric zero and boolean false remain visible.
- Execute the actual production utility module in the Node harness, instead of
  a friendlier escape-function test double. The new zero-value test failed first.
- Allocate a UUID-based QA branch name before setup. Cleanup runs after success,
  partial setup failure and assertion failure; delete only that allocated ref
  and verify its absence. The suite already uses disposable primary applications,
  but explicit cleanup also protects repeated execution in one test application.
  Preserve both the original error and any cleanup failure.

33 Node tests pass locally, including the original 23 and the new rejection,
Java-enum alignment, real-escaping and cleanup tests. Nine new cases failed
before their respective fixes. All three uploaded code blobs match the locally
executed files exactly. No protected ref, dependency, timeout or gate changed.

Canonical Maven verification was attempted again but cannot download Maven 3.9.16
in the local environment. The full Node contract chain stops at missing
`@axe-core/playwright`; normal npm ci fails with EAI_AGAIN. These limitations
are not code-pass claims. New-head CI, real ADMIN branch-cleanup acceptance and
review remain required before merging.
