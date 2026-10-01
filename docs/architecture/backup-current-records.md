# Current records and embedded history

This document describes the P05 adapters. They are not yet a complete capture
source: preferences/configuration, integration,
retained catalogue inputs and Git inventory are required before production jobs
can activate. The knowledge adapter is described in `backup-data-ownership.md`.

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

The projection recognizes canonical `projectRequirement` blocks as well as the
legacy two-key `requirement` form, without treating one-key architecture
requirements as portfolio parents. A regression exercises the real portfolio
exporter and verifies history removal and source immutability.

The existing collaboration `materialize` operation is not a backup restore
implementation: importing a lone selected version can create local version 1
even when its source number is higher. P08 must preserve or explicitly map
source version identities and references. No restore roundtrip is claimed here.

Tests use real HSQL/Hibernate state with two repositories, private workspaces,
identical business keys, stale analyses, deleted text, drafts and decisions.
They verify authorized current and history contents, selected-version exclusion,
dependency failures and UTC conversion for both zoned and unzoned columns.
This evidence is not a substitute for the later four-database restore matrix or
the complete Git/file/browser acceptance gates.

Source capture uses the explicit artifact/version/fragment references of the
selected requirement versions. A legacy source link has only a business key,
without repository identity, so it cannot grant access in a scoped export.
Installation exports retain such links in a separate legacy namespace; restore
must not bind them to whichever tenant happens to use the same requirement key.
Current installation capture retains latest source versions plus current
business dependencies. Historical versions and their legacy links require a
history profile. Fragment parent chains must be complete, acyclic and belong to
the same version; artifact/version/fragment contradictions fail before output.

Retained originals and extracted text stream from a configured, operator-owned
content root into independently hashed entries. Paths never enter portable
records. An original's recorded SHA-256 must match the streamed bytes. Missing,
changed, non-regular or escaping files fail capture. Secure directory handles
prevent symlink traversal where the provider supports them. Other providers
check canonical paths and file identities; the configured root must remain
stable and private throughout the maintenance window. These checks have local
Linux evidence; native Windows acceptance remains part of P12.

A source whose upload was never retained is explicitly `NOT_RETAINED`; its
metadata/fragments are preserved, with no fabricated original. Files are written
before their referencing DTOs, so no nested entry can bypass archive entry
limits. SQL batches use at most 200 dependency IDs; the closure is capped at
100,000 identifiers per category. File bytes and DTO text are streamed, not held
in the dependency map. Selected-version source closure must be supplied from
selected Git evidence during capture composition, not from today's database.

## Administrative document templates

The template library owns `TemplateBackupContributor` and the read-only capture
port on `DocumentTemplateGitRepository`. `INSTALLATION_CURRENT` captures one
immutable main-branch tree, including every manifest and OOXML/binary part. It
does not call bootstrap, materialize a worktree or write source refs. Advancing
the source HEAD after capture cannot change the selected blob identities.

The global template repository is administered separately from user workspaces.
Workspace and selected-version exports record `OUTSIDE_SCOPE` without reading
its HEAD or content. The current-tree adapter rejects `INSTALLATION_FULL` until
auxiliary Git-history capture is composed; it cannot claim complete history.

Before payload output, capture verifies the whole tree layout, regular file
modes, portable Linux/Windows paths and directory case collisions. Each template
manifest must be bounded, unambiguous JSON and match its package's part count,
byte length and SHA-256. Source paths are retained below
`data/templates/current/`; `data/templates/inventory.json` links immutable source
object IDs to the independently hashed archive entries. No earlier package
content enters a current-state capture.

Only bounded path/object metadata is retained for the tree. Payload checksums
and export bytes stream with an 8 KiB verification buffer. Limits match template
ingestion: 2,048 parts, 25 MiB per part and 100 MiB per package, plus a 1 MiB
manifest limit and the capture-wide limits. Contributors call
`ComponentSink.checkpoint()` during discovery so cancellation, runtime budgets
and the maintenance lease remain active before the first output entry.

Real in-memory Git regressions verify binary preservation, source immutability,
history exclusion, out-of-scope non-access, empty repositories and rejection of
corrupt packages, ambiguous manifests, symlinks and unsafe paths. The shared
capture test checks that cancellation during preflight releases the barrier and
leaves no completed staging artifact. Native Windows checkout remains P12 work.

## Parsed architecture documents

`ArchitectureDslDocument` predates repository/workspace ownership columns.
Its branch and path cannot authorize a scoped read. The architecture adapter
therefore accepts `BackupDocumentReference` evidence from the captured Git
inventory: exact repository/workspace, commit, path and content SHA-256. The
repository must belong to the capture; current evidence must identify a branch
head, selected-version evidence the requested commit, and history evidence a
captured ref or required commit. The composition must establish reachability
and omit committed content superseded by a saved working copy.

Only matching payloads are exported as `architecture.document` records, with
their captured Git references. Current/selected payloads also pass the shared
stand projector to remove embedded portfolio history. Repeated materializations
of the same proven content collapse to the latest source record. Global branch,
namespace and parse-event metadata are not evidence of ownership and do not
enter scoped records. Restore can derive DSL metadata from the selected content.

`INSTALLATION_FULL` preserves every original row and its metadata as
`architecture.legacy-document`, explicitly `INSTALLATION_ONLY`, without assigning
it to a guessed repository. Other profiles declare unattributed legacy records
unavailable. This is a conservative adapter boundary, not an ownership migration:
uncommitted legacy imports without provable scope still need classification
before a current capture can claim complete coverage. Git inventory composition
and the complete application capture source remain unfinished.

The real HSQL/Hibernate regressions cover identical paths across workspaces,
false commit attribution with different bytes, selected commits, history,
installation legacy preservation, embedded-history removal and source immutability.

## Identity records and protected local credentials

`IdentityBackupContributor` supplies the application component's identities,
provider bindings, local accounts, roles, joins, backup grants and access audit.
Application composition must combine it with the source-content and settings
adapters under one `application` component; production capture is still disabled.

Scoped exports contain only the requesting principal and principal IDs supplied
by the authorized record closure. Missing identities fail before dataset output.
Scoped identity metadata and provider tuples support explicit target mapping;
local accounts, roles and administrative grants require installation scope.
Historical access audit requires `INSTALLATION_FULL`. Source grants describe the
source installation and never authorize target access automatically.

Accounts created since identity migration may not have logged in yet. Their
stable account ID and metadata remain portable as `PENDING_LOCAL_ACCOUNT`, with
no invented ownership scope or provider binding. Capture performs no registration
or source writes. Registered historical principals remain distinct from local
accounts that happen to have the same display name.

Normal identity files live below `identities/`. Password hashes are queried and
written only for the authorized `INCLUDE_ENCRYPTED` full-installation selection,
under `protected/identities/password-hashes.ndjson`. The existing coordinator
requires encrypted staging for that selection, and the archive writer enforces
authenticated protection. Sessions, bearer tokens and live authority are absent.

Real HSQL/Hibernate and identity-migration tests cover scoped exclusion, missing
references, installation current versus full audit, pending accounts, protected
hash separation, broken audit actors and source immutability. The protected-entry
test verifies routing; archive encryption is covered by the separate archive
tests, not claimed from inspecting plaintext DTO output.
