# Task 3 implementation ledger

Status: in progress; not product acceptance.

## Checkpoint 1 — RED tests

- Scope: frozen historical DOCX revision/receipt, without adoption or live architecture changes.
- Seam: export-owned frozen model and DOCX port, app composition binding persisted baseline; architecture module owns POI and graph adapter.
- Added real endpoint/POI-parser revision test and JavaScript DOCX download contract.
- RED: `node taxonomy-app/src/test/resources/reformulation/report-download-contract.cjs taxonomy-app/src/main/resources/static/js/api/portfolio-api.js taxonomy-app/src/main/resources/static/js/portfolio/reformulation-reports.js` exited 1 with `TypeError: Unsupported report format` at the production API adapter.
- Next exact command after remote alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#docxRevisionContainsFrozenSourceAndLiteralEvidenceWithBinaryDigest -Dsurefire.failIfNoSpecifiedTests=false test` (expected RED: unsupported DOCX format).
- No long test run or implementation edit yet. Root must publish this WIP checkpoint and verify full remote tree before continuing.

## Checkpoint 2 — first implementation slice

- Endpoint RED: focused Maven selector executed one test, failed its HTTP 200 assertion with HTTP 400 (`Supported report formats: json, md, html`); zero test errors. Command is recorded above.
- Added export-owned frozen architecture model and DOCX port, scoped persisted-baseline access, snapshot-only assembler, architecture POI adapter, binary MIME/digest response, and fourth UI choice.
- JS GREEN: exact Node selector above exited 0 with `REFORMULATION_REPORT_DOWNLOAD_OK`.
- Frozen validation/detail, branch distinction, graph and gap tests are still pending; current assembler is not yet verified. No Maven GREEN or visual QA claim.
- Next exact command after remote checkpoint and local ref alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest#docxRevisionContainsFrozenSourceAndLiteralEvidenceWithBinaryDigest -Dsurefire.failIfNoSpecifiedTests=false test`.

## Checkpoint 3 — graph/gap RED selectors

- Java first GREEN attempt: 1 test, 1 failure, 0 errors; DOCX returned HTTP 500 because the saved snapshot fixture has no architecture graph and `ArchitectureReportDocument` rejects empty graphs. No passing claim.
- Added explicit unavailable graph section, removed raw catalogue/report JSON dump from DOCX prose, and surfaced captured inherited decision context.
- Added frozen-mapper tests for distinct offer/analysis branches, two directed parallel mappings without duplicate view edges, absent versus empty gaps, tampered version and dangling relation target. These tests have not yet been run; expect current graph multiplicity assertion to fail.
- Next exact command after root remote verification/ref alignment: `python3 .superpowers/sdd/reformulation-completion/run-maven.py -pl taxonomy-app -am -Dtest=ReformulationReportTest -Dsurefire.failIfNoSpecifiedTests=false test`.
