# Current architecture examples implementation plan

> **For agentic workers:** Use the existing execution and review skills. The user authorized the straightforward documentation improvements after the repository assessment; no further design decision is needed.

**Goal:** Make the introduction demonstrate the requirement-specific architecture workflow using retained, attributable evidence.

**Architecture:** Reuse the captured Flood snapshot and the current application contracts. Keep the deterministic hospital exporter fixture and its drift test intact, while making the newer requirement-based example prominent.

**Tech stack:** Existing Markdown, Mermaid, Maven documentation checks and captured JSON fixtures.

**Spec:** The user requested the straightforward improvements identified in the preceding assessment: update obsolete examples without inventing a new model-quality result.

## Constraints and review focus

- Documentation only; no provider calls, runtime changes, new test selectors or changed quality gates.
- Preserve real catalogue IDs, directed relation types and frozen source identity.
- Separate captured pipeline results, current authored reference expectations and live-model evidence.
- Retain the captured PARTIAL status and the unresolved input-identity question.
- Keep English/German examples aligned and preserve existing section anchors.
- Keep the hospital Mermaid fixture byte-for-byte compatible with the existing drift test.

## Tasks

- [x] Update README with a focused diagram selected from the captured Flood result, traceable source links and the explicit authored-response boundary; retain the older exporter fixture in a labelled expandable section.
- [x] Update the first English and German examples to the evidence-based workflow, fix misleading illustrative catalogue mappings and relation direction, and preserve navigation.
- [x] Refresh the scenario acceptance guide against the current corpus and tests, distinguishing historical results from present expectations.
- [x] Check documentation links, diagram syntax/rendering and the selected graph against captured evidence; run the existing focused Maven checks and report missing prerequisites honestly.
- [x] Obtain an independent whole-change review, address material findings, and submit the bounded change as a pull request.

## Implementation decisions

The isolated branch starts at `9cd9b68f0dc55a4cd358d900cd0bbc7b206ce7bb`.
The README focus diagram is an editorial selection of actual captured nodes and
edges, with shortened functional labels; it is not a newly generated model run.
No test is added for prose. Existing exporter, link and help checks remain authoritative.

## Verification and review

The existing focused Maven selection passed on Java 25: 27 tests, zero failures,
errors or skips (`DocumentationLinkPolicyTest`, `ReadmeShowcaseDriftTest`,
`HelpControllerTest`, `TemplateI18nLintTest`). The compiled Help resources matched
the final source files. All three new diagrams rendered in a local Chromium
preview without page overflow or script errors; their displayed relationships
were checked against the captured graph. The original hospital Mermaid block
remained byte-identical and its existing drift test passed.

The independent review found that repository-relative evidence links would break
inside bundled Help. The new examples now use direct repository URLs, with frozen
snapshot files pinned to the base revision. All 16 new evidence/navigation links
were checked in the final rendered pages. No remaining material review finding
was identified. Live-model quality and the full repository CI remain separate
verification boundaries; no provider call was made for this documentation change.
