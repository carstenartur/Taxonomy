# SAP LeanIX: optional read-source plugin

This independent Java 25 plugin prepares a bounded read path from SAP LeanIX into
Taxonomy's framework-free `InventorySourceExtension`. It is **not** a complete
portfolio comparison screen, a second inventory database or a synchronization engine.

## Architecture and installation

The artifact is `com.taxonomy:taxonomy-leanix-plugin:1.5.1-SNAPSHOT`, plugin ID
`taxonomy.leanix`, extension ID `leanix`, kind `INVENTORY_SOURCE`, mode `STARTUP`.
It uses the existing `TaxonomyPlugin` ServiceLoader/catalog and exact-artifact leases.
There is no network access, credential lookup or background work when loading it.
Stop the host before installing/removing the JAR; dynamic activation is not supported.

The new source SPI belongs to `taxonomy-extension-api`, not to the plugin or a
Spring feature. Host SPI **1.1.0** is required; an old 1.0 host must reject the JAR
before class loading. Existing compatible 1.x plugin ranges remain valid. Only
provided SDK classes are referenced. HTTP/Jackson dependencies are shaded into
plugin-private packages; SDK, PF4J and nested JARs are not bundled.

The plugin is intentionally not a default root-reactor module or a shipped feature.
Its independent build and packaged-host checks extend the **existing**
`ExternalPluginPackagedIT`, selected by `plugin-packaging-tests`. No new CI workflow,
test framework or selector is introduced. From a complete checkout:

```sh
./mvnw -B -pl taxonomy-extension-api -am install
./mvnw -B -f plugins/taxonomy-leanix-plugin/pom.xml verify
./mvnw -B verify -Pplugin-packaging-tests
./mvnw -B verify -Pci -DrunOnnxTests=true
```

Install the final shaded `target/taxonomy-leanix-plugin-1.5.1-SNAPSHOT.jar`, **not**
`original-*.jar`, in the operator-managed plugin directory of a compatible host.
Follow the host's existing artifact checksum/installation policy. A loaded metadata
entry does not mean a source has been authorized, configured or queried.

## Supported read profile: `leanix-read@1`

Public SAP documentation is the protocol reference, not proof of compatibility with
an actual customer workspace. The supported query profile reads:

- `Application` and `ITComponent`: `id`, `rev`, `type`, name, description and
  lifecycle phases (`phase`, `startDate`). Original phase names/dates are preserved;
  all are declared source evidence, not an observed production availability claim.
- An administrator-selected scalar subtype field and up to eight text fields per
  type, aliased in fixed queries. Identifiers are validated before interpolation;
  user-supplied queries, nested field expressions and mutations are not accepted.
- Optionally Application-to-ITComponent usage relationships, including relation ID,
  target reference and `activeFrom`/`activeUntil`. Relation pages are read separately
  and their owning application revision must still match the object read.

An explicit set of fact-sheet IDs performs individual reads instead of enumerating
the workspace. An empty set enumerates the configured types **within the authorized
technical user's visibility and the request budgets**, not within an inferred
organization boundary. There is no organizational filter in profile 1. Use a
restricted technical user and explicit IDs for a narrowly scoped pilot.

Pagination uses `first`, `after`, `pageInfo.hasNextPage` and `endCursor`. Short pages
are not treated as proof of completion. Object and relation cursors are independent.
Repeated cursors, duplicate identities, changed owner revisions and missing connection
metadata fail the whole read rather than returning a successful prefix.

`ITComponent` is not automatically a technology product. The supplied profile must
explain its meaning in the installation. The example deliberately maps it to `OTHER`.
With a configured subtype discriminator, unknown/absent/null values remain `OTHER`,
not the default kind. No source type automatically selects a taxonomy node.

The shared contract keeps the request's selection and projections with the observation,
plus source identities, raw fields, lifecycle evidence and limitations. Absent, null,
empty and unsupported values are distinct. A source without a revision can use null;
a source without a durable relation ID uses an observation-local ID and null external
relation key. LeanIX itself requires its documented revisions and relation IDs.
This is the **read-observation subset** of the common design, not completion of all
its evaluation, temporal interpretation, evidence persistence and review requirements.

## Authentication, trust and resource limits

Only two fixed HTTPS endpoints on the configured origin are called:

- `POST /services/mtm/v1/oauth2/token`, HTTP Basic `apitoken:<API token>`, form
  `grant_type=client_credentials`;
- `POST /services/pathfinder/v1/graphql`, bearer token and fixed query documents.

Use a dedicated technical user with read-only permissions. The host must first bind
the source to its authorized repository/organization, confirm the actual LeanIX
workspace and choose the immutable configuration. Profile 1 has no server-verified
workspace identity discovery; it returns `WORKSPACE_BINDING_OPERATOR_DECLARED`.
A configured workspace label is not proof that an API token belongs to it.

