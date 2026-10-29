# Feature Completeness Matrix

This matrix records implemented feature surfaces and the documentation evidence linked for them. It is not a button-by-button functional acceptance report. A ✅ records the indicated surface or explanation; current behavioral acceptance requires the linked test owner and a result for the relevant revision.

> See [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--user-facing-features)
> for the product rules.
>
> Architecture-export status is defined by the support boundary documented below.
> File generation and broad third-party interoperability are tracked separately.

## Connected workflows and their limits

The following distinguishes delivered behavior from quality claims and unfinished
integration. It does not turn technical tests or an older screenshot into a fresh
acceptance of the current application.

| Area | Delivered behavior / current state | Boundary |
|---|---|---|
| Architecture-guided requirement clarification | Saved proposals, typed questions, human decisions, adoption review, inherited decisions and historical exports | Explicit adoption is separate from approval and reanalysis; live-model semantic quality is not established by playback tests. |
| Selectable analysis and continuation | Selected roots, FULL/TAXONOMIES_ONLY modes, retained work plans and reuse of validated answers | Scope and errors remain visible; a completed plan is not proof of universal coverage. |
| Semantic editing and Git checkpoints | Durable operations with rationale/inverse data and separate stable Git versions | Editor undo/redo and branch-head undo are different operations. |
| Local ONNX | Embedding search/scoring | Not a generative relationship or reformulation model; DE/EN interface support is not equal retrieval quality. |
| Planning profiles | Versioned planned go-live and standard/directive references | A date is not operational availability; a reference is not proof of compliance. |
| Portable backup and external contribution | Supporting capture/archive adapters and bounded stand-bundle work | Production capture, complete history/restore and reviewed reintegration are not a completed end-user workflow. |

