# Portable backup format v1

Normative product specification: [issue #1146](https://github.com/carstenartur/Taxonomy/issues/1146).
The types in `taxonomy-domain/com.taxonomy.backup` define the framework-free
contract. This first package does not enable an export or restore endpoint.

## Selection

`BackupRequest` validates independent profile, scope, time, Git representation
and secrets axes. A repository selection maps each repository to its explicitly
selected workspaces; it must never imply access to other users' private work.

| Profile | Scope | Time | History | Secrets |
|---|---|---|---|---|
| CURRENT_STATE | workspace or repositories | current saved state | excluded | excluded |
| SELECTED_VERSION | workspace or repositories | exact commit per repository | ancestors excluded | excluded |
| REPOSITORY_HISTORY | workspace or repositories | declared complete history | included | excluded |
| INSTALLATION_CURRENT | installation | current saved states | excluded | excluded |
| INSTALLATION_FULL | installation | declared complete history | included | explicitly authorized, encrypted only |

History requires BUNDLE, BARE or WORKTREE. Snapshot profiles may use NONE or a
fresh repository containing one root commit. That root never carries original
ancestor objects. Scalar source commit/revision provenance is retained separately
from history. Selected-version access requires authorization for that content,
not just access to the current branch.

`AuthorizedBackupRequest` records a server-side decision, principal, timestamp
and capability set. It is not a bearer credential. Every job read, download and
activation must re-check current scope and permissions, including revocation.

## Container and manifest

A `.taxbackup` is a ZIP64 container; the suffix conveys no authority. A single
UTF-8 `manifest.json` identifies format version 1, exact application version and
build, backup UUID, source installation, request, UTC capture interval,
consistency proof, mandatory features, components, repositories, entry hashes,
external dependencies and declared omissions. Manifest size, nesting, JSON
string lengths and collection counts must be bounded before constructing domain
objects. Metadata strings allow at most 512 Unicode code points; collections and
maps at most 10,000 members, within the overall 4 MiB manifest limit. Writers
verify that their output passes the bounded reader. Duplicate JSON fields are invalid. Polymorphic class loading is forbidden.

| Path | Content |
|---|---|
| `data/<component>/` | Versioned UTF-8 JSON/NDJSON records |
| `repositories/<opaque-id>/` | One authoritative Git representation |
| `files/<content-id>` | Complete referenced binary bytes |
| `identities/` | Source identity, ownership and membership; target mapping is separate |
| `configuration/` | Sanitized business settings and operational inventory |
| `protected/` | Authorized encrypted DR-only secrets |
| `verification/` | Capture, reference, count and capability evidence |

Each entry records exact uncompressed byte length and lowercase SHA-256. Every
payload entry belongs to exactly one component; component dependencies must be
present and acyclic. Components carry schema version, completeness status and
entry paths. A hash alone does not authenticate origin. The manifest is not
self-hashed: the verified complete archive digest binds restore plans.

Repository records use opaque archive paths unrelated to external repository
names. A typed repository/workspace key distinguishes the central repository from
each selected workspace repository. A workspace-only selection never captures the
central repository implicitly. Selected versions identify one commit per such key.
They record the representation, source relationship, captured refs,
symbolic HEAD, per-branch semantic/checkpoint revisions, required orphan commits and exported/source
heads. A source commit hash is provenance, not a promise that its ancestors exist
in a snapshot export. A bare/worktree export must not contain hooks, active push
configuration, credential helpers, alternates or references outside the archive.

The archive reader additionally enforces normalized/case-folded path collision
checks, entry/count/ratio/size quotas, no symlinks, no traversal, no undeclared
entries and exact lengths/hashes. It stages everything in an isolated directory;
no target database, Git ref or application state is changed during verification.
Protected secrets require a verified streaming authenticated-encryption provider;
no standard plaintext export may contain them or leave plaintext temp files.

## Completeness and compatibility

`BackupInventory.requireClassified` rejects unknown persistent categories.
The catalogue is necessary but insufficient for a completeness claim: adapters
must also show that each required category has been captured or reconstructed
under the same writer barrier. Missing external data produces an explicit
DEPENDENT component and prerequisites, never silent completeness.

The first release accepts only the same application version and exact supported
component versions. Unknown format versions, unknown mandatory features and
unknown component schemas fail before any target mutation. Later converters
must be explicit and validated against older fixtures. There are no implicit
downgrades or incremental base chains in v1.

Numeric business decimals use strings where lossless precision is required;
binary content uses archive files. Timestamps use UTC. Null and empty string are
different wire values and require explicit Oracle handling. All DTO fields,
relationships and technical-ID mappings belong to versioned component schemas.

## Snapshot exclusions

CURRENT_STATE and SELECTED_VERSION exclude Git ancestors, previous blobs, reflog
payloads, editor inverse commands and historical operations, previous requirement
versions, previous reformulations, and old source contents. Current saved drafts,
active questions/answers and necessary current provenance remain primary data.
Nested JSON/evidence documents must be projected explicitly so they cannot hide
past payloads. Browser-only unsaved edits are not included in server backups.

Full history preserves original commit IDs/authors, explicit reflogs and objects
referenced only from durable application state. Historical authors never imply
current ownership, login eligibility or access rights.

## Delivery state

This contract is preparatory. Backup, restore, Git transport, cross-database
support and encryption must remain unavailable until the corresponding issue
packages and real acceptance runs establish those capabilities.