The host supplies one bound credential through `ReadAccess`, not unrestricted access
to an environment/secret store. The returned owned character array is wiped. Tokens
remain request-local, are reused across pages, expire within the invocation budget,
and are never included in observations or chained exceptions. Disable HTTP wire
logging; never put tokens in request JSON, browser state, reports or source control.
Returned source text is untrusted: the consumer must apply its ordinary escaping,
visibility and LLM-context policy, not treat it as instructions or executable markup.

HTTPS hostname/certificate verification remains enabled. `ReadAccess.tls()` can carry
the host's approved enterprise trust store. Public networks are the default; private
network access requires a server-side opt-in, not a browser flag. Both literal IPs
and addresses resolved at connection time are checked. Metadata/link-local, wildcard
and multicast addresses stay prohibited. There are no redirects, automatic retries,
cookies, ambient proxy configuration or transparent content decompression.

Budgets cover token acquisition and every object/relation page together. Defaults:
100 items/page, 100 requests, 2,000 objects, 5,000 relations, 1 MiB/response,
8 MiB total and a 60-second request deadline. These are ceilings, not benchmark
measurements. Connection/socket timeouts and cancellation bound HTTP execution;
OS DNS resolution remains dependent on the host's resolver and network policy.
Large relation selections can exhaust budgets: narrow the selection rather than
silently accepting a partial inventory.

HTTP 401/403, 429, invalid JSON, HTTP-200 GraphQL `errors`, overlarge responses,
limits and revision changes are explicit failures with sanitized codes. A failure
must leave previously accepted evidence intact. Success still carries
`NO_ATOMIC_SNAPSHOT` and `NO_DELETION_AUTHORITY`. Missing records never justify
nonexistence, removal, procurement or a positive suitability decision.

## Programmatic host composition

The example below assumes an already authorized, server-constructed `request` and
credential/TLS grant. It is **not** a public arbitrary-profile proxy endpoint:

```java
try (var lease = catalog.acquire(
        new ExtensionKey(ExtensionKind.INVENTORY_SOURCE, "leanix"),
        InventorySourceExtension.class)) {
    // Retain/check lease.plugin() identity and the approved configuration in host evidence.
    Observation observation = lease.extension().read(request, boundAccess);
    // Feed the observation into reviewed inventory evidence, not canonical architecture writes.
}
```

[Example projection/settings](examples/leanix-read-profile.json) contain no credentials
and are **not automatically loaded**. `IntegrationContext` must use `LINK_ONLY`,
external system `leanix`, profile `leanix-read`, version `1`. It comes from the existing
authorized host context; do not accept caller-forged workspace/actor authority.
Neither queries nor the example read a business requirement or taxonomy catalogue.

## Verification boundary and installation acceptance

Ordinary JUnit tests exercise the actual HTTP client against a local HTTPS server,
with a generated test certificate and normal certificate verification. Fixtures are
synthetic, not a LeanIX export. The existing packaged test independently builds the
JAR from the SDK, runs its JUnit suite and starts a packaged host with the JAR.
No local scenario runner is substituted for JUnit/Maven.

Before advertising support for a real workspace, verify its schema, source types,
subtype/scalar fields, revisions, lifecycle representation and **relation connection
arguments/paging**, token permissions and workspace binding. Execute at least a
multi-page object read, relation read, known nonmatch, permission error and failed
refresh with authorized real data. Public example shapes do not establish every
workspace's GraphQL schema. Live LeanIX acceptance remains **NOT_EXECUTED**.

Not included: vendor reference-catalog support dates, transformations/target-architecture
simulation, initiatives, organizational relationships, user interface, persistent
source configuration, automatic taxonomy mapping, suitability scoring, external
writes or the complete Alfabet-to-neutral-contract bridge. Those remain host/shared
comparison work, except for later explicitly versioned source-profile extensions.

## Public references

SAP sources consulted 18 October 2026; no customer installation was contacted:

- [GraphQL API](https://help.sap.com/docs/leanix/ea/graphql-api)
- [Authentication](https://help.sap.com/docs/leanix/ea/authentication-to-sap-leanix-services)
- [Technical users](https://help.sap.com/docs/leanix/ea/technical-users)
- [Pagination](https://help.sap.com/docs/leanix/ea/pagination-in-graphql)
- [Filtering](https://help.sap.com/docs/leanix/ea/filtering-in-graphql)
- [Relationships](https://help.sap.com/docs/leanix/ea/managing-relations-between-fact-sheets)
- [REST-to-GraphQL migration and activeFrom/activeUntil](https://help.sap.com/docs/leanix/ea/migrating-from-fact-sheets-rest-api-to-graphql-api)
- [Common Taxonomy design](../../docs/superpowers/specs/2026-10-18-inventory-planning-sources-design.md)
