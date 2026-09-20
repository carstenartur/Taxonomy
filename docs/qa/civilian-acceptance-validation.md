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
  `.github/scripts/civilian-document-qa.py` checks all report rationale text and all
  diagram labels, empty page bodies, page count and file/PDF hashes. CI preserves
  every rendered page for visual inspection. The full Visio graph remains dense,
  with overlapping/reversed edge labels in Draw; this is a semantic interchange
  artifact, not an approved presentation layout or Microsoft Visio compatibility.

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
