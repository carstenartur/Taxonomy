# Current records and embedded history

This document describes the P05 adapters. They are not yet a complete capture
source: complete reference closure, Git inventory and production composition are required before production jobs
can activate. The knowledge adapter is described in `backup-data-ownership.md`.

`CompositeBackupDataContributor` checks the declared adapter coverage against
the reviewed inventory for one component before accessing a source. Every owned
`PORTABLE_PRIMARY`, `GIT_PRIMARY` and `EXTERNAL_DEPENDENCY` category requires exactly
one adapter. Unknown categories, claims on another owner's data, duplicate claims,
missing adapters and component/schema mismatches are rejected. `REBUILDABLE` and
`TRANSIENT` entries are excluded explicitly, with their inventory rationale carried
into the component's omissions alongside each adapter's profile-specific omissions.
The delegate list and reported categories are immutable; adapter declarations must
remain stable for the lifetime of the assembly.

The assembly requires the same component schema in the captured snapshot, calls
its adapters in the supplied order with the original snapshot and sink, and checks
cancellation before, between and after them. Any adapter or sink failure propagates;
the capture coordinator must discard partial staging. In particular, unsupported
history profiles remain unsupported rather than becoming successful partial exports.

`ApplicationBackupComponent` explicitly assembles source records/files, identities,
preferences and business configuration. All 16 required application categories are
checked against the packaged inventory. Construction does not inspect live sources
or provision storage. This factory is not a production capture bean. Declared
coverage does not establish stable source fencing, Git/reference closure, external
availability or complete manifest propagation; the remaining module adapters and
capture coordinator still have to supply those guarantees.

Before writing payloads, `BackupSnapshotCoordinator` collects profile-specific
omissions from every selected `BackupDataContributor` under the capture lease.
It combines them with plan omissions in deterministic component order, removes
duplicates and persists them in the manifest. Unselected adapters are not queried.
If omission metadata is unavailable, capture aborts before source writes and releases
the lease. These declarations survive reopening the staged capture; external
prerequisite and dependency propagation still requires the complete inventory.

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

## Retained base-catalogue inputs

`CatalogueSourceJournal` retains the exact workbook, overlay and optional relation
CSV bytes consumed by catalogue initialization. The parser uses the same immutable
capture that is stored with the materialized nodes and relations in their existing
transaction. A failed initialization or forced reload rolls back both the records
and their source evidence. Resource locations and transport diagnostics are absent
from the journal. Each input is bounded to 64 MiB and addressed by SHA-256.

Immutable blobs and revisions are separate from the versioned current pointer.
Identical input/use tuples reuse the current revision; changed inputs create a new
one. Reusing persisted nodes does not reopen the workbook or relation CSV. It keeps
their prior provenance and records the overlay actually applied during that start.
Pre-retention inputs remain `NOT_RETAINED`; a configured or shipped file cannot
replace a missing original. Disabling the overlay records `NOT_USED`. An absent
optional CSV is `NOT_USED`, an unreadable one is `NOT_RETAINED`, and an available
input whose optional parser fails is `PARSE_FAILED`. Cancellation aborts the whole
transaction, including cancellation during resource close.

`CatalogueSourceBackupContributor` owns the three journal categories and
`storage.files.catalogue`. Its version-1 datasets describe the current pointer,
selected revisions and retained blob entries. Source IDs require target mapping;
optimistic locking state never becomes portable authority.

| Profile | Catalogue input policy |
| --- | --- |
| Current state / installation current | Active revision and scalar use/digest/length provenance; originals marked `HISTORY_REQUIRED`; no raw files or old revisions |
| Selected version | No present-day journal reads; selected Git/source composition must supply version-specific evidence |
| Repository history | Only explicitly authorized whole input revisions and their original bytes |
| Installation full | All retained revisions and their deduplicated original bytes |

Even an active workbook or overlay can contain superseded content. Raw inputs
therefore require history. Current materialized nodes remain the responsibility of
`KnowledgeBackupContributor`. Scoped selection must prove authority over every
whole input revision, including its file contents; referencing one catalogue node
does not authorize a global workbook. No production selector or capture bean is
registered by this adapter. Framework/DSL/APQC sources remain with their actual
source or Git owners rather than being attributed to the base workbook.

