# Architecture Handoff from an Immutable Snapshot

Use the **Copilot Architecture Workbench** when the recipient must receive the same architecture that was reviewed in Taxonomy. The workbench addresses one persisted analysis snapshot by project ID and snapshot ID. Downloading an artefact serializes that stored projection; it does **not** run the LLM again.

This is distinct from convenience exports on the ad-hoc analysis screen. Those older paths are not yet evidence-bound to a persisted portfolio snapshot and must not be used to claim that a file is the exact reviewed result.

## Workflow

1. Open a project requirement that has a successful current analysis.
2. Select **Open architecture workbench**.
3. Confirm the displayed project, requirement, snapshot, workspace, branch and commit provenance.
4. Select one format from **Export format**.
5. Choose **Download selected format**.

The selector remains disabled until the requested snapshot has loaded successfully. A missing, invalid or inaccessible snapshot is not replaced by the latest analysis and never triggers a new analysis.

## Format roles

| Format | Handoff role | Intended use | Current limits |
|---|---|---|---|
| Evidence JSON | `canonical-evidence` | Machine-readable snapshot, canonical graph, render scene, warnings, provenance and declared export profiles | Not yet a complete ZIP handoff package or universal import format |
| SVG | `stable-human-view` | Scalable, deterministic visual reference for documents and review | Visual representation, not a complete architecture model |
| PDF | `stable-human-view` | Stable printable reference | Visual representation, not a complete architecture model |
| ArchiMate Exchange XML | `experimental-model-exchange` | Standards-oriented architecture interchange | Experimental until the full mapping, semantic round trip and independent-tool evidence in #967 are complete |
| Mermaid | `lossy-text-projection` | Git, Markdown, wiki and documentation diagrams | Presentation-oriented and intentionally omits unsupported Taxonomy semantics |
| Structurizr DSL | `lossy-text-projection` | C4/Structurizr-oriented continuation | Taxonomy concepts are mapped to a smaller software-architecture vocabulary |
| Visio VSDX | not offered by this workflow | Future editable Microsoft Visio handoff | Withheld until package/schema and real-product compatibility work in #965 is complete |

No format other than Evidence JSON should be interpreted as Taxonomy's canonical persistence representation. The canonical source remains the exact repository/workspace/branch/commit and immutable analysis snapshot shown by the workbench.

## Verifying that files belong to the same graph

Every response carries the following headers:

| Header | Meaning |
|---|---|
| `X-Taxonomy-Architecture-Snapshot` | Persisted analysis snapshot ID |
| `X-Taxonomy-Architecture-Commit` | Authoritative Git commit, when recorded by the snapshot |
| `X-Taxonomy-Architecture-Graph-SHA256` | Format-independent fingerprint of the selected node/relationship graph |
| `X-Taxonomy-Export-Profile` | Versioned exporter profile used for this file |
| `X-Taxonomy-Export-Role` | Declared handoff role from the table above |
| `X-Taxonomy-Export-Content-SHA256` | SHA-256 of the downloaded bytes |
| `ETag` | Content digest in HTTP ETag form |

Files generated from the same snapshot and semantic graph carry the same architecture graph fingerprint, while their content digests differ by format. Evidence JSON embeds the snapshot coordinates, graph fingerprint and all declared format profiles so provenance remains available after the HTTP headers have been separated from the file.

## API

```text
GET /api/projects/{projectId}/architecture-workbench/{snapshotId}/exports/{formatId}
```

Supported `formatId` values are:

```text
json
svg
pdf
archimate
mermaid
structurizr
```

The existing snapshot-specific `.svg` and `.pdf` URLs remain available and use the same export/provenance contract.

## Current non-goals and open work

This workflow deliberately does not yet claim:

- a certified editable Visio handoff;
- lossless conversion between Taxonomy and ArchiMate, Mermaid or Structurizr;
- a complete `manifest.json`/`losses.json` multi-file handoff package;
- semantic round-trip equivalence through every external modelling tool;
- authority for legacy exports that accept only free-form business text.

Those remaining items are tracked by #965, #966, #967 and the broader interoperability programme #926.
