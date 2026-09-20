# Civilian acceptance validation

The reproducible contract and source requirement are described in the
[acceptance guide](../testing/civilian-acceptance.md). This record distinguishes
executed checks from pending visual/product acceptance.

## Executed locally

- The semantic playback tests failed before implementation and pass with the
  implementation: rule order and repeated calls, unknown requirement/keys/budget/
  task, duplicate rules.
- Real authenticated HTTP creation, two complete Copilot verification passes,
  78 remote response playbacks, snapshot persistence/reopening, and 17 generated
  export artifacts pass. The graph contains 38 elements and 44 relationships.
- Focused regressions cover qualified ancestor suppression without suppressing
  sibling branches, human-readable snapshot titles and PDF label size/bounds.
  Each defect was reproduced before its fix.
- The initial full verification exposed an optimistic-lock conflict when the
  coordinator and status readers promoted the same snapshot. Promotion now uses
  the existing tenant-scoped pessimistic row lock. The real HTTP scenario passes
  with three concurrent readers, without retrying failed responses.
- The actual Fit handler failed the phone-width and tall-graph geometry cases
  before its 20% floor was removed. All eight workbench URL/geometry contracts
  now pass; the zoom range includes the fitted scale.
- The PDF was rendered with Poppler and visually inspected. It now uses a wider
  poster page to preserve natural-size labels, and places long type names on
  their own line. The overall graph still needs zoom or a large print format;
  taxonomy hypotheses are not an approved application design.
- The original 87-page Word finding is corrected: page breaks are attached to
  meaningful headings, tables use readable column widths, and repeated rationales
  are referenced and printed once per section with their distinct provenance.
  Diagram aspect ratios and spacing are preserved, and chapter headings/captions
  stay with their following/preceding content. The follow-up native render and
  every-page inspection are recorded in the follow-up evidence below.
- Structurizr exports now pass the official 6.2.3 restricted parser. Custom
  elements represent the flat taxonomy graph without inventing C4 parent systems;
  duplicate display labels retain distinct IDs and the original label property.
- The complete ArchiMate → native reviewed import → Sparx file delivery uses a
  separate repository/workspace created through actual REST endpoints. It preserves
  all 38 elements. Explicit review rejects 8 of 44 candidates under native endpoint
  rules and 28 additional relations without a Sparx v1 mapping. The 8 delivered
  relations retain their endpoint names, direction and canonical types on re-import.
  Neither rejection changes the original generated snapshot.
- The [reference-case review](civilian-reference-review.md) accepts the fixture as
  an integration-test reference and records incomplete F3/F4 and F5/A1 contracts.
  It does not approve a deployed flood-warning architecture.
- LibreOffice Writer and Draw independently open the actual DOCX/VSDX exports.
  `taxonomy-tooling check-civilian-documents` checks all report rationale text and all
  diagram labels, empty page bodies, page count and file/PDF hashes. CI preserves
  every rendered page for visual inspection. The [Visio graphical follow-up](visio-graphical-quality.json)
  adds upright captions, strokes behind opaque nodes and bounded native detail pages.
  The actual saved-snapshot follow-up renders 11 pages and verifies all 44 relationship
  keys, source→target directions and types on their detail pages. Dense overview labels
  remain compact keys; Microsoft Visio product acceptance is still NOT_EXECUTED.

## Browser evidence and environment limits

Local Chromium cannot create its process singleton socket (`Operation not
permitted`). Automatic approval review rejected sandbox escalation. Docker is
also unavailable. Browser evidence was therefore produced by the Maven-owned CI
scenario, using the real application and Selenium on GitHub's runner.

The independent review found two important browser-test gaps, now corrected:
EXHAUSTIVE selects the required two passes, and actual downloaded bytes / exact
focus neighbors are checked instead of accepting clicks alone. No critical issues
were reported.

