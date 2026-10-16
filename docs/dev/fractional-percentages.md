# Fractional analysis percentages: contract and verification handoff

## Numeric contract

Analysis values remain percentages in [0,100]: `0.5` means **0.5%**, not 50%.
Category children partition their parent's absolute contribution; root relevance is
independent across taxonomies, and product suitability remains an independent
assessment. Effective product relevance is category relevance times suitability /100.
There is no new minimum1% floor, and no distribution of percentages between unrelated
relation alternatives. An explicit zero, an absent assessment and a failed request
are different evidence states.

`AnalysisPercentages` validates the original numeric token with BigDecimal, computes
allocation/product arithmetic in decimal and converts to the existing JSON/browser
number representation only at the boundary. DTOs and persisted numeric columns use
Double/double. Values too small to represent as a positive double fail explicitly;
they are never silently converted to negative evidence. Formatting is display-only.
The six-decimal display represents still-smaller positives as `<0.000001%`.

All stock category, independent-root and product prompts permit decimal JSON numbers.
A mandatory suffix also corrects old user-overridden integer-only prompts. Stable
identity and exact-quote validation are unchanged.

## Version and storage boundaries

- Analysis score semantics version2; replay policy identifier `decimal-percent-v2`.
  Both checkpoint keys and compatibility input hashes include that policy.
- Saved-analysis export version4. Integer exports remain readable with the same
  percentage units. Actual coverage, when present, is validated; manual score exports
  must not invent automated coverage or turn absent values into zeros.
- General message schema3 declares fractional root scores. Legacy integer schema1
  remains readable. Replies carrying scores upgrade to3 even for legacy tasks.
  Schema2 remains the separate partitioned-relation contract. A schema1 fractional
  score or a fractional task count is invalid; no coercion to an integer is allowed.
- Immutable PostgreSQL migrationV38 and restore-HSQL migrationV8 widen `direct_score`,
  requirement-coverage `score`, and derived requirement-solution `coverage_percent`.
  Existing migrations are unchanged. The legacy active-HSQL upgrade also handles these
  columns before Hibernate validates the model; repeating it is safe.
- Requirement mapping, Git decision projection and backup/restore values retain fractions.
  Actual counts, hierarchy depths, task budgets and priorities remain integer concepts.

## Executed local verification

The source tree was recovered from an existing CI source artifact and independently
matched live main406ee89aa7183c4e50c7b91c0006bdf9718911bd by Git tree hash
`0e8194419722ab30f9a673110468625fbe3fe6e6`. The local baseline commit is synthetic;
it is not a claim to recreate the signed GitHub commit.

All1424 production Java source files compile freshly with Java21 against307 genuine
CI runtime dependency jars, without fabricated dependencies or replacement production
stubs. This flat-classpath compilation is not Maven's module-boundary verification.

Named direct checks exercise the real parser, decimal apportionment, effective product
relevance, custom prompts, saved-analysis JSON, durable checkpoint SQL, claim fencing,
partial-result recovery, relation search and dispatch. JUnit adapters own the new Java
regressions in the normal repository suite. In this environment their existing/shared
self-checking Java entry points were executed directly; **no JUnit engine run is claimed**.

Actual file-backed HSQL testing covers integral-column upgrade, idempotency, shutdown,
reopen and preservation of85,0.125 and SQLNULL. Restore testing runs every registered
Flyway schema owner, validates the full approved Hibernate model, writes0.125 and reloads
it in another factory/session. Checkpoint tests use new transactions and separate sessions;
accepted0.125 and failed raw evidence survive without unrequested provider retries.

The four existing browser suites (product streaming, workbench fit, base path and print)
run61 cases successfully. Export regressions generate real PDF, DOCX, SVG and Mermaid,
checking0.125% and escaped positive-small-value labels. The PDF and both generated DOCX
pages were rendered and visually inspected. These are synthetic, not user analysis exports.

## Remaining acceptance work — not ready to merge

The complete pinned Maven build was attempted, but the wrapper could not download
Maven3.9.16. The installed GitHub connector exposes read actions only. No remote branch,
PR or CI result exists for this local numerical migration.

The known legacy JUnit score declarations, callback signatures, immutable-map expectations,
Mockito parent-budget matchers and report fixtures have been migrated to the decimal
contract. Real counters (catalogue lookups, publication mutations, queue consumers,
coverage density, ranks and depths) remain integers. Whole-number snapshot imports stay
explicitly covered. Newly built exports expect version4; legacy imports retain their
recorded format versions. The prior whole-point remainder and fraction-rejection assertions
now require fractional shares and strict numeric range checking respectively. Frozen local
embedding comparisons use the independent Lucene path without integer rounding.

A real javac pass over every test source plus AST inspection found and removed additional
integer/Double incompatibilities and boxed-map equality mismatches. The available runtime
artifacts do NOT include JUnit, Mockito, AssertJ, Spring Test or ArchUnit: test compilation
still fails on those absent APIs. Neither the diagnostic pass nor the successful existing
self-checking entry points is a complete JUnit/Maven build. A zero-result numeric audit
only means no further matches were found with the symbols javac could resolve.

Before merging: run the standard complete Maven verification with the actual test
libraries, exercise PostgreSQL migration against a real PostgreSQL database, review
plugin/API compatibility for changed scoring signatures, and integrate the separate
export presentation PR#1238 without overwriting either branch's changes. Java callers of
changed score DTOs/callbacks must be rebuilt against this version; the JSON compatibility
contract is not a promise of binary compatibility for arbitrary precompiled plugins.
The local numeric work does not implement automatic split/retry of malformed relation-source batches.

No workflow, coverage policy, threshold or test inventory was removed or disabled.
