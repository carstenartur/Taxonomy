# Alfabet inventory-source plugin

The user approved implementing the proposed optional Alfabet plugin on 28 October
2026. Reuse the existing report reader and tests; expose it through the same
`InventorySourceExtension` as LeanIX. Keep authorization, comparison, taxonomy
mapping and reviewed decisions in the host. Reads are explicit, bounded and local
to one invocation; loading the plugin performs no I/O or credential lookup.

## Approach

Retaining the reader in `taxonomy-interop` would leave the vendor integration
mandatory. Wrapping that whole module would pull host implementation dependencies
into the plugin. The selected approach moves the reader and its tests into the
independently built `plugins/taxonomy-alfabet-plugin`, using only the provided SDK
and privately shaded HTTP/Jackson dependencies. Preserve the existing connection
address policy inside the plugin; do not make OSLC a plugin dependency.

## Contracts

Host SPI 1.2 adds immutable, bounded non-secret `Request.sourceOptions` and an
optional source-bound `ReadAccess.principal()` grant. Retain the old Request
constructor and credential-only lambda compatibility for existing plugins.
Alfabet requires profile `alfabet-read@1` and SPI >=1.2.0; LeanIX keeps its existing
SPI requirement and empty options. Unknown Alfabet options fail before I/O.

Options declare `currentProfile`, `authentication` (BEARER, CLIENT_CREDENTIALS or
PASSWORD), `object.<sourceType>.report`, and optional
`relation.<alias>.{report,className,sourceRef,targetRef,activeFrom,activeUntil}`.
Relations in profile 1 are administrator-declared usage relationships (`USES`).
All report/column names are explicit. No arbitrary query, endpoint, SQL, secret
reference or insecure HTTP option is accepted by the plugin entry point.
Explicit object-ID selections are rejected before I/O because the existing report
protocol does not implement that selector. Administrative reports define scope.

Objects use `(instanceId, refstr, original RefStr)` identities, explicit type
projections and distinct absent/null/empty field values. Revisions stay null and
lifecycle information stays raw mapped evidence, with no availability inference.
Relation IDs are observation-local; the external relation key is null. Repeated
base RefStr values are not collapsed. Duplicate object identities across reports,
malformed endpoints and exhausted budgets fail the entire observation.

The host supplies bound credential/principal arrays (wiped after use), approved
TLS trust and private-network policy. All authentication and report calls share
request/row/byte/time budgets. No successful partial result, canonical writes,
background work, deletion authority or extra persistence is introduced.

## Delivery and verification

Startup-only ServiceLoader registration, independent shaded JAR and manifest;
extend the existing packaged-plugin Maven tests and release version script.
Move the reader/transport regressions, add real local HTTPS source-contract tests,
SDK compatibility tests and packaged-host loading/absence tests. Run focused Maven
suites, independent plugin verify, plugin packaging and the repository's canonical
`./mvnw -B verify -Pci -DrunOnnxTests=true`; report prerequisite failures accurately.
Live Alfabet installation acceptance remains NOT_EXECUTED.
