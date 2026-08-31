# Copilot and Autopilot

Taxonomy distinguishes an explicitly requested **Copilot full analysis** from unattended **Autopilot** execution. Both use persistent, tenant-, repository- and branch-bound portfolio analysis jobs. Reloading or navigating away does not lose an operation.

The canonical inventory for all application settings is [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md). This page explains the AI-automation settings in their workflow context.

## Manual Copilot full analysis

A saved immutable requirement version is processed through one or more bounded verification passes. A completed operation produces taxonomy scoring and reasons, a relation-aware architecture view, gap and pattern analysis, an architecture recommendation, immutable snapshots, and—when enabled—deterministic solution and product proposals.

```text
POST /api/projects/{projectId}/requirements/{requirementId}/copilot
GET  /api/projects/{projectId}/requirements/{requirementId}/copilot/latest
GET  /api/projects/{projectId}/copilot-operations/{operationId}
POST /api/projects/{projectId}/copilot-operations/{operationId}/cancel
```

Profiles have these meanings:

| Profile | Minimum passes | Solution/product proposals |
|---|---:|---|
| `STANDARD` | 1 | disabled |
| `FULL` | configured Copilot default, at least 1 | enabled unless the request disables them |
| `EXHAUSTIVE` | at least 2 | enabled unless the request disables them |

All configured or request-supplied verification-pass counts must be between 1 and 3. Passes of one operation execute sequentially. Identical input state and settings reuse the persistent operation unless the caller deliberately requests `force=true`.

Manual Copilot requires a ready provider but does **not** require `TAXONOMY_AI_COST_POLICY=UNMETERED`; the user explicitly starts every run.

## Persisted architecture workbench

Every successful Copilot full analysis stores an immutable architecture snapshot. Open **Architecture** from the completed run to investigate that exact result in a read-only workbench with search, overview and focus modes, context filtering, zoom, true fit-to-content, fullscreen display, provenance, review state and evidence details.

![Persisted Copilot architecture workbench](../images/71-copilot-architecture-workbench.png)

The browser, SVG download and PDF download use the same deterministic server-side scene and coordinates. The interactive browser controls change only visibility, selection and viewport; they do not silently recalculate or mutate the persisted architecture.

## Complete-run result surface

After a full analysis reaches an authoritative terminal state, Taxonomy reloads the requirement with the selected immutable snapshot. The same page keeps the Copilot operation status, provider, accurate terminal phase, completed verification passes and server-contact evidence visible above the result tabs. The selected snapshot is also shown explicitly and remains available through **Open result**. Snapshot-bound links open the loaded **Analyses** result directly rather than leaving it hidden behind the default text tab.

The normal reading flow presents six provider-neutral indicators, the highest-priority architecture gaps, detected patterns and recommendation reasoning. Complete technical payloads remain available in the collapsed diagnostics section and the JSON report instead of being dumped into the result page.

![Complete Copilot run result](../images/72-complete-copilot-run-result.png)

The screenshot is captured by `CompleteCopilotSessionIT` from the real successful persisted state. It is not assembled from a browser fixture, and screenshot generation fails if the selected snapshot, terminal operation and result tabs are not visible together.

## Architecture-node limits

`TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` keeps its historical name for compatibility, but its Spring property is `taxonomy.ai.max-architecture-nodes` and it applies to **both manual Copilot and Autopilot**. It is the operator ceiling for the architecture view built in every AI-automation pass. A manual API request may select a lower value but cannot exceed it.

A second setting, `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES`, is the general portfolio-analysis ceiling. The effective limit is therefore the lower of both values. Keep them aligned unless a deliberately smaller AI-automation view is wanted:

```text
TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50
TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES=50
```

Increasing the value can retain more relevant nodes, but also enlarges pipeline work, snapshots, relation generation and diagrams. It does not change the number of project requirements processed in one Autopilot batch.

## Explicit Autopilot opt-in

Taxonomy never assumes that a custom OpenAI-compatible endpoint is free. Unattended execution requires all three deliberate settings:

```text
TAXONOMY_AI_COST_POLICY=UNMETERED
TAXONOMY_AI_AUTOPILOT_ENABLED=true
TAXONOMY_AI_AUTOPILOT_PROVIDER=CUSTOM_OPENAI
```

