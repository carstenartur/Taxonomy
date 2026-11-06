# Taxonomy Extension API

This framework-free SDK owns common extension metadata and the neutral feature
contracts used by operator-installed plugins. It does not depend on Spring,
persistence, the executable host or interoperability implementation classes.

## Reviewed integration boundary (host SPI 1.3.0)

`com.taxonomy.extension.api.integration.IntegrationConnectorExtension` contributes
`INTEGRATION_CONNECTOR` through `TaxonomyPlugin`. It extends the existing
`LifecycleIntegrationConnector`; the contribution ID is the exact
`descriptor().id() + "@" + descriptor().version()`. Multiple profile versions may
coexist. Integration plugins require STARTUP mode and host SPI >=1.3.0; existing
1.x contracts remain compatible.

The host calls plugins through catalog leases and stamps preview metadata with
`taxonomy.plugin.id`, `taxonomy.plugin.version` and `taxonomy.plugin.sha256`.
These keys are host-owned. Pending reviews cannot be applied with a different
plugin artifact, even if its profile version has not changed. A new preview is
required after replacement. Removing a plugin does not remove retained operations
or canonical data.

### Canonical projection version 1

`ARCHITECTURE_MODEL` plus `CANONICAL_PROJECTION_V1` declares the following neutral
exchange representation. The profile version identifies the adapter's mapping
rules independently of the SDK version.

| Exchange value | Projection |
|---|---|
| `ELEMENT` with `extensions.canonicalType` | Existing canonical element type; absent type requires explicit mapping/rejection |
| `RELATION` with `extensions.canonicalType`, source and target | Existing canonical relation, checked against accepted endpoint identities |
| `SPECIFICATION` | Native architecture package |
| `PLACEMENT` with a non-VIEW container | Package occurrence; `parentId` refers to a parent package occurrence |
| `VIEW` and placements whose container is that view's ID | Diagram membership; distinct from package membership |
| Unsupported values and `MappingLoss` | Retained evidence or explicit loss, never automatic repair |

The raw external artifact type and attributes remain evidence when a canonical
type changes locally. Native endpoint approval fields are accepted only from
explicit review, not from uploaded metadata. Canonical model validation, dependency
closure, authorization, journaling and Git checkpoints remain host responsibilities.

A read-only implementation overrides `validateInboundSelection()`; its default
implementation uses `previewOutbound()`, which a reader does not provide.
The optional [Sparx QEA plugin](../plugins/taxonomy-sparx-plugin/README.md) demonstrates
this boundary with synthetic fixtures. It is not part of the default distribution.

## Inventory read-source boundary (host SPI 1.2.0)

`com.taxonomy.extension.api.inventory.InventorySourceExtension` contributes
`INVENTORY_SOURCE` through the existing `TaxonomyPlugin` entry point. It returns a
bounded source observation using `InventoryReadContracts`, not a canonical model
or an approved suitability decision. Contributions are STARTUP-only in the current
runtime; old hosts reject artifacts requiring SPI 1.1.0 before loading them.

SPI 1.2 adds bounded, immutable non-secret `Request.sourceOptions` for versioned
adapter configuration and optional source-bound `ReadAccess.principal()` for
authentication modes requiring a username/client ID. The original Request
constructor supplies empty options; credential-only ReadAccess lambdas remain
compatible. Plugins using the new contract must require SPI >=1.2.0. Both
credential and principal arrays are owned by the invocation and wiped by the consumer.

The observation retains the exact source selection, immutable type/field projection,
non-secret configuration revision, context, source identities and field states.
Absent/null/unsupported is not false or empty. Missing source revisions and missing
external relationship IDs are allowed by the neutral contract; adapters may require
those values where their specific source protocol provides them.

The host owns source authorization, TLS/credential grants, catalog leases, visibility,
evidence persistence, comparison and reviewed local decisions. A caller must obtain
its request from the authorized context, not deserialize arbitrary browser authority.
There is no added database, LLM call, scheduler, remote write or second plugin catalog.

The optional [LeanIX plugin](../plugins/taxonomy-leanix-plugin/README.md) and
[Alfabet plugin](../plugins/taxonomy-alfabet-plugin/README.md) demonstrate independent
implementations. Their read-observation subset does not complete the
[full product-neutral comparison design](../docs/superpowers/specs/2026-10-18-inventory-planning-sources-design.md).
See [extension module boundaries](../docs/dev/extension-module-boundaries.md) for
physical ownership, artifact loading and existing Maven verification.
