# Relation Seed Model

## Overview

The Taxonomy Architecture Analyzer uses a **relation seed CSV file** to define
default architectural relations between taxonomy element types. These seeds are
loaded at application startup when no Relations sheet is present in the Excel
workbook.

The seed file lives at `src/main/resources/data/relations.csv` inside the
`taxonomy-app` module.

---

## CSV Format

The CSV uses a 10-column format with a mandatory header row:

```
SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType,ReviewRequired,Status
```

| Column | Required | Default | Description |
|---|---|---|---|
| **SourceCode** | yes | — | Taxonomy root code of the source element (e.g. `CP`, `CR`, `BP`) |
| **TargetCode** | yes | — | Taxonomy root code of the target element |
| **RelationType** | yes | — | One of the `RelationType` enum values (e.g. `REALIZES`, `SUPPORTS`) |
| **Description** | no | `null` | Human-readable explanation of why this relation exists |
| **SourceStandard** | no | `null` | The framework or standard that justifies this relation (e.g. `TOGAF`, `NAF`, `APQC`, `FIM`, `LOCAL`) |
| **SourceReference** | no | `null` | Specific reference within the standard (e.g. `NCV-2`, `Business Architecture`) |
| **Confidence** | no | `1.0` | Numeric confidence value between 0.0 and 1.0 |
| **SeedType** | no | `TYPE_DEFAULT` | Classification: `TYPE_DEFAULT`, `FRAMEWORK_SEED`, or `SOURCE_DERIVED` |
| **ReviewRequired** | no | `false` | Whether human review of this relation is recommended |
| **Status** | no | `accepted` | Current status: `accepted` or `proposed` |

### Backward Compatibility

The parser (`RelationSeedParser`) supports legacy 3- or 4-column CSV files.
Missing columns receive their default values. This ensures older CSV files
continue to work without modification.

---

## Seed Types

| Seed Type | Meaning | Provenance Prefix |
|---|---|---|
| **TYPE_DEFAULT** | Structural relations that are always expected between taxonomy types (e.g. CP → CR REALIZES). These form the foundational architecture model. | `csv-default` |
| **FRAMEWORK_SEED** | Relations derived from a specific architecture framework such as TOGAF, NAF, APQC, or FIM. These extend the default model with framework-specific knowledge. | `csv-framework` |
| **SOURCE_DERIVED** | Relations inferred from a regulatory document, industry standard, or reference source rather than a framework. | `csv-source-derived` |

### Provenance Encoding

When seed relations are loaded into the database, their provenance field is set
to `{prefix}:{SourceStandard}` — for example `csv-default:NAF` or
`csv-framework:TOGAF`. If no SourceStandard is specified, only the prefix is
used (e.g. `csv-source-derived`).

---

## Source Standards

| Standard | Description |
|---|---|
| **NAF** | NATO Architecture Framework — military/government architecture standard |
| **TOGAF** | The Open Group Architecture Framework — enterprise architecture standard |
| **APQC** | American Productivity & Quality Center — process classification framework |
| **FIM** | Federal Information Model (German: Föderales Informationsmanagement) |
| **LOCAL** | Project-specific or domain-specific relation, not derived from a published standard |

---

## Adding New Seed Relations

When adding new relations to the CSV file, follow these guidelines:

1. **Verify semantic correctness.** Each relation must represent a real
   architectural dependency supported by the taxonomy element types.

2. **Classify properly.** Use `TYPE_DEFAULT` only for universally expected
   relations. Use `FRAMEWORK_SEED` for framework-derived relations. Use
   `SOURCE_DERIVED` only when the relation comes from a specific document.

3. **Assign confidence.** Use `1.0` for well-established relations and lower
   values (e.g. `0.8`–`0.9`) for relations that are reasonable but may not
   apply in all contexts.

4. **Mark review-required.** Set `ReviewRequired` to `true` for any relation
   whose semantic fit is debatable or whose impact on propagation should be
   validated by a domain expert.

5. **Do not collapse relations.** Avoid using `RELATED_TO` as a catch-all.
   Each relation should use the most specific `RelationType` that applies.

6. **Document rationale.** The Description column should explain *why* the
   relation exists, not just restate the type name.

### Example: Adding a Public-Sector Regulation Seed

```csv
CP,IP,REQUIRES,Capability CP-1023 requires IP-2001 per BSI IT-Grundschutz APP.1.1,BSI,IT-Grundschutz APP.1.1,0.85,SOURCE_DERIVED,true,proposed
```

---

## Relation Types

The system defines 12 relation types. See
[CONCEPTS.md — Relation Type](CONCEPTS.md#relation-type) for the full table.

---

## Implementation

| Class | Module | Role |
|---|---|---|
| `RelationSeedParser` | taxonomy-app | Parses and validates the CSV file |
| `RelationSeedRow` | taxonomy-domain | Immutable record representing one parsed CSV row |
| `SeedType` | taxonomy-domain | Enum classifying seed provenance |
| `TaxonomyService.loadRelationsFromCsv()` | taxonomy-app | Loads parsed seeds into the database at startup |
| `RelationCompatibilityMatrix` | taxonomy-app | Validates source/target type compatibility |
