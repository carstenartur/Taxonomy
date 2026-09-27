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

Implementation checkpoint: focused GREEN pending. Root owns the broader covering
selector, refreshed application DOCX visual inspection and published-head CI.