See the [clarification workflow](PROJECT_REQUIREMENT_PORTFOLIO.md#requirement-clarification-workflow),
[scope/progress guide](PROJECT_REQUIREMENT_PORTFOLIO.md#analysis-scope-and-progress) and
[editor guide](ARCHITECTURE_EDITOR.md).

A missing screenshot reference means that this matrix does not provide that part
of its completion evidence. It must not silently count as a checked screenshot.
The [view and control inventory](../testing/ui-view-control-inventory.md) maps current templates and control groups to explanations, test owners, captures and remaining gaps. Source inspection and historical screenshots do not prove that every control works.

## End-User Features (GUI-first)

| Feature | GUI | REST | User Guide | Screenshot | Help/Tooltip | DE/EN i18n | Status |
|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| Architecture editor | ✅ | ✅ | ✅ [Editor guide](ARCHITECTURE_EDITOR.md) | [Editor](../images/architecture-editor-current-en.png) | See guide | ✅ | Implemented; capture shows one example workspace |
| Optional database setup assistant | ✅ | ✅ | ✅ [Database setup](DATABASE_SETUP.md) | [Connection step](../images/database-setup-current-en.png) | ✅ | ✅ | Implemented; configuration preparation does not switch or migrate the running database |
| Requirement analysis | ✅ | ✅ | ✅ §4 | #15–17 | ✅ | ✅ | Implemented |
| Scored tree exploration | ✅ | ✅ | ✅ §5 | #15, 35 | ✅ | ✅ | Implemented |
| View modes (6 modes) | ✅ | ✅ | ✅ §5 | #5–9, 39, 69 | ✅ | ✅ | Implemented |
| Architecture view | ✅ | ✅ | ✅ §7 | — | ✅ | ✅ | ⚠️ Implemented; #20/38 are retired seeded illustrations, not current evidence |
| Relation proposals (accept/reject) | ✅ | ✅ | ✅ §9 | #12, 13, 36 | ✅ | ✅ | Implemented |
| Snapshot-bound browser/SVG/vector PDF views | ✅ | ✅ | ✅ export boundary | [Workbench](../images/architecture-workbench-current-en.png) | ✅ | ✅ | ✅ Supported human-readable views of the selected persisted snapshot |
| Mermaid/JSON architecture projections | ✅ | ✅ | ✅ export boundary | #23, 33 | ✅ | ✅ | ⚠️ Migration to the common snapshot-bound artifact envelope, authority headers, and loss manifest remains open in #966 |
| ArchiMate 3.1 export subset | ✅ | ✅ | ✅ export boundary | #23, 33 | ✅ | ✅ | ⚠️ Experimental bounded subset; versioned mapping/loss profile implemented; independent-tool acceptance remains open in #967 |
| Visio 2012 VSDX export subset | ✅ | ✅ | ✅ export boundary | #23, 33 | ✅ | ✅ | ⚠️ Experimental bounded subset; typed identities, authorized metadata and versioned loss manifests are implemented; Microsoft Visio desktop certification remains open in #965 |
| Full-text search | ✅ | ✅ | ✅ §11 | #29 | ✅ | ✅ | Implemented |
| Semantic/Hybrid search | ✅ | ✅ | ✅ §11 | #30, 31 | ✅ | ✅ | Implemented |
| Graph exploration (upstream/downstream) | ✅ | ✅ | ✅ §8 | [Graph entry, DE](../images/graph-entry-current-de.png) | ✅ | ✅ | Implemented |
| Failure impact analysis | ✅ | ✅ | ✅ §8 | #22 | ✅ | ✅ | Implemented |
| Requirement coverage | ✅ | ✅ | ✅ §11c | #26, 27 | ✅ | ✅ | Implemented; historical coverage-dashboard captures |
| Gap analysis | ✅ | ✅ | ✅ §11d | — | ✅ | ✅ | ⚠️ Implemented; #26/27 show coverage, not gap analysis |
| Pattern detection | ✅ | ✅ | ✅ §11f | — | ✅ | ✅ | ⚠️ Implemented; screenshot not linked |
| Recommendations (Copilot) | ✅ | ✅ | ✅ §11e | — | ✅ | ✅ | ⚠️ Implemented; screenshot not linked |
| Reports (MD/HTML/DOCX) | ✅ | ✅ | ✅ §10a | #23 | ✅ | ✅ | Implemented |
| Workspace management | ✅ | ✅ | ✅ §13 | #43–45 | ✅ | ✅ | Implemented |
| Branch compare | ✅ | ✅ | ✅ §12 | — | ✅ | ✅ | ⚠️ Implemented; current screenshot missing |
| Context transfer (copy back) | ✅ | ✅ | ✅ §12 | — | ✅ | ✅ | ⚠️ Implemented; current screenshot missing |
| Variant creation | ✅ | ✅ | ✅ §12 | #46, 47 | ✅ | ✅ | Implemented |
| Merge (with conflict resolution) | ✅ | ✅ | ✅ §12 | — | ✅ | ✅ | ⚠️ Implemented; current screenshot missing |
| Cherry-pick (with conflict resolution) | ✅ | ✅ | ✅ §12 | — | ✅ | ✅ | ⚠️ Implemented; current screenshot missing |
| Branch delete | ✅ | ✅ | ✅ §12 | — | ✅ | ✅ | ⚠️ Implemented; current screenshot missing |
| DSL editor (syntax highlighting, autocompletion) | ✅ | ✅ | ✅ §11g | #34, 40 | ✅ | ✅ | Implemented |
| Version history (commits) | ✅ | ✅ | ✅ §12 | #41, 66 | ✅ | ✅ | Implemented |
| Sync from shared / Publish | ✅ | ✅ | ✅ §12 | — | ✅ | ✅ | ⚠️ Implemented; current screenshot missing |
| Leaf justification | ✅ | ✅ | ✅ §6 | #18 | ✅ | ✅ | Implemented |
| Document import (PDF/DOCX) | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implemented; screenshot evidence not linked here |
| Source provenance tracking | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implemented; screenshot evidence not linked here |
| AI-assisted requirement extraction | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implemented; screenshot evidence not linked here |
| Regulation-to-architecture mapping | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ⚠️ Implemented; screenshot evidence not linked here |

## Architecture export support boundary

This boundary is deliberately narrower than the set of file extensions that the
application can generate.

### Export authority

The read-only Architecture Workbench serializes the explicitly selected persisted
architecture snapshot. The snapshot is authorized for the requested project,
workspace, branch, and commit before serialization. Downloading an existing
snapshot does not invoke the LLM, select a newer result, or rebuild the graph from
current preferences.

The browser view and snapshot-bound download paths therefore describe one reviewed
result. Export formats may still omit or transform semantics.

### Evidence by format

| Output | 1.4.0 support boundary | Evidence already present | Evidence not claimed |
|---|---|---|---|
| Browser view, SVG, and vector PDF | Supported human-readable views of the selected persisted snapshot | One neutral server-side diagram scene and snapshot-bound rendering | General model interchange or editability in an external architecture tool |
| Mermaid and JSON architecture projections | Available special-purpose text/data projections | Existing bounded serializers | Migration to the common snapshot-bound artifact envelope, exact authority headers, and a complete cross-format loss manifest remain pending in #966; do not infer snapshot equivalence without endpoint evidence |
| **ArchiMate 3.1** | **Experimental bounded ArchiMate 3.1 subset** | Offline validation against the pinned 3.1 XSD set; snapshot-bound workbench ZIP and loss manifest; a separate installed-Archi 5.10.0 check of the exact workbench profile with recorded representation losses | **Independent-tool interoperability is not certified**; Archi erases numeric/boolean declaration types, property-value language markers and source folders. The strict typed reader rejects that changed contract; acceptance in another independent consumer remains pending. See the [profile and evidence contract](../dev/ARCHIMATE_EXCHANGE_PROFILE.md#consumer-evidence-and-remaining-acceptance). |
| **Visio 2012 VSDX** | **Experimental bounded Visio 2012 subset** | Deterministic OPC/VSDX package structure, relationship and content-type checks, masterless connector geometry, XMLBeans validation, and Apache POI technical-reader loading/rendering; the workbench download is bound to the selected snapshot | **Microsoft Visio desktop certification pending**: open, edit, save, and reopen behavior in a documented Microsoft Visio desktop version has not been certified; the versioned handoff/loss manifest and stable typed Shape Data are included |

### Required product wording

Use these descriptions consistently:

- **Experimental bounded ArchiMate 3.1 subset — mapping profile and loss manifest included; independent-tool acceptance pending.**
- **Experimental bounded Visio 2012 subset — Microsoft Visio desktop certification pending.**
- **Snapshot-bound export — downloading does not invoke the LLM.**

Until #965 and #967 are complete, public documentation and UI text must not claim
general Visio 2013+ compatibility, production-ready editable VSDX handoff, general
ArchiMate standards certification, lossless semantic round trips, or import
acceptance by named third-party tools. #964 owns the frozen-SHA documentation
reconciliation; #966 owns the common artifact-envelope and authority work.

## Admin/Automation Features (API-first — no GUI required)

| Feature | REST | API Docs | Status |
|---|:---:|:---:|---|
| User management (CRUD) | ✅ | ✅ | Implemented |
| LLM diagnostics | ✅ | ✅ | Implemented |
| Embedding status | ✅ | ✅ | Implemented |
| Startup status | ✅ | ✅ | Implemented |
| Workspace eviction (admin) | ✅ | ✅ | Implemented |

## Legend

| Symbol | Meaning |
|---|---|
| ✅ | Surface or explanation is present in this column; not a claim that every control or state was tested |
| ⚠️ | Partially done — needs verification or completion |
| 🔴 | Significant gap — GUI may exist but docs/help/screenshot missing, or REST-only |
| ❓ | Unknown — needs audit |

## Screenshot Index

Numbered images in `docs/images/` are historical references unless a capture is explicitly bound to a run and source revision. They have several test owners; `ScreenshotGeneratorIT` does not own every image. Captures #20, #37 and #38 relied on unsupported catalogue-relation seeding and are retired; they do not prove that catalogue Graph queries include accepted workspace proposals. Capture #32 is also retired: its graph-semantic search fixture did not establish a supported workspace relation projection and can return `409 NOT_BUILT`. Retired presentation fixtures #48–65 and #67–68 are also not application acceptance evidence. See the [capture instructions and provenance](../../.github/copilot-ref-screenshots.md) and [view inventory](../testing/ui-view-control-inventory.md).

Reference format: `#NN` = `docs/images/NN-*.png`. User Guide section references: `§N` = USER_GUIDE.md section N.