Capture checks selected references, availability, bounded lengths and raw SHA-256
before the first output entry. The output pass must reproduce the exact checked
state, revision and blob metadata; changed, added or missing selected records abort
capture. It streams and verifies each raw file again under
`files/catalogue/<sha256>.bin`, checks the sink receipt, and honors cancellation
throughout. The caller must hold the stable fenced capture lease and discard all
staging on failure. Metadata uses batches of at most 200 IDs and a 100,000-identity
limit; raw streams use an 8 KiB verification buffer. Current profiles do not read
excluded raw payloads. Source rows are never modified by capture. Capture failures
discard private driver diagnostics, including errors suppressed during cleanup,
while preserving cancellation.

The independent catalogue migration runs before Hibernate validation in the
existing HSQL/Postgres Flyway startup path. Its DDL also defines SQL Server and
Oracle binary/number types; their evaluation profiles continue to use the existing
Hibernate-managed schema path. Real HSQL tests cover migration, rerun, constraints,
loader rollback, provenance preservation, public catalogue views and corrupt or
unauthorized exports. Reconstructing the journal object verifies persisted state;
it is not a physical database restart test. Cross-database restore and native
Windows acceptance remain P08/P12 work. Catalogue initialization retains the
existing single-instance startup contract.

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

Principal and source-installation UUIDs must use the canonical stored spelling.
Abbreviations and uppercase aliases are rejected instead of normalizing distinct
database keys into the same exported identity. Principal/account keys and the
installation ID are checked before identity datasets are written; invalid values
produce fixed diagnostics without exposing database contents.

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

## Current administrative preferences

`PreferencesBackupContributor` supplies the application's `storage.git.preferences`
category for `INSTALLATION_CURRENT`. It pins the main ref once and reads only the
single regular `preferences.json` blob from that commit. It never initializes an
empty repository, consults the mutable runtime cache, rewrites source refs or reads
older values, authors or messages. An empty source is explicitly `UNINITIALIZED`.
Scoped and selected-version exports report `OUTSIDE_SCOPE` without inspecting the
administrative repository. The adapter rejects `INSTALLATION_FULL` until P06
composes authorized auxiliary Git-history capture.

`data/application/preferences.json` contains typed version-1 settings and the
captured source commit. Remote URLs and tokens are excluded entirely, including
credentials hidden in URL user-info or query strings; only their presence and the
source push preference are recorded. Restore requires manual review and new target
connection configuration. This metadata never enables automatic pushes or analysis.
Values absent from the persisted document remain null; source deployment defaults
still belong to the configuration/dependency inventory and are not invented here.

Unknown keys, invalid typed values, duplicate properties, trailing JSON and invalid
UTF-8 reject the capture before output. Provider/parser diagnostics are redacted
without attaching a cause that could contain secret values. Input is limited to
1 MiB, commit/tree metadata to 64 KiB each and portable text settings to 4,096
characters. Reads use an 8 KiB buffer with capture checkpoints. Real in-memory Git
tests cover current/history separation, secret exclusion, ref movement, empty and
malformed repositories, wrong file modes, bounded input and cancellation.

## Business configuration and external prerequisites

`ApplicationConfigurationBackupContributor` writes `configuration/application.json`
for both installation profiles. Its explicit version-1 records preserve resolved
deployment values for preference defaults, product analysis, portfolio limits,
document limits, three business feature switches, context/draft limits and report
time zone. These are deployment values **before** the persisted preferences above,
not a dump of mutable runtime state. Missing values remain null; capture does not
invent defaults from annotations or activate features on the target.

The adapter reads only fixed, reviewed property names. It does not enumerate the
environment or inspect endpoint, path, credential, keyset or provider values, even
to check whether they are configured. Placeholder resolution is restricted to
the selected property and its documented environment alias. References to other
properties fail before lookup, including nested references and references with
defaults; a secret cannot be interpolated into an otherwise portable label.
Spring's preliminary lookup of an entire allowed `NAME:default` expression is
declined without querying a source; only the actual approved name is looked up.
Other operational settings remain the target operator's responsibility. All scoped
profiles produce `OUTSIDE_SCOPE` without reading deployment properties.
Configuration history is not retained by this source.

The inventory identifies four external prerequisite groups: deployment, identity
providers, secret stores and object storage. Their status is explicitly
`NOT_CAPTURED_OPERATOR_VERIFICATION_REQUIRED`; it does not assert that a provider
is configured, available or backed up. The operator must verify the applicable
database/network/TLS/path and provider configuration, encryption-key custody,
remote credentials and referenced-content closure. These prerequisites must be
carried into the manifest and restore plan by the complete capture composition.
Retained catalogue originals still need their own adapter and source evidence.

