# Task 3 implementation ledger

Status: in progress; not product acceptance.

## Checkpoint 1 — RED tests

- Scope: frozen historical DOCX revision/receipt, without adoption or live architecture changes.
- Seam: export-owned frozen model and DOCX port, app composition binding persisted baseline; architecture module owns POI and graph adapter.
- Added real endpoint/POI-parser revision test and JavaScript DOCX download contract.
- RED: `node taxonomy-app/src/test/resources/reformulation/report-download-contract.cjs taxonomy-app/src/main/resources/static/js/api/portfolio-api.js taxonomy-app/src/main/resources/static/js/portfolio/reformulation-reports.js` exited 1 with `TypeError: Unsupported report format` at the production API adapter.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#docxRevisionContainsFrozenSourceAndLiteralEvidenceWithBinaryDigest -Dsurefire.failIfNoSpecifiedTests=false test` (expected RED: unsupported DOCX format).
- No long test run or implementation edit yet. Root must publish this WIP checkpoint and verify full remote tree before continuing.
