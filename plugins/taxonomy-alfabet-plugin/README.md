# Alfabet: optional read-source plugin

This independent Java 25 plugin exposes the existing Alfabet API-v2 report reader
through `InventorySourceExtension`. The reader and its tests moved out of
`taxonomy-interop`. The host owns authorization, evidence storage, taxonomy mapping
and reviewed comparison. No controller, background job, database or remote write
is added. Plugin discovery performs no network or credential lookup.

## Build and installation

Plugin ID `taxonomy.alfabet`, extension ID `alfabet`, kind `INVENTORY_SOURCE`, mode
`STARTUP`. Requires host SPI **>=1.2.0 and <2.0.0**; older hosts reject the artifact
before class loading. Stop the host before installing/removing the final shaded
`target/taxonomy-alfabet-plugin-<version>.jar` in its operator-managed plugin directory.
Use the host's checksum/installation policy; never install `original-*.jar`.

```sh
./mvnw -B -pl taxonomy-extension-api -am install
./mvnw -B -f plugins/taxonomy-alfabet-plugin/pom.xml verify
./mvnw -B verify -Pplugin-packaging-tests
./mvnw -B verify -Pci -DrunOnnxTests=true
```

The plugin is outside the default reactor and distribution, like LeanIX. The
existing release transition updates both independent POMs. Only the provided SDK
is a host dependency. HTTP/Jackson are shaded into private packages; no SDK,
interop, Spring or nested JAR is bundled.

## Source profile `alfabet-read@1`

The host constructs an authorized `InventoryReadContracts.Request`: external
system `alfabet`, authority `LINK_ONLY`, profile `alfabet-read`, version `1`.
Bind the source to its actual installation, repository and organization before
calling it. The configured instance label does not prove credential visibility.
The immutable `sourceOptions` and `configurationRevision` stay with the observation.
They contain no credentials or secret-store references. Unknown keys fail before
access-grant lookup. Arbitrary endpoint, HTTP method, query, SQL or script settings
are not supported.

| Option | Meaning |
|---|---|
| `currentProfile` | Approved Alfabet user profile for every call |
| `authentication` | Explicit `BEARER`, `CLIENT_CREDENTIALS` or `PASSWORD` |
| `object.<sourceType>.report` | Approved object report for each selected type projection |
| `relation.<alias>.report` | Approved usage report; alias starts with a letter and contains letters, digits, `_` or `-` |
| `relation.<alias>.className` | Expected base-object class |
| `relation.<alias>.sourceRef` | Column containing the source object's actual RefStr |
| `relation.<alias>.targetRef` | Column containing the target object's actual RefStr |
| `relation.<alias>.activeFrom` | Optional raw start-date column |
| `relation.<alias>.activeUntil` | Optional raw end-date column |

At most 16 distinct reports are configured. `TypeProjection.textFields` maps
labels to exact columns; configure at least one field or subtype column. Unknown,
null or missing configured subtypes remain `OTHER`. No class/column semantics are
guessed. Relation reports in profile 1 must represent `USES`; other relationship
meanings require a later versioned profile.

`origin` is the HTTPS API application base with trailing slash, for example
`https://alfabet.example.invalid/AlfabetAPIServer/`, without userinfo, query,
fragment, encoded path or dot segments. `selectedIds` must be empty because the
existing report protocol has no arbitrary ID selector. Scope the approved
server-side report instead. `readUsageRelations=false` does not fetch relation
reports; true requires at least one complete relation declaration.

[Example settings and projections](examples/alfabet-read-profile.json) are a
nonfunctional template, not automatically loaded application configuration or a
browser request payload. Supply the authorized IntegrationContext separately.
Replace names/columns with verified values for the actual installation and edition.

## Host invocation and grants

```java
try (var lease = catalog.acquire(
        new ExtensionKey(ExtensionKind.INVENTORY_SOURCE, "alfabet"),
        InventorySourceExtension.class)) {
    Observation observation = lease.extension().read(approvedRequest, boundAccess);
    // Retain the lease's plugin identity with the reviewed evidence.
}
```

`ReadAccess.credential()` supplies an owned character array: bearer token, client
secret or password. Client-credentials/password modes additionally use the bound
client ID/username from `principal()`. Arrays are wiped after use; do not return
shared backing arrays. Tokens are invocation-local. There is no ambient
credential-store lookup at the plugin entry point, nor credential-bearing
observations or exception causes. Keep HTTP wire logging disabled.

`ReadAccess.tls()` supplies approved enterprise trust, preserving certificate and
hostname verification. Private addresses require `allowPrivateNetworks()` from
the host; literal and connection-time resolved addresses are checked. Metadata,
link-local, unspecified and multicast targets stay forbidden. Only `api/token`
(when necessary) and `api/v2/objects` are reachable. POST on the objects endpoint
reads a report. Redirects, retries, cookies and compressed responses are rejected.

## Evidence and limits

Object identity is `(instanceId, refstr, original RefStr)`. Missing revisions stay
null. Absent/null/empty columns are distinct. Only declared fields become object
evidence; raw status/date strings do not establish availability or suitability.
A relation row's base RefStr is an object reference, never a relationship ID.
Distinct rows retain observation-local IDs and null external relation keys.
Actual source/target reference columns are required. Duplicate objects across
reports, exact duplicate relation rows, unexpected classes, nonempty nested data
and malformed payloads fail explicitly.

All authentication/report calls share request, object, relation, byte and time
budgets. Default shared limits: 100 rows/page, 100 requests, 2,000 objects, 5,000
relations, 1 MiB/response, 8 MiB total, 60 seconds. These are ceilings, not measured
capacity. Rejected responses are cancelled before HTTP cleanup can drain them.
Token expiry can shorten the overall deadline.

A short page exhausts one report; this proves neither atomicity nor global
inventory completeness. Reports must be read-only and deterministically ordered.
Success declares `NO_ATOMIC_SNAPSHOT` and `NO_DELETION_AUTHORITY`. Missing objects
do not imply nonexistence. Failure returns no successful partial observation;
the host must retain earlier accepted evidence.

## Verification boundary

JUnit tests use synthetic reports, real local HTTP/HTTPS and generated certificates.
The existing `ExternalPluginPackagedIT` independently builds the JAR with an empty
Maven repository and checks packaged-host loading/absence. These checks do not
establish real product compatibility. **Live Alfabet execution: NOT_EXECUTED.**
Acceptance requires the actual edition/version, approved report definitions and
permissions, multi-page objects, a relation report, denied access and failed refresh.
The required installation/report access and real-product evidence are tracked in
[EXT-07](../../docs/internal/TODO-EXTERNAL-INPUT.md#ext-07).

Manufacturer references:

- [Report export](https://documentation.alfabet.com/11-9/en/AFLREST_AFL_RESTService_DataExport.html)
- [Authentication](https://documentation.alfabet.com/en/AFLREST_RESTService_Authentication.html)
- [API-server setup](https://documentation.alfabet.com/11-10/en/Instal_SetUpAPIServer.html)
- [Common comparison design](../../docs/superpowers/specs/2026-10-18-inventory-planning-sources-design.md)
