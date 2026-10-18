# Start Here — Task-Oriented Developer Guide

This guide helps you find the **smallest safe change area** for a concrete task
without reading the full architecture reference first.

---

## How to use this guide

1. **Identify your task type** from the [Change Map](01-change-map.md).
   The map lists common task types with direct links to the relevant task page,
   the owning modules, entry points, and focused verification commands.

2. **Open the task page** for your specific change.
   Each page describes:
   - The primary entry point file(s) to start editing
   - Files you will typically **not** need to touch
   - The backend endpoint(s) involved
   - The frontend module(s) involved
   - The DTO / domain types involved
   - Which tests to run (and how)
   - Known pitfalls for that change type

3. **Make the change in the indicated area only.**
   The goal is to keep your diff small and reviewable.
   If you discover that your change requires touching many unrelated files,
   re-read the task page — there is likely a cleaner extension point.

4. **Run the targeted checks** listed on the task page before opening a pull request,
   then complete the required broader verification. The canonical entry point is:

   ```bash
   ./mvnw -B verify -Pci -DrunOnnxTests=true
   ```

   A focused module run or the default `verify` lifecycle does not cover every CI
   lane. Read [Maven Verification](MAVEN_VERIFICATION.md) for prerequisites,
   browser/shard evidence, companion profiles, and the remaining local-equivalence
   gaps. Documentation changes need content, link, and rendering checks as well.

---

## Module quick-reference

Use the [current module inventory and ownership contracts](../en/MODULE_BOUNDARIES.md)
for the complete reactor. The root [POM](../../pom.xml) owns module membership;
the inventory describes the implemented boundaries, not a planned extraction.

| Concern | Start here |
|---|---|
| Shared DTOs, enums, and contracts | Framework-free `taxonomy-domain`; feature persistence belongs to the owning feature module |
| DSL syntax, mapping, validation, and semantic diff | Framework-free `taxonomy-dsl` |
| Export codecs and extension/report/template contracts | `taxonomy-export`, `taxonomy-extension-api`, `taxonomy-reporting-api`, `taxonomy-templates-api` |
| Catalogue, relations, search, and local embeddings | `taxonomy-knowledge` |
| Requirement analysis, prompts, and provider execution policy | `taxonomy-analysis` |
| Architecture derivation, summaries, metadata, and view pipeline | `taxonomy-architecture` |
| Workspace/editor state, semantic journal, and Git checkpoints | `taxonomy-workspace` |
| Reports, templates, exchanges, and portfolio workflows | Their respective `taxonomy-reporting`, `taxonomy-templates`, `taxonomy-interop`, and `taxonomy-portfolio` modules |
| External plugin validation and lifecycle | `taxonomy-extension-runtime`; `plugins/taxonomy-mermaid-plugin` is the independent plugin example |
| Application wiring, security, migrations, cross-context adapters, and UI resources | `taxonomy-app`; provenance/document ingestion and preferences also remain application-local |

`taxonomy-app` is the executable composition root. Feature services, controllers,
entities, tests, and resources can live in their owning libraries; do not route all
backend changes through the application module. Build/tooling modules are described
in the inventory and are not runtime features.

The same executable supports local analysis or optional external-Artemis
coordinator/worker processes. That analysis topology does not establish that every
web/application subsystem supports multiple replicas; see
[deployment and operations](../en/DEPLOYMENT_GUIDE.md).

---

## When the architecture reference is still necessary

The task pages cover the **most common** extension scenarios.
For deeper understanding of the system design, cross-cutting concerns, or
non-standard changes, read:

- [Developer Guide](../en/DEVELOPER_GUIDE.md) — modules, conventions, pitfalls
- [Architecture](../en/ARCHITECTURE.md) — component diagram, data flow, security model
- [Configuration Reference](../en/CONFIGURATION_REFERENCE.md) — all `application.properties` keys
- [API Reference](../en/API_REFERENCE.md) — full REST API documentation

> The `docs/dev/` directory is the **task-oriented entry point**.
> The `docs/en/` directory remains the **deep architecture reference**.
> Neither replaces the other.
