# ANN candidate budget regression (PR #1141)

## Reproduced failure

At source `979c0ed643d33eeb807469b5b39015a009cb41dc`, both the ONNX
reference workflow `36473627822` and core workflow `36473627640` failed the
same English reference assertion. The model/index were usable. Email and
payroll were found; English word processing (`UA-1583`) was not in ten hits.
This is not another model-provisioning failure or a separate coverage failure.

The complete source archive reconstructs the exact tree
`4abf6d16d292f1c65a0ef98c5b8c266125366799`. Using its packaged application,
real pinned BGE weights, and a fresh complete 2572-node index reproduced the
unchanged `OnnxReferenceEvaluation.verify` assertion locally.

A diagnostic search on the **same running index and same query** produced:

| Requested search candidates before correction | Word-processing rank |
| --- | --- |
| 10 | Not found |
| 50 | Not found |
| 100 | 2 |
| 1000 | 2 |

The wider searches are diagnosis, not a relaxed top-ten acceptance rule.
Independent direct ONNX Runtime and DJL inference agreed on normalized CLS
vectors (cosine approximately one). The relevant node was present; the small
approximate-neighbor search failed to discover it. Lucene's graph exploration
uses the kNN candidate count, not just the eventual result-page size.

## Bounded correction

`LocalEmbeddingService.semanticSearch` now separates ANN candidate exploration
from the caller's response limit. The candidate budget is ten times the requested
page, at least 100, with extra exploration capped at 1000. An internal caller
requesting more than that still retains its requested count; the existing public
facade continues to cap pages at 1000. Long arithmetic prevents multiplication
overflow. `fetchHits(topK)` is unchanged. No lexical fallback, reference-specific
boost, new model, query rewrite, timeout increase or acceptance relaxation is used.

This is bounded oversampling of an approximate search, not a guarantee of exact
nearest neighbors on every possible catalogue. It spends additional Lucene graph
search work, not additional model/provider calls. Performance beyond this small
regression has not been benchmarked.

## Verification actually performed

- The unchanged real reference evaluator failed before the correction and passed
  after it, against the full application and freshly built real vector index.
- All twelve semantic/full-text searches ran each time. Both runs used identical
  model-file, reference and catalogue hashes, the same prefix, and top-K=10.
- English semantic ranks after the fix: payroll 1, word processing 2, email 1.
- German findings remain explicit: payroll and word processing missed the
  reference; email was at rank 6. The report correctly remains
  `MEASURED_WITH_REFERENCE_MISSES`; a passing English gate is not a German pass.
- Fourteen actual JUnit 6.0.3 tests passed, zero skipped, including the three new
  budget tests, native CLS-pooling checks and the existing reference contracts.
  Restoring the old no-oversampling budget made two new tests fail.
- Production/test compilation used Java 21 with `-Xlint:all -Werror`. The existing
  application dependencies were reused; no project dependency was introduced.

The local before-run JAR came from artifact `10992865601`. Its recorded synthetic
merge revision `fe885ced26586e5704dceca8d0ac5b5f4bd66f09` has the exact source tree
above. The after-run JAR is a locally patched derivative, replacing only the
compiled service class and marking `git.dirty=true`. Its SHA-256 is
`ca8e79b5fea7d0c67865d18408efdf9df0bda8c5b0a532102fe0e13b23775905`.
It is not relabelled as a clean CI build of a later commit.

`./mvnw -B -ntp verify -Ponnx` was also attempted locally, but the pinned Maven
3.9.16 distribution could not be downloaded in this environment, before tests.
Thus the focused JUnit and real application checks are not a complete Maven,
Docker/Selenium or all-project CI pass. The new PR-head workflows remain required.

Temporary preparation workflows supplied the exact public source/model and the
project's existing JUnit version for local reproduction; their files are not in
this PR. Existing reference questions, targets, assertions and test selectors
remain unchanged.
