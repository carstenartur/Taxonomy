# Analysis reading ergonomics: delivery contract

Baseline: main `4e69b04b2fa56dd68d6b56cd4c7f15c22c7e0bd8`.
This is the bounded repair of the five reported reading/navigation failures, not
completion of the separately prepared Preferences, Decision viewport or DSL work.

## Visible behavior

The view selector owns a stable row; variable Tree/Decision/Sunburst tools use a
separate row. The selector may wrap at narrow widths but tools do not move it.
Summary items are native keyboard-actionable selection buttons. Selecting an item
keeps Summary and exposes text details. Only the explicit “Show in taxonomy tree”
action changes view and expands/focuses the actual catalogue path.

Sunburst, Tree and Decision share a relevance selector. “Hide zero relevance”
preserves the original immutable projection: only explicit numeric zeroes are
hidden. Unknown/missing evaluations, pending product-family measurements and
ancestors needed to reach retained descendants stay visible. “Only scores > 0%”
retains only finite positive scores and their original ancestor paths. The
projected data also feeds full diagram printing; catalogue, scores and saved
architecture remain unchanged. An empty selected taxonomy does not switch roots.
An empty projection explains how to show the catalogue again. The pre-existing
zoom handler no longer shadows the translation function with its D3 transition.
Filtering still recomputes the Sunburst layout; zoom continuity during filtering
is not a delivered guarantee.

## Analysis duration

The complete analysis use case measures elapsed wall duration with nanoTime,
including scoring, relationship discovery and architecture construction. The
streaming use case reports its own complete/partial duration. These are not sums
of concurrent LLM timings, queue latency or total Copilot postprocessing time.

Nullable `analysisDurationMillis` accompanies AnalysisResult, terminal SSE,
portfolio snapshot JSON, restored analysis drafts and decision-report metadata.
A null historic/unmeasured duration is explicitly “Not recorded”, never zero or
an estimate from report generation time. Replacement/manual/imported score
maps cannot inherit the previous result's duration. Progress snapshots expose
frozen terminal elapsed time and finish time. A measured result duration wins
over later diagnostic polling, including when final diagnostics are unavailable.

The UI retains duration in the result log and the taxonomy header. Decision HTML,
JSON and DOCX include it; Word also shows it in the executive summary. Interactive
report requests submit their current run metadata; immutable portfolio reports
read the stored analysis payload instead.

## Decision report contents

The former generic “Direct child nodes and alternatives” heading was Heading2
inside every Heading2 decision chapter. It is now a contextual Heading3, with the
parent code, and the full decision contents include only levels 1–2. The contents title has a
non-outline TOCHeading style. Contents navigation uses a single title and a
“Open chapter” action rather than printing the same title twice.

The export materializes the linked contents in document order. Short contents
include only level 1; full decision contents include levels 1–2. It emits no empty
TOC field and removes automatic field refresh, including an inherited template
setting, so opening the report does not request recalculation. Page numbers are
not guessed: chapter links work without a layout engine or a field update.
Existing downloaded reports need to be exported again to receive this change.

## Verification and remaining gates

- Before correction the actual production browser components reproduced Summary
  navigation, selector displacement, a Sunburst `t is not a function` exception,
  and the missing filter.
- Final local production-component matrix: 9 passing scenarios, including native
  Summary keyboard activation and selector positions at 390/768/1366/1920/2560px.
  Actual catalogue: 2,572 nodes; scores are explicitly controlled fixture input.
- 133 targeted Node checks pass, zero failures/skips, including source-data
  immutability, unknown/product zero semantics, measurement ownership, frozen
  progress and authoritative duration after a failed final diagnostic read.
- Four executable Java checks pass after compiling changed sources against the
  existing application runtime libraries: JSON/SSE timing, terminal timing,
  and EN/DE report navigation/duration. Ordinary JUnit wrappers register these
  in the standard source build. This local execution is not Maven/JUnit evidence.
- Generated five-page German DOCX rendered through LibreOffice and all pages
  inspected. English DOCX structure and HTML timing also checked. The sample
  report is authored over real BP/BR catalogue edges, not a live LLM assessment.
- The existing complete USER Copilot browser workflow now asserts the selector,
  native Summary activation, filter, zoom and retained measured duration, then
  records a screenshot. No additional analysis call is introduced. Full
  application execution of this expanded workflow remains a CI gate.
- Canonical `./mvnw verify -DexcludedGroups="real-llm"` was attempted locally and
  stopped at the pinned Maven 3.9.16 distribution download. The complete
  `npm --prefix .github run verify:ui-contracts` stopped at the existing evidence
  test because `@axe-core/playwright` could not be found. Local Node is 22, not
  repository-pinned 24. These are not green complete builds.

Full exact-head Maven, browser/database/security gates and review remain required.
No production dependency, canonical workflow, threshold, exclusion, resource
budget or taxonomy hierarchy was changed by this repair.
