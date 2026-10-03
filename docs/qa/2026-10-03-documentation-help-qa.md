# Documentation and in-application help QA — 3 October 2026

## Scope and delivery status

Baseline: `3ec8a98610e6cb6d7fc4b4addee4c4f1bb87fb04`, tree
`dd7809c324ed4a98b9fcc319dffbe3e1fbc44832`.

This documentation-only checkpoint changes the README and records the investigation.
It does **not** contain the broader documentation patch or the proposed help runtime
corrections. Those were implemented and tested in a separate local verification
copy, but their publication was blocked. Do not infer that the existing help defects
are fixed on this branch or that its CI verifies the unpublished implementation.
No production code, test selector, workflow, authorization rule or threshold is
changed by this checkpoint. No merge is requested automatically.

The README now explains architecture-guided requirement clarification, separately
confirmed adoption, separately requested reanalysis, retained evidence, selectable
analysis scope and local embedding limitations. The original badges, generated
architecture showcase and all shell-command blocks were retained. The module-report
path remains a detail of the module/build documentation rather than the product
introduction.

## Review coverage

The exact application artifact contains 337 Markdown documents. They were inventoried;
this is **not** a claim that every sentence received complete semantic review.
All 55 registered help documents were rendered in both English and German through
the actual controller and Flexmark renderer: 110 rendered pages. Link/fragment/image
checks cover that registered set, not all repository files or external URLs.

Manual content review focused on the README, both user guides, feature matrices,
Git/versioning, requirement reformulation, analysis scope/progress, AI limitations,
and claims in the BSI-oriented operator checklist. The checklist must not present
an unversioned, self-authored checklist as certification or an independently verified
percentage of compliance. No independent standards or legal assessment was performed.

## Reproduced implementation findings

| Finding | Proposed local correction | Evidence boundary |
|---|---|---|
| Generated heading IDs are absent, so document fragments have no targets | Enable Flexmark heading IDs and duplicate resolution | Actual renderer, including Unicode and repeated headings |
| Source-wide regular-expression replacements also alter literal code examples, while other relative links remain unresolved | Resolve links in the parsed Markdown model, with a per-document source path | Literal inline/fenced code, reference links, images and repository paths |
| Unpackaged documentation resolves ambiguously or against mutable main | Bind repository links to a valid recorded source revision; retain an explicit development fallback | Actual resolver assertions; external destinations were not fetched |
| Image DOM URLs lose an application context prefix | Use the existing browser URL bootstrap for imported links and images | Real Chromium DOM at root and simulated `/taxonomy`; existing global fetch routing was already context-aware |
| An older request can overwrite a later document or its error | Track request ownership/generation, cancel obsolete work and ignore late results | Controlled out-of-order responses, including a transport that ignores cancellation |
| Help fragment IDs can collide with application IDs | Namespace imported IDs and scope fragment navigation | Real DOM assertions |
| The client URL filter does not reject every unsafe scheme form | Check parsed schemes and embedded control characters, retain safe links | Small local fixtures; no externally reachable exploit or incident is asserted |
| Unsupported locale names duplicate identical English HTML in the cache | Cache by the actual resolved resource path | Ten unsupported locales share one English entry |
| The image resource stream is not explicitly closed | Close it with try-with-resources | Actual controller with a tracked stream |

The local implementation reuses the installed Flexmark parser and existing i18n
routing/bootstrap. It does not add a documentation server, dependency, alternative
HTTP client or broader file-serving endpoint. Existing document/image allowlists
and read-only endpoints are retained.

## Documentation findings

The local patch incorporates the earlier twelve-file documentation correction into
complete source files, rather than only the original excerpts. It also corrects
stale cross-document fragments, preserves compatibility anchors for translated
headings, and removes contradictory claims about universal node scoring and an
automatic Git commit for every edit.

Git branch reset and personal semantic undo are **different existing operations**.
The former was confirmed in the versioning controller and must not be described as
if it were the latter. The correction explains both; it does not change their runtime
semantics merely to make prose look consistent.

Other corrections distinguish an unassessed branch from a negative finding,
embedding search from generative relation/wording support, and implemented capture
components from an operational end-to-end backup/restore workflow. Historical
screenshots and catalogue inputs are not relabelled as fresh evidence.

## Evidence actually executed locally

| Check | Original baseline | Corrected local candidate |
|---|---|---|
| Shared renderer assertions | 17 of 20 fail | 20 of 20 pass |
| Cache and stream assertions | Both fail | Both pass |
| Registered help link/fragment/image check | 1,278 findings across 110 rendered pages | Zero findings across the same 110 pages |
| Chromium DOM/ownership checks | 11 of 17 fail | 17 of 17 pass |
| Concurrent renderer contexts | Not used as a baseline defect claim | 100 concurrent tasks pass |
| Source-revision binding | Not used as a baseline defect claim | Two focused assertions pass |
| Real Spring Boot HTTP help checks | Not counted as a pre-fix comparison | 118 root-path and 118 `/taxonomy` assertions pass |

The renderer/resource assertions share code with the proposed JUnit adapters. They
were executed as Java programs, **not as a full JUnit run**. The proposed Failsafe
browser class was not executed through Selenium here. The 17 Chromium checks used
real production scripts in offline DOM fixtures because host policy blocked browser
navigation. They do not certify the complete application browser journey.

HTTP checks started the real Spring Boot application with HSQLDB, local authentication,
MOCK selected and embeddings/model downloads disabled. Compiled changed classes and
edited resources were placed before the exact baseline artifact on the classpath.
This is a controlled **classpath overlay**, not a freshly built release or a clean
new-commit artifact. No paid provider requests were made.

The complete Maven command was attempted in the recovered verification copy:

```text
./mvnw verify -Pci -DrunOnnxTests=true
```

It exited 127 before execution because this copy has no Maven wrapper/full checkout.
There is no new full-reactor, Docker, Selenium, JaCoCo or complete Node UI-suite pass.
The actual production Java sources compiled with Java 21 and the baseline runtime
dependencies using `-Xlint:all,-path -Werror`; only optional driver-manifest path
warnings were excluded from this supplementary compile. Repository gates were not
changed. JavaScript syntax and whitespace checks also passed.

## Artifact identity

Application artifact: `11258279358`, CI run `37082514184`.

- ZIP SHA-256: `42be2dd943b548f434b795f6259356a76b7936b6f7be524b6158193845315c60`
- JAR SHA-256: `fdc1f3522e3ba763d57518f44c8d5bd1642b1d058673015642e14f63e3774d62`
- Baseline README Git blob: `73569d4b704e8f9a497d79a8ee3f6f225f11e2a2`
- Corrected README Git blob: `9ce103c8c27dd872abede974fc87d617d3b2492b`

The original manifest binds the artifact to the baseline commit and tree above.
That identity must not be represented as the identity of the overlaid candidate.
Credentials, application logs, runtime binaries and model files are not included
in the separately delivered correction package.

## Still required

Integrate and review the complete documentation/help candidate in a full checkout,
then run the normal Maven, browser, coverage and security gates on its exact commit.
Run the real help journey at root and under an application context path, including
keyboard navigation, language changes and cross-document links. Review all remaining
API/installation/operations assertions and current screenshots before claiming a
comprehensive documentation QA completion.

The development instructions also still need reconciliation with the actual CI
profile and separate browser/database lanes. External URLs, unbundled repository
source destinations and current standards have not been fully checked. Existing
release and backup branches are outside this change and remain untouched.
