# Completion of the QA remediation — 26 November 2026

## Scope and acceptance

This completes the source reconstruction begun in [the initial functional verification](2026-11-26-qa-bugfix-verification.md). The retained QA matrix contains **44 product findings: 15 original findings and 29 additional findings**. The [implementation ledger](../superpowers/plans/2026-11-26-complete-qa-remediation.md) preserves their identifiers. New source and fresh verification are used throughout; the missing historical commits and their old successful runs are not evidence that the reconstructed source passes.

The integration and merge record is [PR 1607](https://github.com/carstenartur/Taxonomy/pull/1607). Its required Maven verification, CodeQL, coverage and review policies govern acceptance of the exact published head. Focused local results below are evidence for their stated scope and are not substituted for those gates.

## Native export and product compatibility

The shared QEA writer now reserves normalized GUIDs from the reader's existing qualified native-reference rules. New identities cannot silently resolve a preserved dangling GUID reference. Opaque text remains opaque, and an existing referenced object remains editable. Occurrence writes validate supplied native diagram/object identity before either no-op path or insertion. Contradictions fail explicitly, without changing the input file.

`SparxQeaIdentityPreservationTest` exercises 25 real SQLite cases; 14 expected failures reproduced the original defects. The corrected full standalone plugin suite passes **176 tests**, including the existing QEA-1–4 regressions. Independent review approved the implementation and its immutable-source checks.

The Archi 5.11.0 download moved to the official archive; [PR 1608](https://github.com/carstenartur/Taxonomy/pull/1608) restores the URL without changing the version, SHA-256 or maximum size. The production resolver reproduced the old HTTP 404 and downloaded 167,984,423 bytes from the corrected URL with the existing digest. All 10 resolver tests passed. The actual product compatibility result is retained in that PR's CI.

## Reports and diagrams

The nine report findings (VIS-01–05 and four additional report/title findings) are covered by new renderer, service and controller regressions.

- Snapshot SVG and PDF share frozen source context. Visible text includes the saved analysis status, complete warnings, unfinished work and original open questions, plus project, requirement, snapshot, provider, branch and complete commit.
- Canonical SVG and PDF carry repository, workspace, branch and checkpoint/revision identity. An uncheckpointed revision is stated explicitly. Only four known generated title aliases are localized; custom titles remain unchanged.
- Known generated diagnostics are translated by exact/anchored formats. Original human/model explanations remain verbatim. Deterministic reason translation requires its explicit recorded source type.
- German table headers and reason links remain whole. The exact bootstrap organisation placeholder is removed, provenance headings and bookmarks are distinct, and the appendix flows naturally.
- Only the exact bootstrap footer is widened for three-digit counters. Administrator templates and stored package bytes remain unchanged. The English bootstrap editing instruction is translated.

The main selected reactor passes **153 tests**. An overlapping reporting subset passes 65 tests, followed by 4 affected tests after the final column-width correction. These counts are not summed.

Fresh DE/EN fixtures were rendered and inspected: all 24 representative DOCX pages, all 8 native PDF pages and all 4 SVGs. Two actual 103-page rendered documents passed geometry checks for all 206 footer counters; the 99/100/103 boundaries were visually inspected. The final footer XML equals the stress-tested footer. Independent review checked source/log/artifact digests and representative images. These are new deterministic renderer fixtures, not a new live-model architecture delivery.

## Help and documentation

The [help/capture repair record](2026-11-26-help-and-capture-repair.md) documents the exact evidence. Runtime links resolve relative to the packaged document after Markdown rendering, preserving fenced examples. Query-only links retain their current document. Registered help pages, explicit language requests, supplementary repository paths, fragments and bundled assets have defined destinations under the application context.

German stable anchors and actual bilingual audit/SBOM sections repair missing targets. Intake, version-selection and report guides reflect the corrected behavior. All 77 active screenshot hashes match their files. The two toolbar PNGs retain their earlier CI test-merge source. Five proposal, Copilot and report examples now use the integrated documentation capture. The remaining 70 retain their November 7 provenance.

The broader Java help/documentation selection passes 30 tests, including 8 help-reader callbacks. Independent review found the query-only URI edge case; its regression failed before correction and the final affected selection passes 17 tests. These are overlapping runs. The real bilingual contextual-help navigation test remains part of the integrated Maven browser shard.

## GUI and product decisions

The 27 GUI findings cover the existing six UX regressions and the additional route, DTO, version, review, inventory and mobile issues. The final source preserves the original request identity and evidence boundaries while correcting presentation and actions:

- Forms distinguish field interaction from submission, validate actual catalogue IDs and integer percentages, and send exactly the supported route/status/body. Snapshot lists, comparisons and solution proposals use the actual server contracts. Read-only, busy, permission and stale-context guards remain active.
- Product option refresh retains the current draft. Version analysis names its selected version; historical source text and the current requirement remain distinguishable. Known generated labels are localized in DE/EN, while original evidence and unknown prose remain unchanged.
- Intake review sits before the footer and receives focus. The button names its actual next action: review, save selection, create only a project, or retry the saved operation. A retained admitted command is replayed with the same body and version condition even if a later checkbox edit requests another action.
- Inventory settings are optional and bounded, unavailable actions are disabled, and the long table scrolls within its own region. The adoption dialog keeps its header and native Close button reachable while the body scrolls. Canonical proposal loading restarts when leaving a frozen source and ignores obsolete responses.
- Candidate POST numbers are nullable so omission is distinct from explicit zero. New coverage is required; only a new missing confidence defaults to the established neutral 0.0. Partial updates preserve omitted values, evidence and statuses. All effective values are validated before mutation.
- Manual exclusions and retained inventory findings remain separate. A trusted inventory replacement changes only its own context. Equal wording does not establish common provenance: an ordinary manual field remains authoritative until explicitly cleared, including ambiguous legacy copies. Both sources prevent inappropriate selection.
- The Git projection carries the existing candidate inventory comparison ID, fingerprint and exclusions, preserving them in a fresh target workspace as well as an existing one. Imported content cannot replace an existing retained context. The actual contribution/materialization path is covered by regression tests.

The focused Maven run for the reconstructed findings passes **753 checks with no failures, errors or skips**: 639 tooling checks (628 JavaScript callbacks and 11 shard ownership/balance cases), 69 portfolio cases, and 45 application cases. The application selection includes 34 form contracts, both adoption contracts, all 3 real Spring/JPA Git roundtrips, and 6 documentation-link policy cases. Earlier direct JavaScript and backend runs overlap this selection and are not added to it. Independent source/specification review approved all 27 GUI ledger entries after the additional label, retry and Git evidence defects were corrected.

The eight new `PortfolioRemediationLayoutIT` cases belong to the existing Maven browser shard and cover DE/EN at 390px and 1440px. They check native Close hit-testing/clicks before and after scrolling, viewport bounds, and the optional inventory region. They save 12 screenshots. Existing actual-server portfolio/lifecycle/inventory cases and the bilingual graph contextual-help case retain their original behavioral assertions; stale routes and selectors are corrected without loosening them.

## Further findings from integrated screenshot review

The 44 retained findings remain the original reconstruction scope. Inspection of the actual [integrated documentation capture](https://github.com/carstenartur/Taxonomy/actions/runs/45879308886), made from the clean test merge `3ec9bbc9d5d1c9bcadae219f4679d658608c4f76`, exposed two additional GUI defects. Those images are evidence of the earlier behavior, not acceptance of the corrected source.

- **REVIEW-UI-01 — pattern evidence state.** The overview treated an empty fully matched list as an unevaluated result even when incomplete patterns had been evaluated and the backend recorded 0% coverage. The overview and detail heading now use the same recorded nullable percentage in its actual percent units. Valid zero and small percentages remain valid values; absent, skipped or invalid evidence remains unevaluated. The separate per-chain completeness is unchanged, and the aggregate badge is explicitly labelled as coverage.
- **REVIEW-UI-02 — local proposal adoption.** A locally accepted workspace proposal was offered a central branch-adoption action and automatically sent to an endpoint that requires a central source. Table eligibility, event binding and direct preview now require explicit central provenance. A local acceptance keeps its Accepted/Akzeptiert status without that inapplicable action. The mixed filter is labelled Accepted/Akzeptiert. Existing central adoption, permission, branch, commit, cancellation and idempotency checks remain intact. The real screenshot owner asserts that its newly created proposal is local, accepted and has no central-adoption controls.

The verified red run executed 95 cases with 21 intended assertion failures and no errors or skipped cases. The final corrected JavaScript sources pass **222 cases**; the application selection also passes **six rendering and two translation contracts**, and all 817 application test sources compile. The earlier harness-error attempt is retained separately and is not used as defect reproduction. Independent review checked the eight changed file hashes, backend units/provenance, every preview caller and the original XML reports. These selections overlap earlier runs and are not summed. Fresh exact-head CI, native browser execution and replacement captures are still governed by PR 1607 before final acceptance.

## PostgreSQL recovery diagnostic follow-up

A [runtime job on the earlier hierarchy dependency head](https://github.com/carstenartur/Taxonomy/actions/runs/45867591430/job/140087878800) failed when its first native PostgreSQL capture subprocess returned 1. The job did not retain that subprocess log, so the functional cause cannot be reconstructed from the available evidence. A [separate PostgreSQL run on the identical source](https://github.com/carstenartur/Taxonomy/actions/runs/45867591476) executed and passed all three native recovery cases, including the complete restore/restart case; this does not explain the failed run.

The unchanged success assertion now includes the existing bounded, lazy process diagnostic, and the runtime shard retains PostgreSQL logs in an artifact named for its attempt. No recovery behavior, selector, success condition or timeout was relaxed. All six process-diagnostic helper cases pass and the application test sources compile. The original failure is preserved as a failure; a later passing run is a fresh verification result, not proof of a speculative functional fix.

## Dependency PR decisions

The dependency repairs retain the existing Maven-owned verification and product probes:

| PR | Correction and decision |
| --- | --- |
| [1606](https://github.com/carstenartur/Taxonomy/pull/1606), d3-hierarchy 3.1.3 | Compatible with both D3 versions; take before 1601. The management comment is version-neutral. |
| [1601](https://github.com/carstenartur/Taxonomy/pull/1601), D3 7.9.2 | Update all runtime and test resource consumers to the packaged version and align hierarchy 3.1.3 with the new lower bound. Actual bundled JS exposes the required functions. |
| [1603](https://github.com/carstenartur/Taxonomy/pull/1603), CodeQL 4.39.4 | Keep the quality-evidence finalizer's action pin equal to the reviewed workflow pin. Existing metadata tests changed from 7/8 to 8/8. |
| [1604](https://github.com/carstenartur/Taxonomy/pull/1604), iconv-lite 0.7.3 | Actual D3-DSV CLI passes Latin-1 CSV→JSON→CSV byte round trip and delimiter conversion with Commander 7.2.0. |
| [1605](https://github.com/carstenartur/Taxonomy/pull/1605), Springdoc 3.2.1 | Declare the existing BOM-managed Jackson 2 Java Time module directly in reporting; the removed transitive dependency had broken `Instant` serialization. |
| [1602](https://github.com/carstenartur/Taxonomy/pull/1602), Commander 15.1.0 | Closed without merge. The published D3-DSV package requires Commander 7; the actual file-input CLI succeeds with 7.2.0 and rejects its positional filename with 15.1.0. Retain the compatible mediation. Taxonomy has no callers of that standalone CLI, so no browser failure is claimed. |

All five accepted dependency branches include the same verified Archi URL correction. Their PR descriptions record exact published heads; the required checks track full CI and must pass before merge.

## Evidence boundaries

The local Chromium launcher had no browser executable, and authorized browser downloads returned HTTP 403. No local browser pass is claimed. Real GUI acceptance is assigned to the repository's existing Maven-owned GitHub browser/UI lanes on the published source, including the new DE/EN desktop/mobile layout cases. Downloaded exact-run screenshots are inspected before final GUI acceptance.

Enterprise Architect desktop and PCS have not been run. Native SQLite/export tests are technical contract checks, not target-product or independent architecture acceptance. No actual vLLM inference, model-quality assessment, or new live A0/A1-to-QEA delivery is claimed. External documentation websites were not exhaustively revalidated. Original requirement/model evidence remains unchanged; partial analysis and outstanding professional review remain visible.