CI runs 35474438121 and 35475122709 reached the actual generated workbench and
passed focus, search, context-anchor stability, zoom and fullscreen checks. Their
downloads failed. The latter's genuine Chrome download-history screenshot proves
that Chrome blocked the HTTP host-bridge attachment as insecure; a `.crdownload`
was present. The browser now trusts only its dynamic isolated test origin, matching
the repository's existing ContainerTestUtils policy. This is test configuration,
not a production security setting or a substituted export response.

[Run 35475880662](https://github.com/carstenartur/Taxonomy/actions/runs/35475880662)
passed all four selected tests in 3m08s on head `8421556`. It covers real UI
creation, two Copilot passes, snapshot reopening, focus, search, context-anchor
stability, zoom, fullscreen, four actual downloads with semantic validation, and
the measured 390px mobile viewport. The five original PNGs were individually
inspected and committed with [run and image hashes](civilian-acceptance-evidence.json).
Chrome window sizing alone had yielded a 500px image; the final mobile image is
390 × 844 using explicit device emulation. No physical phone was tested.

The overview requires zoom; the six-node focus is easier to follow. The original
PDF's node labels are legible at natural poster size, while connection crossings
and full-graph density still require human assessment at the intended output size.

The first full repository verification ran for 18m45s and failed on the snapshot
promotion race above. The corrected exact command
`./mvnw verify -DexcludedGroups="real-llm"` passed in 20m38s: 5,092 reported tests,
zero failures/errors and one skipped test in `HelmConstrainedSmokeContractTest`.
It used Java 25 (release 21), the pinned ONNX model and the runtime's supported
Mockito premain agent. All production fixes were included; the later mobile-only
test enhancement was compiled and executed by the successful CI run above.

The repository's existing POM defaults are `skipITs=true`, `taxonomy.ui.skip=true`
and `taxonomy.quality.skip=true`. The exact command therefore skips the separate
Failsafe/container and general browser suites; no extra exclusions were added.
The dedicated civilian profile supplies its own actual browser evidence. This
result must not be described as a run of every database/container test. The exact
browser profile command was also executed locally and reached Testcontainers,
which failed because `/var/run/docker.sock` is absent.
Desktop import into Sparx EA / Microsoft Visio and production domain approval
remain unexecuted. Official Structurizr grammar validation and the documented
integration-reference review are now part of the follow-up. The counts and browser
run above describe the earlier baseline; follow-up gate/CI evidence is recorded
separately so historical results are not attributed to new code.

## Quality follow-up (2026-09-20)

[Machine-readable follow-up evidence](civilian-quality-followup.json) records the
final implementation and the separate full-suite, browser and native-render runs.
[CI run 35487419613](https://github.com/carstenartur/Taxonomy/actions/runs/35487419613)
passed all four selected tests on implementation head `658ad51` (merge `f2cf6a4`)
in 5m54s. LibreOffice 24 rendered 52 Word pages, with 261 preserved text assertions,
no empty body page and no orphan rationale-box heading. All 52 pages and all five
original browser screenshots were inspected; corrected boxes on pages 5 and 37
were also checked at full size. Draw retained all 38 element labels on one page;
this historical connector-caption defect is corrected by the separately source-bound
[Visio graphical follow-up](visio-graphical-quality.json).

The same implementation rendered to 55 pages with local LibreOffice 26. Page-count
variation is recorded with renderer versions and source/PDF hashes. The original
87-page/empty-page defect and the later split rationale-box finding are corrected;
no source requirement, alternative or rationale was removed. Both XML structure
and independently rendered page bodies now guard the rationale-box regression.

[CI run 35488384460](https://github.com/carstenartur/Taxonomy/actions/runs/35488384460)
also passed on `41d4ffb`. Only the reviewed dependency baseline and execution ledger
changed after the visually inspected run; application/test sources are identical.
The exact full command `./mvnw verify -DexcludedGroups="real-llm"` passed on
`41d4ffb` in 19:54 min: 5,108 reported tests, zero failures/errors and
1 skipped test(s). The evidence lists the skipped suite and existing POM
defaults; separate container/general browser suites were not added to this command.
The dedicated civilian CI supplies the actual browser and independent rendering.
