# Task 2a — independent review

Reviewed range: 363618842d905807689f2b9b932d9642bd49c5d9 through
cbe2aa28ef54c5ca0323309a68ea789b4e064d7c. Read-only review; no repeated tests.

Spec compliance: changes required. Task quality: focused implementation and module
boundaries sound for tested valid payloads, with two material missing edge cases.

- High: `ReformulationPositiveReviewGuard.java:40-47` queries and compares imported
  project/requirement business keys case-sensitively. Existing materialization uses
  case-insensitive identity (`PortfolioGitService` and `ReformulationEvidenceCodec`).
  A valid conflict-bearing P/R document imported into existing p/r with matching
  current version and text hash is stored but missed by the positive-review query.
  Preserve original portable bytes/hash while matching the established identity.
- Medium: `ReformulationService.java:336-339` dereferences deserialized baseline
  without a null check. Persisted JSON `null` yields 500 and malformed JSON maps to
  422, instead of generic 409 for invalid persisted baseline. Failure must not expose
  payload content and must not alter unrelated codec contracts globally.

Root inspected the cited code and confirmed the mismatch and null/error path.
Reported focused GREEN before correction: 48 positive tests. This does not cover
the two newly identified paths. Future-task coordination docs are outside review.

Fix round 1 pending: add meaningful RED regressions for both paths, minimal fixes,
one covering GREEN, then scoped re-review. Do not repeat unrelated full suites.
