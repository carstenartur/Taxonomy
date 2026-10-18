# Taxonomy Extension API

This framework-free SDK owns common extension metadata and the neutral feature
contracts used by operator-installed plugins. It does not depend on Spring,
persistence, the executable host or interoperability implementation classes.

## Inventory read-source boundary (host SPI 1.1.0)

`com.taxonomy.extension.api.inventory.InventorySourceExtension` contributes
`INVENTORY_SOURCE` through the existing `TaxonomyPlugin` entry point. It returns a
bounded source observation using `InventoryReadContracts`, not a canonical model
or an approved suitability decision. Contributions are STARTUP-only in the current
runtime; old hosts reject artifacts requiring SPI 1.1.0 before loading them.

The observation retains the exact source selection, immutable type/field projection,
non-secret configuration revision, context, source identities and field states.
Absent/null/unsupported is not false or empty. Missing source revisions and missing
external relationship IDs are allowed by the neutral contract; adapters may require
those values where their specific source protocol provides them.

The host owns source authorization, TLS/credential grants, catalog leases, visibility,
evidence persistence, comparison and reviewed local decisions. A caller must obtain
its request from the authorized context, not deserialize arbitrary browser authority.
There is no added database, LLM call, scheduler, remote write or second plugin catalog.

The optional [LeanIX plugin](../plugins/taxonomy-leanix-plugin/README.md) demonstrates
an independent implementation. Its read-observation subset does not complete the
[full product-neutral comparison design](../docs/superpowers/specs/2026-10-18-inventory-planning-sources-design.md).
See [extension module boundaries](../docs/dev/extension-module-boundaries.md) for
physical ownership, artifact loading and existing Maven verification.
