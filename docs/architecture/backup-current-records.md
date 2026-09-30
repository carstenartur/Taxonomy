# Current records and embedded history

This document describes the first P05 adapters. They are not yet a complete
capture source: knowledge, source files, identities, templates, architecture,
integration and Git inventory are required before production jobs can activate.

Each module constructs explicit version-1 records. An NDJSON dataset starts with
`schemaVersion`, `kind` and `profile`, followed by one record per line. Jackson
receives these records, never persistence entities or polymorphic archive types.
Namespaced `SourceRecordId` values identify source rows and their foreign
references; a restore must map them and must not insert source database keys.
Business keys, repository/workspace identity and branch remain explicit.
Instants use UTC ISO-8601, decimal amounts use plain decimal strings, JSON null
means absent, and the editor's `1:` storage envelope is removed. The envelope
continues to preserve an empty editor document even on Oracle.

`PortableRows` belongs to the framework-neutral `com.taxonomy.exchange` library
in `taxonomy-export`. It reads a bounded row at a time on the capture thread,
keeps JDBC resources open while the sink consumes them, checks interruption,
and closes the read transaction on success or failure. Limits are 16 MiB per
encoded record, 8 Mi characters per text value and one million scanned rows per
dataset. Repository predicates accept at most 200 selected logical repositories;
larger installation captures use installation scope. Component/capture/archive
limits apply in addition. A limit failure discards the capture, never a truncated
successful dataset.

Workspace owns exact repository/workspace and encoded-tenant predicates.
Selecting a central repository does not select any private workspace. Analysis
and portfolio use this public scope reader; analysis also uses the existing
workspace tenant value type. These three forward dependency edges are recorded
in the architecture baseline. The cycle rule is unchanged.

| Profile | Workspace, analysis and portfolio payload policy |
| --- | --- |
| Current state / installation current | Current editor source, current requirement version, its active analysis and decisions, current saved draft, relevant unfinished work; no inverse journal bodies or earlier versions |
| Selected version | Present-day unversioned rows excluded; selected immutable Git content is authoritative, with unavailable non-versioned categories declared in the manifest |
| Repository history / installation full | Authorized retained versions and journal records; full source pointers retained even when an analysis is older than the active text version |

Draft results are included in a stand only when `lastAnalyzedText` equals its
current `businessText`. Unknown draft history fields and obsolete stored text
are excluded. Continuation closure retains only hashes of current draft text in
memory. Worker claims never enter the schema; continuation execution requires
review after restore.

Portfolio publishes 25 explicit datasets, including requirements, analysis
snapshots and decisions, solution/product decisions and reformulation work.
Current snapshots must belong to the current requirement version; obsolete
snapshot pointers become absent in stand exports. Full history keeps the
original pointers. Cross-tenant project/solution/product/requirement links and
missing current versions fail dependency verification before record output.
Reformulation adoption ancestry, former originals and change reasons require a
history profile. Current reformulation drafts retain their active revision;
frozen inherited history is excluded and cannot authorize resumed execution.

A current Git tree can itself contain historical `requirementVersion` and
`reformulationEvidence` blocks. `PortfolioStandDocument` therefore selects the
explicit `currentVersionNumber`, removes previous versions and adoption
ancestry, and removes the original-text/change-reason fields. Ambiguous legacy
selectors, duplicate properties and unsupported syntax fail closed instead of
silently discarding content. This projection changes only exported stand
documents; the existing collaboration/history export remains unchanged.

Tests use real HSQL/Hibernate state with two repositories, private workspaces,
identical business keys, stale analyses, deleted text, drafts and decisions.
They verify authorized current and history contents, selected-version exclusion,
dependency failures and UTC conversion for both zoned and unzoned columns.
This evidence is not a substitute for the later four-database restore matrix or
the complete Git/file/browser acceptance gates.
