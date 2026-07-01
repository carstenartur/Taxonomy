# Task: Add an Architecture View Step

## Goal

Add a new step to the architecture view generation pipeline — for example, a new
scoring phase, a filtering step, or a new view assembly strategy that produces a
specialised `RequirementArchitectureView`.

---

## Primary entry points

| File | What to do |
|---|---|
| `taxonomy-app/src/main/java/com/taxonomy/architecture/service/RequirementArchitectureViewService.java` | Add the new step to the assembly pipeline |
| `taxonomy-app/src/main/java/com/taxonomy/architecture/service/ArchitectureImpactSelector.java` | Modify element selection if the step changes which elements appear in a view |

---

## Files usually touched

- `taxonomy-app/…/architecture/service/RequirementArchitectureViewService.java` — central pipeline; add/insert the new step
- `taxonomy-app/…/architecture/service/ArchitectureImpactSelector.java` — element/relation selection logic
- `taxonomy-app/…/architecture/service/EnrichedImpactService.java` — enrichment of impact elements (if the new step adds metadata)
- `taxonomy-app/…/architecture/service/LayerRepresentativeSelector.java` — if the step changes layer representative selection
- `taxonomy-domain/…/dto/RequirementArchitectureView.java` — add new fields to the view DTO only if the step produces new output that the UI needs
- `taxonomy-domain/…/dto/ImpactElement.java` or `EnrichedImpactElement.java` — if the step annotates individual elements

---

## Files usually not touched

- `taxonomy-dsl/` — the view pipeline operates on scored results, not DSL content
- `taxonomy-export/` — export consumes the final `RequirementArchitectureView`;
  export code changes only if you add new DTO fields that exports must render
- `taxonomy-app/…/analysis/service/` — LLM analysis produces `AnalysisResult`;
  the view pipeline consumes it; the two stages are decoupled
- `taxonomy-app/…/relations/service/` — relation data is read-only input to the pipeline
- `taxonomy-app/…/controller/` — the architecture view controller delegates to the
  service facade; no controller change needed unless you add a new endpoint

---

## Backend endpoint(s)

| Endpoint | Controller |
|---|---|
| `GET /api/architecture/view` | `ArchitectureSummaryApiController` |
| `GET /api/architecture/impact` | `GapAnalysisApiController` |
| `GET /api/architecture/recommendations` | `RecommendationApiController` |

The pipeline produces results consumed by these endpoints.
In most cases, you do not need to add a new endpoint — extend the existing ones.

---

## Frontend module(s)

- `taxonomy-app/src/main/resources/static/js/architecture.js` — renders the
  architecture view; update only if the new step adds visible output fields
- `taxonomy-app/src/main/resources/templates/index.html` — update the architecture
  panel section only if you add new UI elements

---

## DTOs / domain types

| DTO | Usage |
|---|---|
| `RequirementArchitectureView` | The final view assembled by the pipeline |
| `ImpactElement` | An element that appears in the view |
| `EnrichedImpactElement` | An element annotated with metadata (risk, coverage, etc.) |
| `ChangeImpactView` | Change impact across elements |
| `GapAnalysisView` | Gap view between requirements and architecture |

Add new fields to existing DTOs only if the step produces genuinely new data.
Avoid adding nullable fields as a shortcut — consider whether a new DTO is cleaner.

---

## Tests to run

```bash
# App module unit tests
mvn test -pl taxonomy-app
```

Relevant test classes:
- `RequirementArchitectureViewServiceTest` — add a test for the new step's output
- `GapAnalysisApiControllerTest` — end-to-end pipeline tests
- `ReportApiControllerTest` — report generation tests that may use the view

---

## Documentation / screenshot updates

- `docs/en/ARCHITECTURE.md` — if the new step is architecturally significant,
  add it to the "Architecture View Generation Pipeline" section
- `docs/en/DECISION_PIPELINE.md` — update the pipeline description
- Screenshots: regenerate the architecture view screenshot if the new step changes
  visible output

---

## Common pitfalls

1. **Pipeline ordering matters:** The steps in `RequirementArchitectureViewService`
   run in sequence. Inserting a step in the wrong position can produce incorrect scores
   or miss elements. Read the existing step order carefully before inserting.

2. **Empty analysis result:** The pipeline must handle an empty `AnalysisResult`
   gracefully (no LLM response yet). Test with an empty result as well as a populated one.

3. **Determinism:** The view pipeline is expected to be deterministic for the same
   input. Avoid `HashMap` or `Set` iteration where order matters — use `LinkedHashMap`
   or sorted collections.

4. **`ArchitectureCommitIndex` is read-only input:** The commit index is built
   separately by `CommitIndexService`. A new view step should read from it but
   not modify it.
