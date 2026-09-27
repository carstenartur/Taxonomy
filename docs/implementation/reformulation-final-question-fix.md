# Current-question DOCX answer contract

Addresses the sole P2 in `reformulation-intermediate-final-review.md`: current
questions with no origins lost numeric constraints, conditional applicability,
option meanings, incompatible options and question dependencies in Word.

Test-first checkpoint `7f32e3cb` was published as `264f1708`. The focused actual-DOCX
test uses empty-origin numeric and conditional choice questions and checks English
and German labels, numeric units/bounds, gating values, dependency IDs and sorted
option meanings. It parses production-rendered bytes with Apache POI.

RED command (before production edits):

`python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-architecture -am -Dtest=ReformulationReportDocxReviewTest -Dsurefire.failIfNoSpecifiedTests=false test`

Result: exit 1, 6 tests, 1 assertion failure, 0 errors/skips. The new test failed on
missing `Minimum: 10.0` and `Maximum: 100.0`. Console evidence is outside the checkout
at `/workspace/scratch/38625e9262ff/final-question-red.log`.

The bounded renderer change shares the existing complete origin-schema rendering
with current questions and adds their prerequisite/dependent question IDs. Option
meaning keys remain sorted. No live lookup or domain behavior changes.

Implementation checkpoint `dd3adae7` was published as `0da2fdba`. The same command
ran GREEN against that published source: exit 0, BUILD SUCCESS, 6 tests, zero
failures/errors/skips, finished 2026-09-27 16:47:52 UTC. Console evidence:
`/workspace/scratch/38625e9262ff/final-question-green.log`.

Root owns the broader covering selector, refreshed application DOCX visual
inspection and published-head CI; focused success does not claim those gates.

## Integrated verification

Root's covering selector on published source `0da2fdba` completed at
2026-09-27 16:50:37 UTC: exit 0, BUILD SUCCESS, 61 tests with zero failures,
errors or skips. Export order 1, DOCX 6, evidence boundary 6, report 7,
positive-review guard 16, historical two-start report 2, real reanalysis 1,
architecture ratchet 22. The independent whole-branch reviewer accepted the
scoped fix with no remaining actionable findings.

Root independently rendered the freshly generated documents with LibreOffice /
Poppler and inspected all eight pages (EN five, DE three): readable current/origin
contracts, provenance and graph inventory, no observed clipping or overflow.
Exact application-generated DOCX SHA-256 values:

- EN: `061106b57d366da07f924bfb3a7ee5146afb9feae1e55cfeb80f3ff7ee98004e`.
- DE: `9ed0657137aa001a894f9ab41affd22a1d0f506f2878736f4033a8ae29a66777`.

The QA archive contains these documents, their PDFs/page images and a manifest.
Current-head CI remains the final merge gate. Task 4 and actual-model quality
remain open; none of these fixtures establish real language quality.
