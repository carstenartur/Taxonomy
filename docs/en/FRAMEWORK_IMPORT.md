# Framework Import

The Framework Import pipeline lets you ingest architecture models from external frameworks into the Taxonomy Architecture Analyzer. Imported elements and relations are converted to the canonical taxonomy data model and stored as Architecture DSL documents with full version control.

## Table of Contents

- [Overview](#overview)
- [Supported Frameworks](#supported-frameworks)
- [Import Workflow](#import-workflow)
- [UAF / DoDAF Import](#uaf--dodaf-import)
- [APQC Process Classification Framework](#apqc-process-classification-framework)
- [C4 / Structurizr Import](#c4--structurizr-import)
- [Mapping Profiles](#mapping-profiles)
- [Preview Mode](#preview-mode)
- [REST API Endpoints](#rest-api-endpoints)
- [Troubleshooting](#troubleshooting)
- [Related Documentation](#related-documentation)

---

## Overview

The import pipeline transforms external architecture artifacts into the taxonomy's internal representation through a multi-stage process:

```
File upload  →  ExternalParser  →  ExternalModelMapper (profile)
            →  CanonicalArchitectureModel  →  DslSerializer
            →  DslMaterializeService  →  Database (TaxonomyRelation / RelationHypothesis)
```

Each supported framework has a **parser** that reads its native file format and a **mapping profile** that maps external element types and relation types to taxonomy root codes and relation types.

---

## Supported Frameworks

| Profile ID | Framework | File Format | Parser |
|---|---|---|---|
| `uaf` | UAF / DoDAF (XMI) | XML | `UafXmlParser` |
| `apqc` | APQC PCF | CSV | `ApqcCsvParser` |
| `apqc-excel` | APQC PCF | XLSX | `ApqcExcelParser` |
| `c4` | C4 / Structurizr | DSL | `StructurizrDslParser` |

Use `GET /api/import/profiles` to list all available profiles at runtime.

---

## Import Workflow

1. **Select a profile** — Choose the framework that matches your source file.
2. **Preview** — Upload the file to the preview endpoint to see statistics (element count, relation count, mapped types) without writing to the database.
3. **Import** — Confirm and run the full import. The pipeline parses the file, maps elements and relations, serialises them to DSL, and materialises the result into the database.
4. **Review** — Imported relations appear in the Graph Explorer, Relation Proposals panel, and Architecture View. Imported elements carry an `x-source-framework` extension attribute for traceability.

---

## UAF / DoDAF Import

**File format:** XMI / XML

The UAF parser reads `<packagedElement>` and `<ownedElement>` tags from XMI exports. Relations are derived from `<ownedConnector>` and `<connector>` tags with `source` and `target` attributes.

### Element Type Mapping

| UAF Type | Taxonomy Root Code |
|---|---|
| Capability | CP (Capability) |
| OperationalActivity | BP (Business Process) |
| ServiceFunction | CR (Capability Requirement) |
| CapabilityConfiguration | CI (Configuration Item) |
| CommunicationsFunction | CO (Communications Service) |
| System, Platform | UA (User Application) |
| Performer, Organization, ResourcePerformer | BR (Business Role) |
| InformationElement | IP (Information Product) |

### Relation Type Mapping

| UAF Relation | Taxonomy Relation |
|---|---|
| Implements | REALIZES |
| Supports | SUPPORTS |
| Consumes | CONSUMES |
| Uses | USES |
| Provides | FULFILLS |
| IsAssignedTo | ASSIGNED_TO |
| DependsOn | DEPENDS_ON |
| Produces | PRODUCES |
| CommunicatesWith | COMMUNICATES_WITH |

**Security:** The XML parser has XXE (XML External Entity) protection enabled to prevent injection attacks.

---

## APQC Process Classification Framework

**File formats:** CSV or XLSX (Excel)

The APQC parser reads the Process Classification Framework hierarchy. Each row represents one process element at a specific level. Parent-child relations are derived automatically from the PCF ID hierarchy (e.g. `1.1` is a child of `1.0`).

### Expected Columns

| Column | Description |
|---|---|
| PCF ID | Unique identifier (e.g. `1.0`, `1.1`, `1.1.1`) |
| Name | Process name |
| Level | Hierarchy depth (1–5) |
| Description | Optional process description |

Column detection is flexible — the parser matches headers by keyword.

### Level Mapping

| APQC Level | Taxonomy Root Code |
|---|---|
| Level 1 (Category) | CP (Capability) |
| Level 2 (Process Group) | BP (Business Process) |
| Level 3 (Process) | CR (Capability Requirement) |
| Level 4 (Activity) | CI (Configuration Item) |
| Level 5 (Task) | BR (Business Role) |

### Relation Type Mapping

| APQC Relation | Taxonomy Relation |
|---|---|
| ParentChild | RELATED_TO |
| Enables | SUPPORTS |
| Consumes | CONSUMES |
| Produces | PRODUCES |

---

## C4 / Structurizr Import

**File format:** Structurizr DSL (`.dsl`)

The Structurizr parser uses regex-based extraction for element and relation definitions.

### Element Syntax

```
identifier = elementType "name" ["description"] ["technology"]
```

### Supported Element Types

| C4 Type | Taxonomy Root Code |
|---|---|
| Person | BR (Business Role) |
| SoftwareSystem | SY (System) |
| Container | UA (User Application) |
| Component | CM (Component) |
| DeploymentNode | CO (Communications Service) |
| InfrastructureNode | CO (Communications Service) |
| ContainerInstance | UA (User Application) |

### Relation Syntax

```
source -> target "description" ["technology"]
```

Container nesting is tracked automatically and produces `CONTAINS` relations. Relation types are inferred from description keywords (e.g. "depends" → `DEPENDS_ON`, "delivers" → `FULFILLS`).

### Relation Type Mapping

| C4 Relation | Taxonomy Relation |
|---|---|
| Uses | USES |
| Delivers | FULFILLS |
| InteractsWith | COMMUNICATES_WITH |
| DependsOn | DEPENDS_ON |
| Contains | CONTAINS |
| Realizes | REALIZES |
| Supports | SUPPORTS |

---

## Mapping Profiles

Each framework is backed by a **mapping profile** that defines:

- `profileId()` — Unique identifier used in API calls
- `displayName()` — Human-readable label
- `mapElementType(externalType)` — Converts an external element type to a taxonomy root code
- `mapRelationType(externalRelType)` — Converts an external relation type to a canonical `RelationType`
- `supportedElementTypes()` — Lists all external element types the profile can handle
- `supportedRelationTypes()` — Lists all external relation types the profile can handle

An **ArchiMate mapping profile** is also available in the codebase for future ArchiMate model import, mapping 10 ArchiMate element types and 11 relation types.

---

## Preview Mode

Preview mode (`POST /api/import/preview/{profileId}`) runs the full parse-and-map pipeline but **does not write to the database**. The response includes:

- Number of elements parsed
- Number of relations parsed
- Mapped element types with counts
- Mapped relation types with counts
- Warnings for unmapped types

Use preview to validate your file before committing to a full import.

---

## REST API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/import/profiles` | List all available import profiles |
| `POST` | `/api/import/preview/{profileId}` | Preview import result (dry run, no database write) |
| `POST` | `/api/import/{profileId}` | Run full import into database |

The import endpoint accepts a `branch` query parameter to specify the DSL branch for the import (defaults to the active branch).

All endpoints require authentication. See [API Reference](API_REFERENCE.md) for full request/response schemas.

---

## Troubleshooting

| Problem | Cause | Solution |
|---|---|---|
| "Unknown profile" error | Profile ID not recognised | Check `GET /api/import/profiles` for valid IDs |
| Empty preview result | File format mismatch | Ensure the file matches the expected format for the profile |
| Missing elements after import | Unmapped external types | Check preview warnings for unmapped type names |
| XML parse error | Malformed XMI | Validate XML structure; check for encoding issues |
| CSV parse error | Wrong delimiter or encoding | Use comma-separated values with UTF-8 encoding |

---

## Related Documentation

- [User Guide](USER_GUIDE.md) — End-to-end usage instructions
- [Concepts](CONCEPTS.md) — Taxonomy node types, relation types, and the canonical model
- [Architecture](ARCHITECTURE.md) — System architecture and module structure
- [API Reference](API_REFERENCE.md) — Full REST API documentation
- [Git Integration](GIT_INTEGRATION.md) — How imported DSL documents are versioned
