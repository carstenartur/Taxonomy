# Alfabet: optional inventory-source plugin

Alfabet report reads are now provided by the independently built
[`taxonomy-alfabet-plugin`](../../plugins/taxonomy-alfabet-plugin/README.md).
It uses the same `InventorySourceExtension` and neutral observations as LeanIX,
with explicit Alfabet report configuration through host SPI 1.2.

## Migration from the prepared library

The earlier `com.taxonomy.interop.alfabet.AlfabetReadClient` and its tests moved out
of `taxonomy-interop`. There was no automatic Spring registration, controller,
job or persistent configuration to migrate. Programmatic callers now construct
an authorized `InventoryReadContracts.Request`, acquire the `alfabet` catalog
lease and invoke `read(request, boundAccess)`. Reader classes are private plugin
implementation details; the common SDK is the host integration boundary.

Replace the old `AlfabetReadProfile` JSON with
[the source-options/projection template](../../plugins/taxonomy-alfabet-plugin/examples/alfabet-read-profile.json).
The `IntegrationContext` comes from existing repository/organization authorization;
the example is not a browser-facing request payload. The host supplies a bound
credential and optional principal through `ReadAccess`. Environment-variable names
and actual secrets no longer belong in profile settings.

Install the shaded JAR in the operator-managed plugin directory with the host
stopped. Alfabet remains optional, startup-only and explicitly invoked. The host
retains identity review, evidence persistence, taxonomy mapping and portfolio
decisions. No second store, automatic synchronization or remote writes are added.

## Preserved boundaries

The fixed API-v2 report protocol, connection-time network policy, bounded reads,
credential-safe failures, absent/null/empty columns and non-atomic evidence remain.
Repeated relation base references are not relationship identities. Missing records
never authorize deletion. Failed reads return no successful partial observation
and must not replace earlier evidence with an empty inventory.

The [plugin documentation](../../plugins/taxonomy-alfabet-plugin/README.md) owns the
current option schema, installation, Maven verification and acceptance checklist.
**Real Alfabet execution remains NOT_EXECUTED**; synthetic and packaged-host tests
are not acceptance against an actual installation.
