# Data quality report for the C3 Taxonomy Catalogue dated 25 August 2025

## Purpose and scope

This report explains the structural, ordering, text-quality and traceability
problems deterministically detected in
`taxonomy-app/src/main/resources/data/C3_Taxonomy_Catalogue_25AUG2025.xlsx`.
It applies to exactly this workbook:

- workbook SHA-256: `6b19743eff1487a76ea3e5b788d90831ba1705da31790cf58f2d69a979b14130`
- audit date: 28 August 2026
- worksheets: 8
- data rows excluding headers: 2,564

The complete machine-readable finding list is attached as
[`C3_Taxonomy_Catalogue_25AUG2025_audit.csv`](https://github.com/carstenartur/Taxonomy/blob/main/docs/data/C3_Taxonomy_Catalogue_25AUG2025_audit.csv).
For every finding it records severity, worksheet, Excel row, node code,
affected field, current value and, where applicable, related codes. This report
explains each category and its recommended correction once instead of repeating
that prose in every CSV row.

> **Boundary:** The report is complete for the deterministic rules described
> below. It cannot prove that every domain definition, NATO reference or
> semantic classification is correct. Those questions require expert review.

## Executive result

The audit produced 1,204 findings. One node or cell can produce more than one
finding, for example an unresolved parent reference and the resulting level
mismatch.

| Severity | Count | Meaning |
|---|---:|---|
| Error | 943 | The hierarchy is structurally invalid or the stored content is objectively corrupted or misspelled. |
| Warning | 226 | The value is readable but has a quality, presentation or disambiguation defect. |
| Review | 35 | The value is not necessarily wrong, but needs a documented domain or governance decision. |
| **Total** | **1,204** | |

### Findings by worksheet

| Worksheet | Data rows | Errors | Warnings | Reviews | Total findings |
|---|---:|---:|---:|---:|---:|
| Business Processes | 408 | 0 | 45 | 5 | 50 |
| Business Roles | 306 | 2 | 22 | 5 | 29 |
| Capabilities | 33 | 0 | 1 | 4 | 5 |
| COI Services | 130 | 0 | 19 | 4 | 23 |
| Communications Services | 91 | 0 | 6 | 4 | 10 |
| Core Services | 150 | 0 | 21 | 4 | 25 |
| Information Products | 1,071 | 941 | 60 | 5 | 1,006 |
| User Applications | 375 | 0 | 52 | 4 | 56 |
| **Total** | **2,564** | **943** | **226** | **35** | **1,204** |

### Finding categories

| Category | Count | Severity |
|---|---:|---|
| Unassigned Information Products at root level | 853 | Error |
| Repeated whitespace | 130 | Warning |
| Duplicate sibling sort order | 93 | Warning |
| Confirmed typographical errors | 50 | Error |
| Traceability gaps | 32 | Review |
| Broken character encoding | 16 | Error |
| Dangling parent references | 8 | Error |
| Declared level mismatch | 8 | Error |
| Implausible level 14 | 5 | Error |
| Duplicate titles in different branches | 3 | Warning |
| Worksheets containing unapproved drafts | 3 | Review |
| Duplicate sibling title | 1 | Error |
| Self-parent cycle | 1 | Error |
| Unexpected additional root node | 1 | Error |

## 1. Hierarchy errors

### 1.1 853 unassigned Information Products

The **Information Products** worksheet already contains the approved domain
root `IP-1000`. Nevertheless, 853 concrete Information Products marked as
`draft` have no `Parent` and are declared at level 1. The importer consequently
places them directly below the virtual `IP` root.

This is not merely a visual defect. It bypasses the existing domain hierarchy,
forces hundreds of candidates into one analysis step, removes the explanatory
path from product family to product, and contributes to analysis timeouts.

**Required correction:** Assign every valid concrete product one reviewed
primary parent in the approved Information Products hierarchy. Preserve other
plausible classifications as secondary classifications or relations rather
than duplicating nodes. The CSV appendix lists all 853 codes and rows.

### 1.2 Eight references to missing parents

| Excel row | Node | Title | Missing parent |
|---:|---|---|---|
| 67 | `IP-1018` | Asset State Reports | `IP-1008` |
| 144 | `IP-1021` | Capability Reports | `IP-1008` |
| 274 | `IP-1036` | Enduring Plans | `IP-1003` |
| 567 | `IP-1067` | Location Reports | `IP-1008` |
| 792 | `IP-1080` | Organizational Plans | `IP-1003` |
| 882 | `IP-1086` | Resource Reports | `IP-1008` |
| 947 | `IP-1088` | Situation Reports | `IP-1008` |
| 1033 | `IP-1096` | Time-Limited Plans | `IP-1003` |

A root fallback keeps an importer running but hides the defect and produces a
wrong hierarchy.

**Required correction:** Determine the intended parent, correct the code and
recalculate the level from the validated parent chain. Maintained catalogue
data must not silently replace unresolved parents with a root.

### 1.3 Self-parent cycle at `IP-2065`

Excel row 370 makes `IP-2065` **Geospatial Support Requests** its own parent.
Four other Geospatial Support nodes then point to `IP-2065`.

**Required correction:** Assign `IP-2065` to the reviewed product family and
then validate its four dependent nodes.

### 1.4 Five nodes declared at level 14

| Excel row | Node | Title | Parent |
|---:|---|---|---|
| 366 | `IP-2130` | Geospatial Support Alerts | `IP-2065` |
| 367 | `IP-2131` | Geospatial Support Analyses | `IP-2065` |
| 368 | `IP-2084` | Geospatial Support Plans | `IP-2065` |
| 369 | `IP-2099` | Geospatial Support Reports | `IP-2065` |
| 370 | `IP-2065` | Geospatial Support Requests | `IP-2065` |

The value is part of the same invalid cyclic subtree and cannot represent a
valid catalogue depth. Parent relationships must be corrected first; levels
should then be derived rather than maintained as an independent truth.

### 1.5 Eight consequential level mismatches

The eight nodes with missing parents declare level 4, but the resolvable graph
cannot establish that depth. These are consequences of the parent errors in
section 1.2, not eight additional root causes.

### 1.6 Unexpected Business Roles root

`BR-1220` **Capability Sustainment Manager** has no parent and is declared at
level 1 although `BR-1000` is already the worksheet root.

**Required correction:** Assign it to the intended role family below `BR-1000`
or explicitly document why the worksheet should contain multiple roots.

## 2. Duplicate or ambiguous names

### 2.1 Duplicate sibling title

Below parent `BR-1175`, nodes `BR-1201` and `BR-1221` are both named
**Stakeholder Communication Manager**. The UI, search, reports and semantic
classification cannot reliably distinguish them by name and path.

**Required correction:** Merge true duplicates or provide precise distinct
names and descriptions.

### 2.2 Same title in different branches

- `BR-1061` and `BR-1290`: **Information Management Roles**
- `BR-1084` and `BR-1266`: **Medical Support Roles**
- `BR-1187` and `BR-1247`: **Risk Assessment Roles**

This may be intentional, but title-only search and reports remain ambiguous.
Qualify the names or consistently display and index the complete hierarchy
path.

## 3. Non-unique ordering

Ninety-three sibling groups contain duplicate `Order` values:

| Worksheet | Affected sibling groups |
|---|---:|
| Business Processes | 13 |
| Business Roles | 9 |
| Capabilities | 1 |
| COI Services | 10 |
| Communications Services | 1 |
| Core Services | 16 |
| Information Products | 6 |
| User Applications | 37 |

A duplicate order value does not prevent parsing, but it fails to define one
canonical presentation order. Assign unique sibling values or specify a
mandatory secondary ordering rule. The CSV appendix lists all 93 groups.

## 4. Text and encoding defects

### 4.1 Broken character encoding

Sixteen Information Product descriptions contain mojibake such as `â€œ`, `â€`,
`â€“`, `â€”`, `â€¢` and `â€˜` in place of quotation marks, dashes, bullets and
apostrophes. Examples include `IP-1475`, `IP-1285`, `IP-1314`, `IP-1643`,
`IP-1321`, `IP-1264`, `IP-1904`, `IP-1296`, `IP-1627` and `IP-1778`.

This visibly corrupts text and degrades full-text search, embeddings, reports
and LLM input. Recover the intended encoding and review each affected row.

### 4.2 Repeated whitespace

One hundred and thirty description, source or reference cells contain repeated
horizontal spaces. Normalize horizontal whitespace while preserving intentional
paragraphs and lists.

### 4.3 Fifty confirmed typographical errors

| Current value | Count | Expected correction |
|---|---:|---|
| `infomration` | 23 | `information` |
| `andfunctions` | 20 | `and functions` |
| `adress` | 2 | `address` |
| `capabilites` | 1 | `capabilities` |
| `maintaing` | 1 | `maintaining` |
| `responsibiilty` | 1 | `responsibility` |
| `Comunications` | 1 | `Communications` |
| `Troup Contributing` | 1 | expert review; probably `Troop Contributing` |

The audit deliberately excludes grammar or style concerns that cannot be
proven mechanically.

## 5. Approval and traceability gaps

### 5.1 886 draft nodes

| Worksheet | Draft nodes |
|---|---:|
| Business Processes | 2 |
| Business Roles | 18 |
| Information Products | 866 |
| **Total** | **886** |

`draft` is not a parse error, but it means a large part of the embedded
catalogue is not represented as a stable approved baseline. Each draft needs an
approval, rejection, replacement or an accountable decision to retain it with
a review date.

### 5.2 Missing provenance fields

The audit checks `Dataset`, `External ID`, `Source` and `Reference` for each of
the eight worksheets, producing 32 aggregate review findings.

Notably:

- `Dataset` is empty in all 2,564 rows.
- `External ID` is empty in all 2,564 rows.
- `Source` and `Reference` are empty in all 1,071 Information Product rows.

These fields may be optional in the upstream export, but without a documented
rule the authoritative standard, source or external identity cannot be traced.
Populate them or document the alternative provenance contract.

## 6. Checks with no finding

The audit also confirmed:

- no duplicate node code anywhere in the workbook;
- no duplicate UUID;
- no syntactically invalid UUID;
- no non-numeric `Order` or `Level` value;
- no parent reference across worksheet boundaries;
- no row missing code, title or description;
- no approved child below a draft parent.

These null findings do not prove that all domain content is correct; they only
exclude the stated formal defect types.

## 7. Recommended correction order

1. Repair cycles and missing parents.
2. Classify the 853 flat Information Products with primary parent, confidence,
   rationale and optional secondary classifications.
3. Derive levels from the validated graph.
4. Resolve the additional Business Roles root and duplicate titles.
5. Make sibling ordering deterministic.
6. Repair encoding, whitespace and confirmed typos.
7. Record approval and provenance decisions.
8. Re-run the complete audit after every catalogue change and version the
   finding delta.

## 8. Implications for Taxonomy

Taxonomy should retain the external workbook as a traceable baseline without
silently accepting its defects as valid hierarchy. Unknown parents,
self-parenting and cycles should be visible and rejected in maintained
catalogue data.

Corrections and domain extensions should be versioned separately from the
upstream workbook, preserving both the original source and every local change
for review, comparison and rollback.

## Complete finding list

The CSV appendix contains:

- `finding_id`
- `severity`
- `category`
- `sheet`
- `excel_row`
- `code`
- `field`
- `current_value`
- `related_codes`

The domain explanation and recommended correction for each category are
recorded in sections 1 through 5.

Appendix SHA-256:
`3691af78ac1511a17836cec3af234aaf59a8b8d693a9e7453fdd3476046c4c5c`.
