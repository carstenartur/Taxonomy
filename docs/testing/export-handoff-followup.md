# Export follow-up on #1108

Integrated base: `2035fc523a6406dc9a20fb211ef6756859cb8c93` (#1108)
plus already merged main `89fcb169a4b455064ce3d504a359f60457125713`.
Exact source base tree: `18531654cdb2c9e672bc2c7e268b7970c2c7ef18`.
The follow-up must not merge before its parent. It does not change #1108 or main.

## Scope of the export commit

The current-view export owner exposes a visible Sparx EA/XMI button next to Visio,
including on existing templates. It shares the original control's disabled state,
uses the same frozen working view, and requests no analysis or persistence.
The button and its explanation are available in English/German. A confirmation
explains fresh-copy semantics, broad relationship projection and omitted layout.
Export controls use readable theme foregrounds rather than low-contrast accent colors.

The new `sparx` SPI adapter reuses `SparxXmiCodec`. Its ZIP contains `architecture.xmi`,
`manifest.json` and `README.txt`. All supplied graph members and source IDs are
retained. Supported codec relations use their established mapping; the explicitly
listed additional Taxonomy relations become labelled Associations with TRANSFORMED
loss entries and exact original types in tags/manifest. Unknown types fail closed.
Display-only containers become explicitly marked packages; native containment/layout
is not inferred from parent metadata. Parent IDs remain evidence. Cycles, missing
endpoints, duplicate IDs, nonfinite scores and oversized graph counts are rejected.

This is a **fresh-copy handoff**, not a new synchronization engine or a checkpoint.
A new delivery namespace prevents an unrelated EA model from being overwritten by
coincidentally identical source codes. IDs are stable within that namespace; direct
repeat exports are separate copies. Persisted `taxonomy.id` identity tags are never
forged. For scoped repeat updates use the existing reviewed Tool Integrations path.
XMI bytes are reparsed by the existing secure codec. The manifest binds their SHA-256.
Specific Microsoft Visio and Sparx EA desktop product acceptance remains unverified.

## Visio transport and diagnostics

A successful HTTP status is not proof of a file. Before creating a download URL,
the browser checks media type, redirects, nonempty content and the bounded ZIP
central directory. Required VSDX parts or Sparx bundle members must exist; malformed
or truncated packages, HTML login pages and JSON errors cannot be saved as VSDX.
This is a transport check, not an XML schema validator or desktop certification.
The existing server OPC/schema validator and its rejection cases remain authoritative.
Its failures now retain the original cause under `VISIO_PACKAGE_VALIDATION_FAILED`
and reach the existing server-export-error boundary (HTTP 500), not the invalid-user-
input boundary. Invalid graph inputs continue to return HTTP 400.

## Evidence and limits

- Production JavaScript: 63 combined export, inherited validation and version-comparison
  tests pass without failures/skips, including 19 current-diagram export cases. Seven bad transport
  responses plus absent Sparx support first reproduced eight intended failures;
  a further unrelated-ZIP case failed before central-directory membership checking.
- Actual Java 21 source compilation of the added mapper and adapter, against runtime
  dependency JARs, passed. Executable checks round-trip XMI via the existing codec,
  verify IDs/direction/Unicode, all twelve Taxonomy relation kinds, deterministic output
  for a fixed namespace, distinct copy namespaces and explicit malformed-input rejection.
  The same checks are wrapped by ordinary module JUnit tests.
- The actual controller/facade/registry path exports all 50/150 supplied Visio nodes
  without an LLM or derivation service and exports the supplied Sparx working view.
  These are direct production-component calls, not a full Spring HTTP session.
- A generated-package failure was observed as an incorrect IllegalArgumentException
  before the repair; its stable server-failure classification and preserved cause pass.
  The twelve existing corrupt-package JUnit cases retain their rejection assertions.
- Six Chromium component scenarios passed with the production export script and real
  generated binary samples: visible Sparx download, cancellation, disabled-state mirroring,
  Visio download, HTTP-200 HTML rejection, truncated VSDX rejection. Downloaded files
  were reopened and CRC checked. Both light/dark button contrast states were checked.
  These are controlled components, not full-application or desktop-product certification.
- The complete civilian scenario now requires all five registered adapters and reopens
  the actual Sparx ZIP/XMI, checking membership, original IDs, direction, source types,
  checksum and fresh-copy disclosure. Parent fresh-JVM isolation and every existing
  export assertion remain. This expanded complete scenario still requires CI execution.
- `./mvnw verify -DexcludedGroups="real-llm"` was attempted and stops before compilation
  at the Maven 3.9.16 download. Local Node is 22, not repository-pinned 24. Full canonical
  CI and independent review remain mandatory. No gate or dependency was weakened.

The reported original Visio file, exact deployed commit and desktop error are not
available. These repairs do **not** establish that the user's original failure is fixed.

## Remaining approved work

The larger Preferences/Decision/toolbar and grouped-DSL/folding/outline changes are
tracked separately from this export commit. Their local source port preserves #1108's
visibility/cancellation protections, but its local checks are not certification of
this published export-only head. Do not claim those functions are deployed from it.
