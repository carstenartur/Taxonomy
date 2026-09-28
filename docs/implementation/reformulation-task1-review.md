# Task 1 — independent review

Reviewed range: 1685182f19d8d3d5d8822baf4e4c986629aa1009 through
47f72a9ce973045b49b83d864ce3fa9c2bc37bc4. Read-only independent review;
reported green suites were not redundantly rerun.

Spec compliance: changes needed. Task quality: two focused defects.

- High: RejectedWordingGuard.java:8,22-25 substitutes the fixed sentence
  `Summary withheld: rejected wording requires review.`. A valid rejected addition
  with wording `requires review` makes the replacement itself repeat rejected text.
  The substituted summary needs the same guarantee. NodeSynthesisResult/Section
  allow an empty summary, while the existing conflict finding explains withholding.
- Medium: FrozenReformulationEngine.java:158-161 uses unconditional put in affected
  synthesis. A same-ID replay replaces retained HUMAN rejection metadata with MODEL.
  Complete synthesis and reconciliation already preserve prior records via
  putIfAbsent. Preserve the exact retained record in the document and node results.

Checks outside the diff: nonblank Statement wording validation, deterministic parser
ID assignment, and ReformulationService REJECT behavior. No edits or repeated tests
were performed by the reviewer.

## Fix round 1 — accepted

Scoped re-review: 47f72a9ce973045b49b83d864ce3fa9c2bc37bc4 through
b2c9a4c24acf950100674ca594b53ff045a28451. Both findings are addressed; no new
breakage was found in the fix diff. No repeated tests or edits by the reviewer.

- Contaminated summaries are now empty, accompanied by the existing conflict
  finding. A regression covers the rejected phrase `requires review` itself.
- Affected synthesis retains the exact existing statement in both the document
  and node result on a deterministic-ID collision, including HUMAN metadata.
- The unchanged Section and NodeSynthesisResult contracts permit empty summaries;
  relevant consumers impose no nonblank-summary requirement.

Implementer evidence: 20-test meaningful RED run with two expected failures and
zero errors; five-class focused GREEN run with 34 tests, zero failures/errors/skips.
The earlier 588-test broader run predates these corrections. Task 2a coordination
documents in the diff were outside this review.
