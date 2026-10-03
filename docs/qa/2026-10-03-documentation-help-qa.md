# Documentation and help QA — 3 October 2026

## Current delivery boundary

This amendment to PR #1164, based on
`e7852cc8510d4d214b5dc018011d914619933ca5`, integrates both general USER_GUIDEs.
They are no longer a deferred patch. The earlier README, portfolio, matrix,
Git/workspace, privacy and verification-instruction corrections remain in place.
There are 21 documentation files in the complete PR; this amendment changes only
the two existing manuals and this report/input manifest. No runtime code, workflow,
authorization rule, test selector, dependency or coverage threshold changes.

The manuals were reviewed again rather than publishing the old patch unchanged.
The former long introductions and duplicated API/DSL examples still contained
contradictory advice, including ignoring every low-scored node. The revised manuals
follow browser tasks, explain score/scope boundaries and link to existing maintained
specialist references. They retain the main chapter topics, all 173 earlier rendered
IDs and all 55 image occurrences in each language. Former subsection targets now
lead to their corresponding task chapter; this is not a claim of identical layout
or unchanged prose. Existing screenshots are not relabelled as freshly generated.

The separate implementation remains PR #1165 head
`83a9931579185d2fae789610057b69ca0bcce685`, tree
`20e16d7dcd0fb83db17a4b4e2815e3793aab555a`. Its three narrow fixes are cache identity,
image-stream ownership and standard Flexmark heading IDs. The previously blocked
broad browser/link rewrite remains excluded, not republished by another mechanism.

## Documentation findings addressed

The first-use tables now contain all five steps; a security note no longer splits
them after step two. Ordinary users sign in with their assigned accounts; initial
administrator bootstrap is a distinct operator procedure.

Both manuals explain the architecture-guided clarification loop: select a saved
source and snapshot, review a separate offer and typed questions, record human
answers or deferrals, inspect and confirm adoption, then request reanalysis
separately. The mobile time-capture example is explicitly illustrative, not a
claimed recorded model output. Original sources and historical evidence remain
separate from generated wording and accepted architecture changes.

The analysis chapters distinguish selected roots, taxonomies-only/full mode,
completed, excluded and unresolved work, and compatible continuation. Low child
weight is not a reason to discard an indispensable contribution; similarity is not
necessity. A failed or unvisited branch is not a negative finding. Local embeddings
do not supply generative relationship or wording capabilities.

Repeated API syntax and grammar examples are replaced by links to the existing
specialist guides, not another parallel reference. Editor revisions, analysis
snapshots, Git checkpoints and branch-head reset are distinguished. Export,
coverage and administrative sections retain explicit support and authority limits.
The prior privacy corrections remain: persistent analyses are not session-only,
database-backed Git is not a generic folder, disabling an account is not erasure,
and no improvised cross-store deletion procedure is claimed to be implemented.

## Fresh verification and immutable inputs

[The input manifest](2026-10-03-documentation-inputs.json) identifies the two guide
blobs, artifact ZIP/JAR digests, complete resource inventory and diagnostic/log
hashes. Prior document inputs remain linked at their immutable parent commit.
The earlier unbound quantitative claims for the lost broad help candidate remain
withdrawn; these new results do not retroactively identify or validate it.

The production classes are from CI artifact `11267389645`, built from merge commit
`d2e469f63a0a80d68429a36a3f7e7f69108afc31`, whose tree matches the #1165 head.
The guide checks overlay only the explicitly identified Markdown resources. They
are not a clean combined build of the two PRs and replace no production classes.

| Check performed again | Result | Exact boundary |
|---|---|---|
| Existing controller regressions against both exact CI artifacts | Baseline reproduces six failures; corrected artifact passes all eleven shared assertions | Java main entry points, not JUnit or the full suite |
| Actual HelpController/Flexmark, all 55 registered documents in EN/DE | All 110 pages render; 34 prior missing same-page targets become zero among 626 current local fragment occurrences | Static HTML targets; not all navigation |
| Output comparison for this two-guide amendment | Only EN/DE USER_GUIDE changes; 108 responses remain byte-identical | Compared with parent documentation on the same runtime |
| Deep-link and image preservation | All 173 previous IDs and 55 image occurrences retained per language; no duplicate IDs or missing referenced local image files | Existence/identity, not screenshot freshness or image download |
| First-use tables | Five body rows in each language | Actual rendered tables |
| New manual cross-document destinations | All 110 authored Markdown references point to existing documents; checked registered target fragments exist | No remote requests or browser routing guarantee |
| Unchanged shipped help importer in real local Chromium | Both languages retain the IDs, five table rows and 55 image nodes; two native section clicks scroll without additional fetches; no page errors | Controlled offline component responses, not login/HTTP/context-path acceptance |

The current corpus contains 709 relative Markdown-link occurrences. The manuals
now link to specialist references instead of copying their APIs. This count is not
709 bugs or HTTP 404 responses. Correct authored destinations and same-page targets
do not repair the remaining browser cross-document routing or request ownership.

A portable diagnostic was run from a fresh directory with the exact locally
supplied artifact. It verifies input hashes before extraction, compiles the corpus
probe with Java 21, renders both resource states and checks the tables, targets,
images and cross-document destinations. Optional Chromium checks use the unchanged
production script. The accompanying package includes the programs and logs, not
runtime binaries, credentials, model files or a second implementation of the app.

## Verification not claimed and remaining work

No new complete Maven/JUnit/Selenium/JaCoCo or full UI-suite result is claimed
locally. The available verification copy is not a full checkout and lacks the
Maven wrapper. Each final PR head still requires its normal GitHub CI and review;
a successful older head or separately built runtime is not a substitute.
No live or paid model calls were made.

The two general manual integrations and their local section-target corrections are
completed in this amendment. Comprehensive documentation QA remains open for the
actual application cross-document/help navigation, context paths and stale request
ownership, full API/install/operations accuracy, specialist-guide contradictions,
external references and current screenshots. The 337-file inventory and 110-page
render checks are not a sentence-by-sentence semantic audit of every document.

## Reproduction

Supply the exact candidate artifact locally and run the packaged diagnostic:

```text
python check_guides.py --candidate-zip /absolute/path/taxonomy-help-83a99315-application.zip --work /new/directory --browser /absolute/path/chromium
```

Java 21, Python with BeautifulSoup, and optional local Chromium/Playwright are
required. No download is performed. The manifest separately records the exact
baseline/candidate controller evidence from the existing shared assertion programs.
