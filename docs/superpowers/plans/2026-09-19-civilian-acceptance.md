# Civilian architecture acceptance

## Design and acceptance contract

Exercise the real application from a sourced civilian requirement through Copilot,
persisted snapshots, architecture views, reports and exchange formats. Only the
remote LLM HTTP response is replaced, using Spring's MockRestServiceServer on the
existing RestTemplate. Keep `llm.mock=false`: real templates, request envelopes,
budget checks, provider parsing, scoring, orchestration, database, security and
exporters remain active. Test support belongs exclusively to test sources.

The reference case is an England flood information and subscription service based
on GOV.UK's published service and the Environment Agency's public API contract.
The requirement and authored responses are a transparent test interpretation, not
a recorded model conversation or a claim to reproduce the Agency's architecture.
Source requirements and additional design assumptions are distinguished.

A compact JSON fixture maps explicit taxonomy categories to scores and reasons.
Playback identifies the scenario, task and requested keys in the actual prompt;
it is order independent, rejects unknown/ambiguous requests and records coverage.
The catalogue is loaded from the real workbook and validated through the API.
No result HTML, architecture, snapshot, database rows or service results are mocked.

## Tasks

1. Establish sourced requirement, catalogue bindings and semantic playback.
   Add negative contracts for unknown scenarios, keys and malformed tasks, plus
   order independent repeated calls. Validate real provider envelopes and parsers.
2. Run Spring with a real HTTP server and database, create project/requirement via
   authenticated HTTP/UI, execute Copilot and reopen the selected snapshot.
   Assert traceability, nonempty typed relationships, valid endpoints, meaningful
   reasons and provenance. Fail if any unrecognised LLM call was swallowed.
3. Reuse that snapshot across export QA and browser documentation. Check machine
   formats with parsers/validators and compare semantic identities; inspect SVG/PDF
   dimensions, labels and screenshots. Document visible controls and evidence.
   Keep screenshot generation opt-in. Distinguish candidate taxonomy architecture
   from an approved implementation and generated relations from confirmed ones.
4. Run focused regressions, the mandatory full verification command, an independent
   branch review, and publish reviewable changes with precise environment limits.

## Review focus

Check the test boundary: no production bypass or high-level mock may masquerade
as end-to-end evidence. Check unknown/ambiguous prompt handling, call coverage,
snapshot identity across exports, actual screenshot provenance, and that quality
claims match observable assertions. Tests must not contact live LLMs or require
real provider credentials. Inspect test-only configuration discovery/isolation.

## Execution ledger

- Base: dddcfb1 (Sparx integration draft stack). Clean dedicated workspace branch.
- Sources inspected 2026-09-19: https://www.gov.uk/get-flood-warnings and
  https://environment.data.gov.uk/flood-monitoring/doc/reference.
- Tasks 1 and 2: complete. Playback contracts observed RED (three failures),
  then GREEN (three tests). Real HTTP Copilot executes 39 rules twice, persists
  both passes and reopens the selected immutable snapshot.
- Task 3: 17 generated export files pass semantic/provenance checks. The scenario
  exposed redundant ancestor endpoints (334 relationships before, 44 after), a
  leaked title message key and 5pt PDF labels. Each has an observed failing
  regression followed by a passing focused run. The generated PDF was rendered
  with Poppler and inspected; the browser still requires CI execution.
- Ruling: use the real taxonomy-derived candidate that the current Copilot
  produces, with explicit domain-review limitations — inventing application
  components outside the pipeline would invalidate the test boundary — this
  does not prove full implementation coverage of the source requirement.
- Ruling: use a Maven-owned opt-in profile for browser evidence, preserving the
  ordinary HTTP acceptance in the default test suite — repository policy forbids
  Java test selectors in workflow YAML — Docker remains necessary for CI images.
- Environment: the pinned ONNX model was downloaded and hash-verified for the
  full gate. Docker is unavailable. Local Chrome/ChromeDriver were prepared,
  but Chrome's socket creation is forbidden by the runtime; automatic approval
  review rejected sandbox escalation. No browser screenshots have been claimed.
- Task 4: final full verification and independent review pending.
- Final review: independent read-only review found no critical issues and two
  important browser gaps: FULL explicitly requests only one pass, and clicks
  alone do not establish download/filter behavior. Both paths now use EXHAUSTIVE;
  browser downloads are retrieved and parsed, focus requires the exact neighbor
  set, and Fit checks rendered bounds. The fixture has no non-anchor context:
  the documentation explicitly limits that toggle's evidence. Browser execution
  remains pending; no RED/GREEN browser claim is made without an actual run.
- Final: Ruling: context toggle visibility cannot be demonstrated with this
  all-anchor result — retain the real generated graph and disclose the limited
  assertion instead of injecting context nodes — separate context-filter cases
  still need their own scenario.
