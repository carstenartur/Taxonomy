# Extension Module Boundaries

## Decision

The extension architecture uses a layered contract model rather than one universal module for every feature-specific SPI.

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
- Spring, JPA or Hibernate

### `taxonomy-export`

Owns diagram/export-specific contracts and implementation logic:

```text
com.taxonomy.export.spi.ExportFormatExtension
com.taxonomy.export.spi.ExportFormatDescriptor
com.taxonomy.export.spi.ExportContext
com.taxonomy.export.spi.ExportResult
```

This SPI uses the format-neutral `DiagramModel`, which is already owned by the export/diagram domain. Keeping the SPI in `taxonomy-export` avoids forcing the general extension API to depend upward on a feature module.

### `taxonomy-app`

Owns Spring and HTTP adapters only:

- `ExportFormatExtensionRegistry`
- `MermaidExportExtension`
- `ArchiMateExportExtension`
- `VisioExportExtension`
- `StructurizrExportExtension`
- controllers and generic extension-list endpoints

Application adapters depend on the SPI; the SPI never references Spring adapters or registries.

## Dependency direction

```text
taxonomy-domain
      ↑
taxonomy-extension-api
      ↑
taxonomy-export ───────→ taxonomy-domain
      ↑
taxonomy-app ──────────→ taxonomy-dsl
```

The Maven reactor and Enforcer rules protect this direction:

- `taxonomy-extension-api` bans application, export, DSL, Spring and persistence dependencies.
- `taxonomy-export` depends on the common extension base but bans `taxonomy-app`.
- `taxonomy-app` may depend on all lower-level modules.

## Package ownership rule

A Java package has one owning Maven module. Do not create the same package in both a framework-free module and `taxonomy-app`.

Use:

```text
com.taxonomy.export.spi            taxonomy-export contracts
com.taxonomy.export.service        taxonomy-app Spring adapters/facades
com.taxonomy.shared.extension      common extension metadata and app registry
```

A new SPI must use a package that identifies its owning module. This prevents split packages, ambiguous IDE navigation and future JPMS conflicts.

## What is intentionally not split

- Individual export implementations remain application adapters until they have independent configuration and lifecycle needs.
- Small architecture pipeline steps remain in `taxonomy-app` because they share an application-level pipeline context.
- DSL grammar, parser and validation remain together in `taxonomy-dsl`.
- Security, workspace routing and persistence are not plugin surfaces.

## Criteria for a new implementation module

Create another Maven module only when most of these are true:

- the contract is stable and framework-independent;
- the implementation can be tested without `taxonomy-app`;
- multiple implementations or substantial independent growth exist;
- the split prevents real unwanted dependencies;
- the module has meaningful build/release value;
- it contains more than a few adapter classes;
- it does not need broad application, security, database and UI access.

## Required review checks

For every new extension family:

1. identify the lowest domain module that owns its input/output model;
2. place the SPI in that module or a lower-level neutral contract module;
3. place Spring discovery and HTTP exposure in `taxonomy-app`;
4. add duplicate-ID validation and contract tests;
5. update the maintainability matrix and task-oriented guide;
6. add or tighten Maven Enforcer and ArchUnit rules.