Restore policy is `MANUAL_TARGET_REVIEW`, with automatic outgoing execution
`DISABLED_UNTIL_APPROVED`. The later restore implementation must enforce these
requirements; exporting this metadata does not implement activation. Property
sources supplied by composition must remain stable for the capture interval.
No production capture bean is enabled by this adapter.

Each raw/resolved property is bounded to 4,096 characters. Scalar conversion uses
Spring's deployed conventions, including padded/hexadecimal numbers and Boolean
aliases such as `on` and `no`. Present but blank typed values are rejected rather
than silently becoming absent. Integers and longs keep their typed range; decimal
values use plain decimal text with at most 32 significant digits and scale magnitude
32. Malformed values or provider failures abort before output, with redacted
diagnostics and preserved interruption semantics. Checkpoints run before every
property read. Tests resolve the real packaged application and Keycloak defaults,
apply environment overrides, reject malformed/unbounded values and indirect secret
references, check scope exclusion, and verify cancellation without source changes.

## Interoperability journals

`IntegrationBackupContributor` owns eight typed datasets: connections, identity
mappings, checkpoints, operations, events, publications, publish items and publish
attempts. It declares the eight corresponding inventory categories and a version-1
execution policy. It is not registered as a production capture bean.

A server-owned `BackupIntegrationScope.Selector` supplies exact repository,
workspace and branch tuples under the capture lease. Their routing hash agrees
with `RepositoryContext.repositoryWorkspaceScopeKey`; the embedded internal-state
workspace key agrees with `WorkspaceOverlayScope`, which is a different identity.
Actor names do not determine ownership. A repository alone cannot resolve a hashed
scope or authorize another private workspace. Installation capture requires a
matching tuple for every stored connection. Unresolved ownership fails capture.

Before any dataset reaches the sink, relational checks validate both ends of
selected parent/child edges, connection pointers, publication ancestry and attempt
ownership. A read-only typed pass then checks embedded contexts, review/operation
identities and source evidence. Publication plan items must agree with their stored
children, including order, keys and intents. Completion receipts must resolve to
those children without duplicates or missing entries; COMMON checkpoints must
match the stored completed publication. These are structural source checks, not
proof of remote effects or validation of a target's authority. Source fingerprints
are retained as provenance; projected current payloads require new baselines and
fingerprints before any target execution.

The two read passes require the caller's stable, fenced capture interval. Queries
batch up to 200 scope hashes and stream records with regular cancellation/fence
checkpoints. Publication evidence checks retain only one publication's bounded
item/receipt identities (at most 1,024); they use a separate read cursor while the
parent dataset cursor is open. Source rows, revisions, claims and Git state are
never modified. Any late fence, storage or size failure still requires the capture
coordinator to discard staging.

Current profiles preserve every unfinished preview, including previews which do
not reserve the connection's active-operation pointer. They retain live mapping
metadata and pending publication candidates, conflict work, targets and intents.
Original transport input, mapping baselines, change-before payloads, completed or
cancelled work, removed mappings, checkpoints, event history, dispatch requests,
receipts and attempts require a history profile. Publication operation documents
are remote-before observations duplicated by the real journal; current exports
omit those duplicate payloads and take saved work from the publication records.
Contradictory terminal publication/operation markers fail rather than silently
losing pending work. History profiles retain authorized typed evidence across all
eight datasets. Runtime lease owner, lease deadline, lease epoch and row versions
are absent from both profiles.

Selected-version capture writes empty datasets and an `OUTSIDE_SCOPE` policy
without selecting scopes or reading live journals. Present-day unversioned records
cannot establish the state of a selected Git commit. Omissions for all profiles
are exposed through the standard manifest metadata path.

Persisted JSON binds only to explicit contract records. Duplicate/unknown fields,
trailing content, enum ordinals, scalar coercions and unsupported schemas are
rejected, with bounded document size and nesting. Parse and timestamp diagnostics
do not echo source values or attach source-bearing causes. Optional legacy fields
remain optional. Nested receipt instants use UTC ISO-8601 without losing fractional
precision.

