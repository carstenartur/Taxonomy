# Start Here — Task-Oriented Developer Guide

This guide helps you find the **smallest safe change area** for a concrete task
without reading the full architecture reference first.

---

## How to use this guide

1. **Identify your task type** from the [Change Map](01-change-map.md).
   The map lists common task types with direct links to the relevant task page,
   the packages most likely to be touched, and the test command to run.

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

4. **Run the targeted tests** listed on the task page before opening a pull request.
   For any change that touches a REST controller, UI template, or Spring configuration,
   also run the full verification suite:

   ```bash
   mvn verify -DexcludedGroups="real-llm"
   ```

---

## Module quick-reference

| Module | What lives here | Spring? |
|---|---|:---:|
| `taxonomy-domain` | DTOs, enums, domain constants | No |
| `taxonomy-dsl` | DSL parser, serializer, model, validator, differ | No |
| `taxonomy-export` | Export services (ArchiMate, Visio, Mermaid, Structurizr) | No |
| `taxonomy-app` | REST controllers, Spring services, JPA entities, UI templates | Yes |

The first three modules are pure Java libraries — they can be compiled and tested
without Docker and without a Spring context.
Changes there are usually safer and faster to verify.

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
