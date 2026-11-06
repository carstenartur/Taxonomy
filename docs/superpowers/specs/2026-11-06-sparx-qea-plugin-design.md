# Optional Sparx QEA import plugin

## Approved scope

The user approved the proposed independently built Java plugin and the generic
host integration boundary on 2026-11-06. The first release reads QEA snapshots
and uses Taxonomy's existing preview, explicit review, apply and version history.
It preserves stable external identities, package structure, supported model
content and diagram membership, and reports unsupported content explicitly.

## Architecture and alternatives

Use an independent `plugins/taxonomy-sparx-plugin` Maven project, following the
existing Alfabet packaging. Its only Taxonomy dependency is the provided public
SDK. It contributes a `LifecycleIntegrationConnector` through a new
`IntegrationConnectorExtension` in the common plugin catalog. The host retains
authorization, canonical models, persistence, review and operation journals.

A built-in QEA reader would couple vendor dependencies to every installation.
A separate Python service would add an unnecessary runtime and separate lifecycle.
The approved approach is a STARTUP Java plugin with a focused SQLite reader.
AAroN and EAM-Toolfamilie are references for EA storage semantics; any actual
code reuse must retain its license and origin. Test models are synthetic.

## Contracts

- Java 25; plugin host SPI advances from 1.2.0 to 1.3.0.
- `IntegrationConnectorExtension` extends `TaxonomyExtension` and
  `LifecycleIntegrationConnector`; identity is `profile@version`.
- `CANONICAL_PROJECTION_V1` declares the existing `canonicalType` and typed
  endpoint representation, package occurrences and diagram membership.
- Plugin profile `sparx-qea`, version `1`; capabilities FILE_IMPORT,
  ARCHITECTURE_MODEL and CANONICAL_PROJECTION_V1. No QEA writing capability.
- External GUIDs are stable across reads, file renames and snapshot changes.
  File hashes identify snapshots, not objects. Duplicate/invalid identities
  and broken structural references cannot silently overwrite objects.
- Diagram membership must not change package membership.
- Unknown types require explicit mapping or preservation/rejection. UML
  Realization must not silently acquire Taxonomy's inverse REALIZES meaning.
- The plugin parses bounded immutable upload bytes through a temporary,
  read-only SQLite connection. It does not accept arbitrary file paths or SQL.
- The host records plugin ID/version/SHA-256 in the preview and checks it
  before using an outstanding review. Calls use catalog leases.
- Existing built-in profiles and installed 1.x plugins remain compatible.
- One existing integrations UI, localized in German and English. No new editor.

## Acceptance

Install the independently built JAR in the existing operator plugin directory.
Import a synthetic QEA fixture with packages, same-name elements, a relation,
tagged values and diagram membership. Review/apply it. Reimport it without
spurious changes. Change an element title or package in the fixture and verify
that the same identity is updated through the existing review flow. Removing
the plugin makes the profile unavailable without deleting prior user data;
replacing its artifact invalidates outstanding reviews.

## Limits

MDG validation, a QEA writer, live PCS synchronization and freely extensible
canonical metamodels are later increments. Synthetic tests establish the
declared mapping contract; real EA product compatibility remains unverified.
