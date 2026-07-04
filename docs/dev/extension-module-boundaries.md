# Extension Module Boundaries

## Decision

We introduce a dedicated Maven module now:

- `taxonomy-extension-api`

`taxonomy-extension-api` contains stable internal extension contracts and metadata types only:

- `TaxonomyExtension`
- `ExtensionKind`
- `ExtensionDescriptor`
- Export SPI contracts (`ExportFormatExtension` + descriptor/context/result types)
- Report SPI contracts (`ReportRendererExtension` + descriptor/context/result types)
- Import SPI contracts (`ImportProfileExtension` + descriptor/input types)
- LLM SPI contracts (`LlmProviderExtension`, `LlmProvider`, `LlmProviderDescriptor`)

Initial implementation and registry classes stay in existing modules:

- `taxonomy-app`: Spring registries, controllers, and Spring adapters
- `taxonomy-export`: framework-free export implementation logic
- `taxonomy-dsl`: DSL/parser/validator core
- `taxonomy-domain`: shared DTOs and enums

## Why this split

- Contracts become reusable without Spring Boot dependencies.
- Application-layer dependencies are prevented by module boundaries.
- We avoid premature over-modularization of small implementation classes.

## Enforced rules

- `taxonomy-extension-api` is part of the Maven reactor (`pom.xml` modules).
- `taxonomy-extension-api` must not depend on:
  - `taxonomy-app`
  - Spring (`org.springframework*`)
  - JPA/persistence (`jakarta.persistence*`, `javax.persistence*`, `org.hibernate*`)
- `taxonomy-dsl` and `taxonomy-export` remain protected from application-layer dependencies by architecture tests in `taxonomy-app/src/test/java/com/taxonomy/ArchitectureTest.java`.

## What is intentionally not split yet

- Small architecture pipeline steps and their tightly coupled `ArchitectureViewContext`.
- Small, isolated implementation classes that do not yet justify a dedicated build/test module.
- DSL/parser core changes that still evolve together with existing DSL modules.

## Criteria for creating a new implementation module later

Create a dedicated implementation module only when most of these are true:

- The implementation family has a stable public contract.
- It can be tested without `taxonomy-app`.
- It has multiple implementations or clear near-term growth.
- It prevents unwanted dependencies.
- It has meaningful independent build/test value.
- It is larger than just a few classes.
- It does not require broad application/security/database/UI dependencies.