Restores must map source database identities, principals, projects, requirements
and repository references, recompute baselines and obtain fresh target review.
`automaticExecutionEnabled` is false and outgoing work is marked
`MANUAL_REVIEW_REQUIRED`; exporting this policy does not implement enforcement in
an importer. Complete portfolio/principal/Git/document dependency closure, trusted
catalogue input selection, production assembly and restore/activation remain required
before the feature can be enabled.

## Read-only Git tree evidence

`ExistingGitBackupRepositories` opens only persisted logical repositories. Central
storage names come from the exact catalogue row; a workspace must join its selected
source repository and uses the same naming function as normal workspace storage.
No call to a provisioning or compatibility-seeding factory is made. The storage
library's secured existing-only facade permits discovery and reads, and rejects ref
mutations. Closing the capture session revokes reads through previously returned
tree handles without closing the shared Hibernate session factory. The coordinator
still owns the write fence, complete scope selection and fresh authorization checks.

Current stand reads resolve only the catalogue's current/default branch. An unborn
branch is explicitly empty and never falls back to another branch or the primary
repository. Selected-version reads use exactly the authorized commit ID and expose
no live branch. This stand port refuses history profiles; its result is not a claim
of full repository, reflog or database-reference closure.

`GitTreeCapture` records an immutable commit/tree and bounded file metadata with
path, object ID, regular/executable mode, byte length and SHA-256. It follows no
parents or live refs. Git object hashes are verified with collision detection;
commit and tree metadata, file counts, nesting and streamed payload bytes have
explicit bounds. Portable UTF-8 paths and case collisions are checked. Symlinks,
submodules and standard/legacy Git LFS pointers are refused because their external
contents have not been retained. No source configuration, hooks or credentials are
read or executed.

Metadata sizes are checked before opening an object because JGit may eagerly load
small objects. Each private reader uses an 8 KiB streaming threshold without changing
source repository settings. Application copy buffers are bounded; JGit's delta
reconstruction and provider caches have additional allocations, so this is not an
8 KiB total-heap guarantee or the later full Git performance qualification.

Copies recheck source lengths and hashes, use bounded buffers and checkpoint during
streaming. A generated-entry copy also verifies that its producer ran exactly once
and that the sink receipt agrees. Source errors do not expose provider diagnostics;
cancellation preserves interruption. The raw tree reader deliberately does not
project embedded TaxDSL history or overlay saved editor state. These remain required
in the stand-export composition before raw evidence can become a current-profile
payload. No production contributor or capture source is registered by these ports.

### Current and selected Git file views

`GitStandBackupSource` composes that existing-only reader with the durable editor
overlay and a module-composed `BackupDocumentProjector`. Current workspace reads
select exactly the repository/workspace/current-branch tuple from `editor_workspace`;
they validate the persisted scope hash, body envelope, revisions and checkpoint.
They do not read operation/checkpoint history or seed an editor workspace. Central
repositories have no private editor overlay. Selected-version reads do not query
the current editor state or use today's branch.

The saved current document supersedes `architecture.taxdsl` in the selected Git
tree, including a saved draft on an unborn branch. Every `.taxdsl` file is projected
to remove embedded portfolio history before its effective checksum is recorded.
Non-document files keep their exact bytes and regular/executable modes. A saved
document with different raw content removes the old committed document from the
architecture component's proof selector, preventing a second copy of superseded
content through materialized database documents. An unchanged saved document can
retain the original proof, but both output paths still use the stand projection.

The view retains bounded file metadata rather than all projected document bodies.
Each copy rereads and projects one bounded, strictly decoded UTF-8 document and
rechecks its length and SHA-256; saved copies also recheck semantic revision and
checkpoint evidence. Other files use the streaming tree copy. Effective file count,
byte limits and portable path collisions are checked after overlay/projection.
Closing the view revokes further copies, including copies interrupted by a callback.
The caller must hold the writer fence and fresh authorization until all copies finish.
The application document limit is at most 16 MiB; this is separate from total-file
limits and does not imply a total process heap bound for JGit or its storage provider.

Pending checkpoints and an editor checkpoint that differs from the captured Git head
are refused with a recovery/reconciliation message. Capture does not resume a writer,
invent a commit, or silently choose one side. Other branches' saved drafts remain
separate workspace records; they are not relabeled as the current branch. The source
refs/revisions and required commits exposed by this port describe capture evidence,
not the later exported synthetic Git repository. Inventory assembly, complete
reference closure and Git representations still precede production activation.
