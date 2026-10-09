# Extension Module Boundaries

## Decision

The extension architecture uses a layered contract model rather than one universal module for every feature-specific SPI. This document distinguishes **framework-free contract ownership** from **Spring adapter ownership** following the completed feature extraction under #628. Independent plugin loading is implemented by the runtime catalog and loader; a Maven feature module alone still does not imply hot-unload support.

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

This SPI uses the format-neutral `DiagramModel`, whose complete `com.taxonomy.diagram` package is owned by `taxonomy-domain`. Keeping the SPI in `taxonomy-export` avoids forcing the general extension API to depend upward on a feature module.

### Template and report ownership

`taxonomy-templates` owns generic OOXML validation, materialization, Git storage,
HTTP, WebDAV and Smart-HTTP adapters. Consumers use `com.taxonomy.templates.api`:
`DocumentTemplates`, its immutable data records and errors, and contributed
`DocumentTemplateContract` / `DocumentTemplateReportPreview` ports. A template
family contributes its seed, validation and optional preview through
`TemplateContribution`. Generic bootstrap never replaces an existing template.

`taxonomy-reporting-api` owns complete immutable decision and architecture report
models in `com.taxonomy.reporting.api`. It depends only on domain contracts and
Jackson annotations; no Spring, persistence, export implementation or live reader.
Report-specific immutable evidence validation belongs here; source traversal and
locale-dependent presentation do not.

`taxonomy-reporting` owns the renderers, layouts, renderer registry, decision
DOTX seed, validation, preview and health adapter. Its auto-configuration explicitly imports
its owned rendering/template components when the template port is assembled. It renders independently supplied
models without `taxonomy-architecture` or workspace/catalog implementations on
its runtime classpath. `taxonomy-architecture` generates snapshot-bound report
models and no longer depends on template storage or report rendering.

Portfolio snapshot orchestration currently depends on both architecture and
reporting. This is an explicit assembly dependency; selecting portfolio as a
startup feature must also select reporting (and its templates dependency).
Application-wide report composition remains in `taxonomy-app`.

### External artifacts and host composition

`taxonomy-extension-runtime` owns manifest compatibility, artifact validation,
PF4J classloader adaptation, atomic publication and call leases. PF4J does not appear
in the SDK. Existing registries are facades over the same catalog. LLM transports
are startup contributions and execute through the existing host policy.

`taxonomy-mermaid-plugin`, under `plugins/`, is an independent Maven project with
provided SDK dependencies. Its JAR contains the existing Mermaid adapter and a
`TaxonomyPlugin` service entry. The algorithm remains in `taxonomy-export`.
The standard distribution places the JAR in `taxonomy-app/target/plugins` and
Docker's `/app/plugins`, outside the executable host. Both existing Mermaid HTTP
routes use the registry and return 404 when that contribution is absent. For a
source checkout, start the packaged app with
`--taxonomy.plugins.directory=taxonomy-app/target/plugins`.

`./mvnw -B verify -Pplugin-packaging-tests` owns independent SDK builds and real
packaged HTTP checks. A focused local execution is not the canonical CI gate.
When Maven uses a non-default settings file, pass its path with
`-Dtaxonomy.maven.user-settings=/path/to/settings.xml` so the independent build
uses the same authorized repositories/proxy; credentials are never copied into
the plugin project or its manifest.

### Spring adapters during the migration

Today most Spring discovery, HTTP exposure and feature adapters still live in `taxonomy-app`, including:

- `ExportFormatExtensionRegistry`
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
