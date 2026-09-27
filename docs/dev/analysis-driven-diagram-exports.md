# Text-driven diagram exports use the shared analysis authority

The Mermaid, SVG, ArchiMate, Visio and Structurizr text-export routes use
`AnalysisDiagramExportService` to call `AnalyzeRequirementUseCase.analyzePreview`.
They no longer score the text separately and construct a legacy diagram from those
scores. Original text, the authorized workspace, current provider, prompt budget,
run observation, stop handling and requirement-scoped relationship evidence share
the normal analysis path. Explicit operator legacy mode is respected; invalid
relationship replies never select that mode automatically.

The preview entry point suppresses publication of global hypotheses even when an
operator explicitly selects legacy analysis. Normal interactive analysis retains
its existing persistence contract. Initial workspace setup and bounded diagnostic
run records may still be created; export is not a claim of zero infrastructure
writes. Neither route adopts an architecture or changes the original requirement.

Current runtime Preferences bound input length and architecture node count. No
second hard-coded 20/25-node filter or transient diagram policy truncates the
returned model. Nonempty partial results are labeled as partial in the exported
diagram. Missing, empty, cancelled, erroneous or unknown results return HTTP 422,
not an empty successful file or a score-derived substitute. Oversized/missing text
returns 400; unavailable catalogue/workspace returns 503 before provider work.
Existing authorization errors retain their status.

Exports of an already supplied current view or saved snapshot remain separate:
they use the frozen model without calling the analysis or resolving a new workspace.
These changes do not implement durable retry inside individual relationship calls,
independent source discovery, or a live-model quality benchmark.

## Verification

`AnalysisDrivenExportTest` covers original/context/limit propagation, a single
shared preview call, more than 25 nodes, partial labeling, invalid-result rejection,
preflight failures and frozen exports. `AnalyzeRequirementUseCaseTest` checks that
preview hypotheses remain unpersisted while the ordinary analysis contract remains.
`DefaultAnalysisExportHttpTest` exercises actual Spring routes with the explicitly
selected catalogue-backed MOCK demonstration, strict malformed-response rejection
and no extra provider work for a frozen view. These fixtures are not independent
real-provider architecture-quality evidence.

The reviewed dependency inventory replaces the legacy export-to-architecture pair
with one export-to-composition pair. The composition adapter owns seven class pairs
across five existing context directions (analysis settings/use case, catalogue,
versioning and workspace resolution). No new Maven module or reverse library-to-app
dependency is introduced. The unchanged ArchUnit policy verifies the whole inventory.