The selected provider must also be fully configured. For `CUSTOM_OPENAI`, set `CUSTOM_LLM_URL` and `CUSTOM_LLM_MODEL`; the API key is optional for a trusted unauthenticated local server.

`TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE=false` disables the automatic save hook without disabling explicitly requested project-wide runs.

## Project-wide execution

```text
GET  /api/projects/{projectId}/autopilot
POST /api/projects/{projectId}/autopilot/run
```

The POST body may contain `requirementIds` and `maxRequirements`. The server never silently truncates a project. If the selection exceeds the effective request/operator batch limit, it rejects the call and requires a smaller explicit selection or a deliberate operator-limit change.

## Configuration table

| Environment variable | Default / validation | Effect |
|---|---|---|
| `TAXONOMY_AI_COST_POLICY` | `METERED`; enum | Operator cost declaration. Autopilot requires `UNMETERED`; manual Copilot does not. |
| `TAXONOMY_AI_COPILOT_PROFILE` | `FULL` | Default profile for a manually initiated operation. |
| `TAXONOMY_AI_AUTOPILOT_PROFILE` | `EXHAUSTIVE` | Profile used by unattended operations. |
| `TAXONOMY_AI_COPILOT_VERIFICATION_PASSES` | `1`; valid 1–3 | Manual default before the profile minimum is applied. |
| `TAXONOMY_AI_AUTOPILOT_VERIFICATION_PASSES` | `2`; valid 1–3 | Unattended default; `EXHAUSTIVE` still requires at least 2. |
| `TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES` | `50`; minimum 1 | Node ceiling for architecture views from both manual Copilot and Autopilot. Also bounded by `TAXONOMY_LIMITS_MAX_ARCHITECTURE_NODES`. |
| `TAXONOMY_AI_AUTOPILOT_ENABLED` | `false` | First explicit opt-in for unattended execution. |
| `TAXONOMY_AI_AUTOPILOT_PROVIDER` | empty | Explicit provider for Autopilot; it must be configured and match the operator's unmetered declaration. |
| `TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE` | `true` | Starts an unattended operation for a newly saved immutable requirement version when Autopilot is otherwise ready. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_SOLUTIONS` | `true` | Enables deterministic `PROPOSED` solution links for non-`STANDARD` Autopilot runs. |
| `TAXONOMY_AI_AUTOPILOT_PROPOSE_PRODUCTS` | `true` | Enables deterministic `CANDIDATE` product proposals for non-`STANDARD` Autopilot runs. |
| `TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS` | `50`; valid 1–500 | Maximum project-wide selection; oversized requests fail rather than being truncated. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_COVERAGE` | `25`; clamped to 0–100 | Minimum overlapping confirmed taxonomy coverage for a deterministic product proposal. |
| `TAXONOMY_AI_PRODUCT_PROPOSALS_MINIMUM_CONFIDENCE` | `0.25`; clamped to 0–1 | Minimum fraction of a solution's confirmed nodes covered by the product. |
| `TAXONOMY_AI_MAXIMUM_RUNTIME_SECONDS` | `1800`; minimum effective 60 | Maximum coordinator wait per pass. The persistent job remains recoverable after the wait ends. |
| `TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS` | `4`; valid 1–64 | Number of different Copilot/Autopilot operations coordinated in parallel. It does not parallelize passes within one operation. |
| `TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY` | `100`; valid 1–10000 | In-memory coordination queue. Persisted operations can be resumed after capacity rejection. |

The portfolio analysis worker is a separate execution pool. Its settings are `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_CONCURRENCY`, `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_QUEUE_CAPACITY`, and `TAXONOMY_PORTFOLIO_ANALYSIS_WORKER_SHUTDOWN_SECONDS`. Raising only the coordinator limit cannot make an individual provider call faster and may increase rate-limit pressure.

The effective readiness and reason are available at:

```text
GET /api/ai-automation
```

## Human review boundary

Autopilot prepares evidence and proposals. It does not:

- confirm taxonomy or relation mappings;
- assign binding organizational responsibility;
- select a product or authorize procurement;
- overwrite an approved architecture;
- merge a draft into an approved branch.

Generated solutions remain `PROPOSED`; products remain `CANDIDATE`. A human reviewer must approve every binding decision.
