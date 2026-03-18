# Feature Completeness Matrix

This matrix tracks the delivery status of all major product features.
A feature is only complete when all required columns show ✅.

> See [Definition of Done](DEVELOPER_GUIDE.md#definition-of-done--user-facing-features)
> for the product rules.

## End-User Features (GUI-first)

| Feature | GUI | REST | User Guide | Screenshot | Help/Tooltip | DE/EN i18n | Status |
|---|:---:|:---:|:---:|:---:|:---:|:---:|---|
| Requirement analysis | ✅ | ✅ | ✅ | ✅ | ✅ | 🔍 audit | ✅ Complete |
| Scored tree exploration | ✅ | ✅ | ✅ | ✅ | ✅ | 🔍 audit | ✅ Complete |
| View modes (6 modes) | ✅ | ✅ | ✅ | ✅ | ❓ | 🔍 audit | ⚠️ Verify help |
| Architecture view | ✅ | ✅ | ✅ | ✅ | ❓ | 🔍 audit | ⚠️ Verify help |
| Relation proposals (accept/reject) | ✅ | ✅ | ✅ | ❓ | ❓ | 🔍 audit | ⚠️ Verify |
| Export (ArchiMate/Visio/Mermaid/JSON) | ✅ | ✅ | ✅ | ✅ | ✅ | 🔍 audit | ✅ Complete |
| Full-text search | ✅ | ✅ | ✅ | ❓ | ❓ | 🔍 audit | ⚠️ Verify |
| Semantic/Hybrid search | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Audit needed |
| Graph exploration (upstream/downstream) | ✅ | ✅ | ✅ | ❓ | ❓ | 🔍 audit | ⚠️ Verify |
| Failure impact analysis | ❓ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | 🔴 Audit needed |
| Gap analysis | ❓ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | 🔴 Audit needed |
| Pattern detection | ❓ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | 🔴 Audit needed |
| Recommendations | ❓ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | 🔴 Audit needed |
| Reports (MD/HTML/DOCX) | ❓ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | 🔴 Audit needed |
| Workspace management | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Branch compare | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Context transfer (copy back) | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Variant creation | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Merge (with conflict resolution) | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Cherry-pick (with conflict resolution) | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Branch delete | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| DSL editor | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Version history (commits) | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Sync from shared / Publish | ✅ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | ⚠️ Partial |
| Leaf justification | ❓ | ✅ | ❓ | ❓ | ❓ | 🔍 audit | 🔴 Audit needed |

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
| 🔍 | Needs i18n audit (both DE and EN labels present?) |
