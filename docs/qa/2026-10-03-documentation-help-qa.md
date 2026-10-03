# Documentation and help QA — 3 October 2026

## Current delivery boundary

This remains an ongoing QA, not a complete review of every product document.
The documentation amendment builds on #1164 commit
`3bd86d64519160c6a4747bf6239a32b2b9e340b5` and adds seven existing pages:
English/German Git integration, workspace/versioning and data protection, plus
the reformulation feature page. Its earlier README, portfolio-guide, matrix and
verification-instruction corrections remain in place. No workflow, authorization,
test selector or coverage threshold is changed by this documentation PR.

Five of the previously separate seven-file corrections are now integrated. The
two general USER_GUIDE corrections remain in the separately identified patch;
they must not be described as delivered merely because their local audit passes.

The separate implementation PR #1165 is at
`83a9931579185d2fae789610057b69ca0bcce685`, tree
`20e16d7dcd0fb83db17a4b4e2815e3793aab555a`. It now also enables the existing
Flexmark heading-ID options. It does not contain the previously blocked broad
browser/link rewrite. JavaScript, sanitizer behavior, routes and allowlists are
unchanged. No alternate publication mechanism is used for that blocked rewrite.

## Corrections to the earlier evidence record

The previously reported quantitative results for an unidentified broad help
rewrite remain withdrawn as reproducible acceptance evidence. In particular, the
old zero-finding, browser and 118 + 118 HTTP claims must not be used to approve
these PRs. New source hashes do not retroactively identify that older candidate.

The results below are fresh and bound to
[the input manifest](2026-10-03-documentation-inputs.json), the named PR sources,
and the preserved logs/diagnostic sources in the accompanying QA package.

## Documentation findings addressed

The Git guides no longer claim a Git commit is created for every accepted edit.
The workspace guides identify the Git-checkpoint timeline separately from the
semantic editor journal. Branch-head reset and personal semantic undo remain two
different existing operations; the text explains both rather than changing the
implementation to make an inaccurate description look consistent.

The reformulation feature page connects its technical contract to the already
published, registered portfolio walkthroughs. It does not depend on new anchors
in the still-unpublished general user guides.

The data-protection review found materially misleading statements: saved analyses
were described as session-only, database-backed Git as a generic filesystem folder,
account disabling as part of a complete erasure procedure, and provider selection
as a data-residency/compliance guarantee. The application code contradicts the
first three claims: ProjectRequirementVersion and RequirementAnalysisSnapshot are
persisted entities, and UserManagementService.disableUser saves enabled=false.
The corrected pages describe durable copies, operator responsibilities and actual
limits. They remove unsupported fixed retention periods, blanket fulfilled labels,
and instructions to delete database rows or rewrite Git history as a supposedly
complete supported erasure path. They preserve all existing heading titles and add
the missing TOMs compatibility anchors.

This does not implement a cross-store erasure feature or certify legal compliance.
A safe erasure design would need explicit scope, retention decisions, authorization,
reference closure and verification across journals, snapshots, indexes, backups and
recipients. It must not be improvised as a destructive side effect of this QA.
Technical source links and dated official GDPR/DSK references are included in the
pages; the assessment is not a complete legal or production-security audit.

## Implementation decisions in #1165

| Reproduced defect | Correction | Why this implementation |
|---|---|---|
| Unsupported language aliases duplicate the same English cached HTML | Key by the actually resolved packaged resource | Content identity determines reuse; no extra cache library or eviction subsystem |
| Image streams remain open on success or read failure | try-with-resources at the opening/read boundary | Resource ownership stays local; response semantics are retained |
| Markdown section links have no generated heading targets | Enable Flexmark heading IDs, Unicode lowercasing and duplicate resolution | Use the existing parser's model, not a second slugging algorithm or browser workaround |

The new heading change is five production lines plus regression assertions and
JUnit adapters. Source-bound local comparisons show that, with unchanged documents,
removing the newly generated heading attributes makes all 110 HTML responses
byte-identical to the lifecycle-only parent. No duplicate IDs were found.

## Fresh verification and exact scope

The original six lifecycle assertions reproduce three baseline failures; all six
pass on the corrected controller. Five heading assertions reproduce three baseline
failures; all five pass after the heading fix. Explicit anchors and fenced code
are preserved. These same assertion methods are exposed through JUnit adapters,
but local execution was through Java main methods, not a full JUnit run.

The final eleven assertions also passed against the freshly downloaded CI-packaged
application, without overlaying replacement production classes. CI artifact
11267389645 was built from merge commit
`d2e469f63a0a80d68429a36a3f7e7f69108afc31`; its source tree equals the #1165 head tree
above. The head SHA and the compiled merge SHA are not conflated in the manifest.

The documentation checks use explicitly listed resource overlays on that artifact;
they are not a claimed combined release build of both PRs.

| Candidate / check | Fresh result | Boundary |
|---|---|---|
| Heading fix with #1164 parent documents | 644 unresolved local fragment occurrences become 36 | Work on generated targets, not proof of complete browser navigation |
| Seven published documentation pages, compared using the unchanged controller | 110 pages render; six help pages change, 104 remain byte-identical | The reformulation feature page is not one of the 55 registered help pages |
| Those documents with #1165's packaged heading fix | 644 local fragment occurrences; 34 unresolved, all in the general user guides | Two authored TOMs targets are fixed; relative Markdown routing remains separate |
| Same combination plus the two unpublished USER_GUIDE files | 650 local fragment occurrences; zero unresolved; five first-table steps per language | This is a separate prepared candidate, not the published #1164 content |
| Both document candidates | No duplicate IDs or missing referenced local image files | Does not validate image freshness or external URLs |
| Unchanged production help importer in offline Chromium fixtures | Six previously absent targets are present after import; no page errors | Actual shipped script and actual controller HTML, but no login/HTTP/end-to-end journey |

There are 634 relative Markdown-link occurrences in the published candidate and
646 in the prepared candidate. These need browser routing; they are not a count
of distinct bugs or measured 404 responses. A zero same-page-fragment result does
not certify cross-document navigation.

The two-file remaining patch passed forward application to full baseline files,
byte comparison with the prepared candidate and exact reverse application.
Whitespace checks passed for all seven published pages. Existing heading titles,
image references and fenced examples in the Git/workspace pages were retained.
The privacy examples were deliberately revised; they are not claimed unchanged.

## Verification not claimed

The local full command `./mvnw verify -Pci -DrunOnnxTests=true` exited 127 before
Maven could execute because the recovered workspace has no wrapper/full checkout.
There is no new local full-reactor, JUnit, Selenium, HTTP, JaCoCo or complete Node
suite pass. Actual GitHub CI must verify each final PR head independently; earlier
head results and the separately packaged tree are not substituted for that gate.
No paid or live model requests were made by these checks.

The review inventories 337 packaged Markdown files and renders all 55 registered
help documents in EN/DE. It is not a sentence-by-sentence review of all 337 files.
General user-guide integration, cross-document/browser navigation, complete API,
installation and operations accuracy, screenshot freshness and remaining external
references are still open. Further assertions in older specialist guides also need
checking; correcting their introductions is not whole-guide certification.

## Reproduction

The input manifest records baseline and candidate artifact digests, the seven new
document blobs/SHA-256, the exact implementation head/tree, remaining-guide hashes,
and diagnostic/log hashes. The accompanying package contains sources, patches,
checks and output manifests, but not runtime binaries, credentials or model files.
Retrieve the identified CI artifacts separately to reproduce the Java checks.
