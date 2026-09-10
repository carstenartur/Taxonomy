# Extension Module Boundaries

## Decision

The extension architecture uses a layered contract model rather than one universal module for every feature-specific SPI. This document distinguishes **framework-free contract ownership** from **Spring adapter ownership** while `taxonomy-app` is being decomposed under #628.

### `taxonomy-extension-api`

Contains only common, Spring-free extension metadata and feature contracts that do not depend on another feature module:

- `TaxonomyExtension`
- `ExtensionKind`
- `ExtensionDescriptor`
- report renderer contracts
- import profile contracts
- LLM provider contracts

It may depend on `taxonomy-domain`, but it must not depend on:

- `taxonomy-app`
- `taxonomy-export`
- `taxonomy-dsl`
- any future Spring feature module
- Spring, JPA or Hibernate

### `taxonomy-export`

Owns diagram/export-specific contracts and framework-free implementation logic:

```text
com.taxonomy.export.spi.ExportFormatExtension
com.taxonomy.export.spi.ExportFormatDescriptor
com.taxonomy.export.spi.ExportContext
com.taxonomy.export.spi.ExportResult
```

This SPI uses the format-neutral `DiagramModel`, which is already owned by the export/diagram domain. Keeping the SPI in `taxonomy-export` avoids forcing the general extension API to depend upward on a feature module.

### Spring adapters during the migration

Today most Spring discovery, HTTP exposure and feature adapters still live in `taxonomy-app`, including:

- `ExportFormatExtensionRegistry`
- `MermaidExportExtension`
- `ArchiMateExportExtension`
- `VisioExportExtension`
- `StructurizrExportExtension`
- controllers and generic extension-list endpoints

That is a **current location, not a permanent ownership rule**. As bounded contexts are extracted, a Spring/JPA/JGit adapter should normally move with the context whose port it implements. `taxonomy-app` remains responsible for application-wide composition and cross-context wiring, but it must not become the permanent home for every framework-aware implementation.

Application adapters depend on their SPI/port; the SPI never references Spring adapters, registries or the executable app.

## Dependency direction

The stable lower-level direction remains:

```text
taxonomy-domain
      ↑
taxonomy-extension-api
      ↑
taxonomy-export ───────→ taxonomy-domain
      ↑
Spring feature adapters / taxonomy-app composition
      └────────────────→ taxonomy-dsl where required
```

The Maven reactor and Enforcer/ArchUnit rules protect this direction:

- `taxonomy-extension-api` bans application, export, DSL, Spring and persistence dependencies;
- `taxonomy-export` depends on the common extension base where needed but bans `taxonomy-app`;
- framework-free modules may never depend on a Spring feature module;
- extracted feature modules may not depend back on `taxonomy-app`;
- cross-context calls must enter through owned APIs/ports rather than repositories or concrete adapter classes.

## Package ownership rule

A Java package has one owning Maven module. Do not create the same package in both a framework-free module and a Spring application module.

Current examples:

```text
com.taxonomy.export.spi            taxonomy-export contracts
com.taxonomy.export.service        transitional Spring export adapters in taxonomy-app
com.taxonomy.shared.extension      common extension metadata / current app discovery
```

A new SPI must use a package that identifies its owning contract module. During bounded-context extraction, adapter packages may be renamed/moved so their owner is equally clear. This prevents split packages, ambiguous IDE navigation and future JPMS conflicts.

## No catch-all adapters module

Do **not** introduce a generic `taxonomy-adapters` module. It would combine unrelated HTTP, persistence, JGit and integration concerns and would risk becoming the next `taxonomy-app`.

Instead:

1. define the narrow port at the lowest stable owner;
2. keep framework-free contracts framework-free;
3. place the concrete adapter with the bounded context that owns the port, unless it is genuinely application-wide composition;
4. let `taxonomy-app` wire contexts together without becoming their implementation module.

The checked target context map is `.github/architecture-contexts.json`; temporary exceptions and their removal conditions are in `.github/architecture-exceptions.json`.

## What is intentionally not split mechanically

- DSL grammar, parser, validation and command semantics remain together in `taxonomy-dsl`.
- Workspace, versioning and the semantic editor initially form one state-authority bounded context instead of three mutually dependent Maven modules.
- Catalogue, relations and search initially form one knowledge bounded context while their internal contracts are improved.
- Small cross-cutting concerns are not promoted to standalone modules merely to increase the module count.
- Security, workspace routing and persistence are not plugin surfaces simply because they have adapters.

## Criteria for a new implementation module

Create another Maven implementation module when most of these are true:

- it represents a coherent bounded context or independently meaningful subsystem;
- its public contract/ports have stable ownership;
- it can be tested without the executable `taxonomy-app` composition root;
- the split prevents real unwanted dependencies;
- it has meaningful build/release or developer-navigation value;
- it contains more than a few unrelated adapter classes;
- its dependencies can remain acyclic and do not require broad access to foreign repositories.

Framework independence is required for contract modules, but **not** for every bounded-context implementation module: a context may legitimately use Spring/JPA internally as long as its dependency direction remains explicit.

## Required review checks

For every new extension family or extracted implementation context:

1. identify the lowest domain/feature module that owns its input/output model;
2. place the SPI/port in that module or a lower-level neutral contract module;
3. place concrete adapters with the owning bounded context or, only when genuinely cross-context, in the composition layer;
4. reject dependencies from framework-free contracts back to implementations;
5. add duplicate-ID/contract tests where extension discovery is involved;
6. update the maintainability/module-boundary documentation;
7. add or tighten Maven Enforcer and ArchUnit rules, including the cross-context dependency ratchet.
