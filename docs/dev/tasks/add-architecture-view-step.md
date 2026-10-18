# Task: Add an Architecture View Step

## Goal

Add a deterministic transformation to the ordered architecture-view pipeline in
`taxonomy-architecture`, while preserving its shared context and output invariants.
Start with [the pipeline extension contract](../07-extension-points.md#architecture-view-pipeline-steps).

Check which construction path your caller uses first:

- `RequirementArchitectureViewService.build(...)` runs the registered steps through
  `ArchitectureViewPipeline.execute(...)`.
- `buildFromEvidence(...)` uses `projectEvidence(...)` and
  `EvidenceRelationProjection` directly. It validates the same view model but does
  not execute the ordered step registry. Requirement-scoped evidence projection
  needs changes and tests in that path when the requested behavior applies there.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStep.java` | Implement the step contract as a Spring bean with a unique ID and order |
| `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStepRegistry.java` | Read discovery, duplicate validation, and ordering rules |
| `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitectureViewContext.java` | Read the step's inputs and intermediate state |
| `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitectureViewPipeline.java` | Read execution and invariant validation; also owns the separate evidence projection entry point |
| `taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/RequirementArchitectureViewService.java` | Stable facade; choose the relevant construction path |

---

## Files usually touched

- A new implementation beside the existing steps in
  `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/`.
  Spring bean discovery registers it; ordinary additions do not need a new
  hard-coded invocation in the facade.
- `taxonomy-architecture/src/main/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineInvariantValidator.java`
  — read and preserve anchor, element, relation, score, and node-limit contracts.
- `taxonomy-architecture/src/main/java/com/taxonomy/architecture/service/ArchitectureImpactSelector.java`
  or `LayerRepresentativeSelector.java` — when selection behavior changes.
- `taxonomy-domain/src/main/java/com/taxonomy/dto/RequirementArchitectureView.java`,
  `RequirementElementView.java`, or `RequirementRelationshipView.java` — only
  when the public view needs additional output.

---

## Files usually not touched

- `taxonomy-dsl/` — the view pipeline operates on scored results, not DSL content
- `taxonomy-export/` — export consumes the final `RequirementArchitectureView`;
  export code changes only if you add new DTO fields that exports must render
- `taxonomy-analysis/src/main/java/com/taxonomy/analysis/service/` — LLM analysis produces `AnalysisResult`;
  the view pipeline consumes it; the two stages are decoupled
- `taxonomy-knowledge/src/main/java/com/taxonomy/relations/service/` — relation data is input to the view
- Controllers in their owning feature modules and application adapters — change
  them only when the HTTP contract or cross-context composition changes

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `POST /api/analyze` | `AnalysisApiController` in `taxonomy-analysis`; can include an architecture view |
| `GET /api/analyze-stream` | `AnalysisApiController` in `taxonomy-analysis`; analysis stream |
| `POST /api/diagram/export/{formatId}` | `ExportApiController` in `taxonomy-app`; generic analysis-to-diagram adapter |

The view service is an internal construction API used by analysis and composed
export/report flows. Trace the consumer's construction path before changing it.
Summary, gap, impact, and recommendation endpoints have their own service contracts;
do not assume that every architecture response runs the step registry.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/core/taxonomy-scoring.js` — renders
  the requirement architecture view
- `taxonomy-app/src/main/resources/static/js/portfolio/requirement-detail.js` —
  renders the selected requirement's architecture
- `taxonomy-app/src/main/resources/templates/index.html` — update the architecture
  panel section only if you add new UI elements

---

## DTOs / domain types

| DTO | Usage |
|---|---|
| `RequirementArchitectureView` | The final view assembled by the pipeline |
| `RequirementElementView` | An included element, relevance, hierarchy, and presence reasons |
| `RequirementRelationshipView` | A relationship within the final view |
| `RelationSearchReport` | Requirement-scoped evidence retained by evidence projection |
| `AnalysisResult` | Analysis output that can carry the architecture view |

Add new fields to existing DTOs only if the step produces genuinely new data.
Avoid adding nullable fields as a shortcut — consider whether a new DTO is cleaner.

---

## Tests to run

```bash
# Architecture-owned unit and contract tests, including needed sibling modules
./mvnw -pl taxonomy-architecture -am test

# Application composition and endpoint tests when their behavior changes
./mvnw -pl taxonomy-app -am test
```

Relevant existing tests:

- `taxonomy-architecture/src/test/java/com/taxonomy/architecture/pipeline/ArchitecturePipelineStepRegistryTest.java`
  — registration and ordering
- `taxonomy-architecture/src/test/java/com/taxonomy/architecture/pipeline/ArchitectureViewPipelineTest.java`
  and `ArchitecturePipelineStepContextWriteTest.java` — execution and invariants
- `taxonomy-architecture/src/test/java/com/taxonomy/architecture/pipeline/EvidenceRelationProjectionTest.java`
  — evidence-bound construction
- `taxonomy-app/src/test/java/com/taxonomy/ArchitectureViewTests.java` — composed
  view behavior; its existing cases explicitly select the legacy propagation path
- `taxonomy-app/src/test/java/com/taxonomy/composition/report/ReportApiControllerCompositionTest.java`
  — when report composition is affected

These commands support iteration. Finish with
`./mvnw -B verify -Pci -DrunOnnxTests=true` and the applicable companion gates in
[Maven Verification](../MAVEN_VERIFICATION.md); selected-module tests do not
establish complete browser, database, or full-reactor verification.

---

## Documentation / screenshot updates

- `docs/en/ARCHITECTURE.md` — if the new step is architecturally significant,
  add it to the "Architecture View Generation Pipeline" section
- `docs/en/DECISION_PIPELINE.md` — update the pipeline description
- Screenshots: regenerate the architecture view screenshot if the new step changes
  visible output, following the [capture ownership and provenance rules](../../../.github/copilot-ref-screenshots.md)

---

## Common pitfalls

1. **Pipeline ordering matters:** The registry sorts enabled steps by their unique
   order values. Anchor selection must run first. Preserve core invariant steps
   and validate the context after each transformation.

2. **Empty analysis result:** The pipeline must handle an empty `AnalysisResult`
   gracefully (no LLM response yet). Test with an empty result as well as a populated one.

3. **Determinism:** The view pipeline is expected to be deterministic for the same
   input. Avoid `HashMap` or `Set` iteration where order matters — use `LinkedHashMap`
   or sorted collections.

4. **Evidence authority:** Keep original model assessments, effective relevance,
   and the complete relation evidence distinct. View filtering must not mutate
   the retained report or create new catalogue relations. An ordered step affects
   only callers that execute that pipeline.
