# Feature Completeness Matrix

This matrix tracks the delivery status of all major product features.
A feature is only complete when all required columns show ✅.

> See [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--user-facing-features)
> for the product rules.
>
> Architecture-export status is defined by the support boundary documented below.
> File generation and broad third-party interoperability are tracked separately.

## End-User Features (GUI-first)

| Feature | GUI | REST | User Guide | Screenshot | Help/Tooltip | DE/EN i18n | Status |
|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| Requirement analysis | ✅ | ✅ | ✅ §4 | ✅ #15–17 | ✅ | ✅ | ✅ Complete |
| Scored tree exploration | ✅ | ✅ | ✅ §5 | ✅ #15, 35 | ✅ | ✅ | ✅ Complete |
| View modes (6 modes) | ✅ | ✅ | ✅ §5 | ✅ #5–9, 39, 69 | ✅ | ✅ | ✅ Complete |
| Architecture view | ✅ | ✅ | ✅ §7 | ✅ #20, 38 | ✅ | ✅ | ✅ Complete |
| Relation proposals (accept/reject) | ✅ | ✅ | ✅ §9 | ✅ #12, 13, 36 | ✅ | ✅ | ✅ Complete |
| Snapshot-bound browser/SVG/vector PDF views | ✅ | ✅ | ✅ export boundary | ✅ #20, 23 | ✅ | ✅ | ✅ Supported human-readable views of the selected persisted snapshot |
| Mermaid/JSON architecture projections | ✅ | ✅ | ✅ export boundary | ✅ #23, 33 | ✅ | ✅ | ⚠️ Migration to the common snapshot-bound artifact envelope, authority headers, and loss manifest remains open in #966 |
| ArchiMate 3.1 export subset | ✅ | ✅ | ✅ export boundary | ✅ #23, 33 | ✅ | ✅ | ⚠️ Experimental bounded subset; mapping/loss profile and independent-tool interoperability remain open in #967 |
| Visio 2012 VSDX export subset | ✅ | ✅ | ✅ export boundary | ✅ #23, 33 | ✅ | ✅ | ⚠️ Experimental bounded subset; Microsoft Visio desktop certification and complete handoff/loss evidence remain open in #965 |
| Full-text search | ✅ | ✅ | ✅ §11a | ✅ #29 | ✅ | ✅ | ✅ Complete |
| Semantic/Hybrid search | ✅ | ✅ | ✅ §11b, §11c | ✅ #30, 31 | ✅ | ✅ | ✅ Complete |
| Graph exploration (upstream/downstream) | ✅ | ✅ | ✅ §8 | ✅ #11, 21, 37 | ✅ | ✅ | ✅ Complete |
| Failure impact analysis | ✅ | ✅ | ✅ §8 | ✅ #22 | ✅ | ✅ | ✅ Complete |
| Gap analysis | ✅ | ✅ | ✅ §11e | ✅ #26, 27 | ✅ | ✅ | ✅ Complete |
| Pattern detection | ✅ | ✅ | ✅ §11f | ✅ | ✅ | ✅ | ✅ Complete |
| Recommendations (Copilot) | ✅ | ✅ | ✅ §4 | ✅ | ✅ | ✅ | ✅ Complete |
| Reports (MD/HTML/DOCX) | ✅ | ✅ | ✅ §10a | ✅ #23 | ✅ | ✅ | ✅ Complete |
| Workspace management | ✅ | ✅ | ✅ §13 | ✅ #43–45 | ✅ | ✅ | ✅ Complete |
| Branch compare | ✅ | ✅ | ✅ §12 | ✅ #48 | ✅ | ✅ | ✅ Complete |
| Context transfer (copy back) | ✅ | ✅ | ✅ §12 | ✅ #49 | ✅ | ✅ | ✅ Complete |
| Variant creation | ✅ | ✅ | ✅ §12 | ✅ #46, 47 | ✅ | ✅ | ✅ Complete |
| Merge (with conflict resolution) | ✅ | ✅ | ✅ §12 | ✅ #52, 53, 58, 60, 61 | ✅ | ✅ | ✅ Complete |
| Cherry-pick (with conflict resolution) | ✅ | ✅ | ✅ §12 | ✅ #54, 59, 62 | ✅ | ✅ | ✅ Complete |
| Branch delete | ✅ | ✅ | ✅ §12 | ✅ #57 | ✅ | ✅ | ✅ Complete |
| DSL editor (syntax highlighting, autocompletion) | ✅ | ✅ | ✅ §11g | ✅ #34, 40 | ✅ | ✅ | ✅ Complete |
| Version history (commits) | ✅ | ✅ | ✅ §12 | ✅ #41, 66, 67, 68 | ✅ | ✅ | ✅ Complete |
| Sync from shared / Publish | ✅ | ✅ | ✅ §12 | ✅ #55, 56, 63–65 | ✅ | ✅ | ✅ Complete |
| Leaf justification | ✅ | ✅ | ✅ §6 | ✅ #18 | ✅ | ✅ | ✅ Complete |
| Document import (PDF/DOCX) | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Complete |
| Source provenance tracking | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Complete |
| AI-assisted requirement extraction | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Complete |
| Regulation-to-architecture mapping | ✅ | ✅ | ✅ DOCUMENT_IMPORT | — | ✅ | ✅ | ✅ Complete |

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
| **ArchiMate 3.1** | **Experimental bounded ArchiMate 3.1 subset** | Representative output is validated offline against the pinned ArchiMate 3.1 XSD set; the workbench download is bound to the selected snapshot | **Independent-tool interoperability is not certified**; a versioned mapping and loss profile, stable external identity/property preservation, and a semantic round trip remain pending |
| **Visio 2012 VSDX** | **Experimental bounded Visio 2012 subset** | Deterministic OPC/VSDX package structure, relationship and content-type checks, masterless connector geometry, XMLBeans validation, and Apache POI technical-reader loading; the workbench download is bound to the selected snapshot | **Microsoft Visio desktop certification pending**: open, edit, save, and reopen behavior in a documented Microsoft Visio desktop version has not been certified; a complete handoff and loss manifest is also pending |

### Required product wording

Use these descriptions consistently:

- **Experimental bounded ArchiMate 3.1 subset — mapping and loss manifest pending.**
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
| User management (CRUD) | ✅ | ✅ | ✅ Complete |
| LLM diagnostics | ✅ | ✅ | ✅ Complete |
| Embedding status | ✅ | ✅ | ✅ Complete |
| Startup status | ✅ | ✅ | ✅ Complete |
| Workspace eviction (admin) | ✅ | ✅ | ✅ Complete |

## Legend

| Symbol | Meaning |
|---|---|
| ✅ | Fully implemented and documented |
| ⚠️ | Partially done — needs verification or completion |
| 🔴 | Significant gap — GUI may exist but docs/help/screenshot missing, or REST-only |
| ❓ | Unknown — needs audit |

## Screenshot Index

Screenshots are auto-generated by `ScreenshotGeneratorIT` and stored in `docs/images/`.
Reference format: `#NN` = `docs/images/NN-*.png`. User Guide section references: `§N` = USER_GUIDE.md section N.
