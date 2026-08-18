# Copilot and Autopilot

Taxonomy distinguishes an explicitly requested **Copilot full analysis** from unattended **Autopilot** execution.

## Copilot full analysis

A saved requirement is analyzed through persistent, tenant-bound analysis jobs. A full pass produces taxonomy scoring, architecture elements and relations, gap and pattern analysis, an architecture recommendation, and an immutable snapshot. Navigation or a browser reload does not lose the operation.

The manual endpoint is:

```text
POST /api/projects/{projectId}/requirements/{requirementId}/copilot
```

Existing operations can be resumed or cancelled through the operation endpoints. `STANDARD`, `FULL`, and `EXHAUSTIVE` profiles execute one to three bounded passes. Identical inputs reuse their persisted jobs unless `force=true` is requested.

## Explicit Autopilot opt-in

Taxonomy never assumes that a custom OpenAI-compatible endpoint is free. Unattended execution requires all three settings:

```text
TAXONOMY_AI_COST_POLICY=UNMETERED
TAXONOMY_AI_AUTOPILOT_ENABLED=true
TAXONOMY_AI_AUTOPILOT_PROVIDER=CUSTOM_OPENAI
```

The selected provider must also be completely configured. For `CUSTOM_OPENAI`, set `CUSTOM_LLM_URL` and `CUSTOM_LLM_MODEL`; the key remains optional for a trusted local server.

`TAXONOMY_AI_AUTOPILOT_ON_REQUIREMENT_SAVE=false` disables the automatic save hook without disabling explicit project Autopilot runs.

## Project-wide execution

```text
GET  /api/projects/{projectId}/autopilot
POST /api/projects/{projectId}/autopilot/run
```

The POST body may contain `requirementIds` and `maxRequirements`. The server never silently truncates a project: when the selected requirements exceed the configured batch limit, the request fails and asks the caller to select a smaller explicit batch or change the operator limit.

Relevant limits include:

```text
TAXONOMY_AI_AUTOPILOT_MAX_PROJECT_REQUIREMENTS=50
TAXONOMY_AI_AUTOPILOT_MAX_ARCHITECTURE_NODES=50
TAXONOMY_AI_COORDINATOR_MAX_CONCURRENT_OPERATIONS=4
TAXONOMY_AI_COORDINATOR_QUEUE_CAPACITY=100
TAXONOMY_AI_MAXIMUM_RUNTIME_SECONDS=1800
```

## Human review boundary

Autopilot prepares evidence and proposals. It does not:

- confirm taxonomy or relation mappings;
- assign binding organizational responsibility;
- select a product or authorize procurement;
- overwrite an approved architecture;
- merge a draft into an approved branch.

Generated solutions remain `PROPOSED`; products remain `CANDIDATE`. A human reviewer must approve every binding decision.
