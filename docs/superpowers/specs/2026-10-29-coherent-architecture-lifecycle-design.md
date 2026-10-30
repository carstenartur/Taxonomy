# Coherent architecture lifecycle

## User decisions and scope

An architecture remains independently addressable after the originating project closes. Later projects can extend it. Requirements have stable, project-qualified identities and immutable text versions. A review records exactly which requirement version is realized by which architecture dependencies, who reviewed it, why, and under which contributing project. A changed requirement must not silently replace its earlier review.

The authorized work joins project portfolio, requirement detail, analysis, architecture overview, interactive editor and versioning into a consistent workflow. Contextual help opens the explanation of the actual view. Existing retained analysis evidence, supplier questions and requirement reformulation remain available through their established services. No live paid provider calls are needed to validate this work.

## Persistent model

| Concept | Identity and lifetime | History |
| --- | --- | --- |
| Architecture | Existing logical JGit repository, independent of projects | Real commits, refs, parents and signatures |
| Project | Existing portfolio business key within the authorized architecture scope | Portable project block and retained contributions |
| Requirement | Original project key plus requirement key; stable canonical identity | Immutable numbered text versions and exact UTF-8 content hashes |
| Contribution | Project, requirement text version and hash | Retained canonical contribution records |
| Realization review | Exact text version and hash, disposition, reviewer, time, rationale and dependency hashes | Canonical DSL in semantic revisions and Git checkpoints |
| Architecture stand | Selected semantic workspace revision or reachable Git commit | Selection is distinct from current business context and retained analysis source |

OPEN, PARTIAL and COMPLETE describe the reviewed architecture realization. REVIEW_REQUIRED is computed when the current text or a reviewed dependency differs. INVALID_EVIDENCE reports damaged or unsupported review evidence. An unrelated architecture edit must not invalidate a review. Relation reviews also bind their endpoint elements; mapping reviews bind the mapped element.

Project closure does not delete the architecture, requirements, immutable versions, contribution records or review evidence. Subsequent projects can reuse an existing requirement identity, add another requirement or explicitly change an existing requirement's text. Canonical requirements and portable portfolio blocks must agree about the selected immutable version.

## Requirement intake and explicit choices

Read-only intake freezes a bounded, authorized candidate set. The configured provider may classify duplicates, changes, additions and conflicts, using only supplied candidate identities and verifiable quotations. The provider runs outside database transactions. Scope and immutable candidates are checked again afterward. Exact raw-text SHA-256 matches remain available when the provider is unavailable. Incomplete review is visibly different from no relevant match.

The person chooses NEW, CHANGE or LINK. CHANGE binds the expected immutable version and hash. LINK reuses the existing identity and adds a contribution; it does not replace its text. Requirement detail always offers an explicit new version of the known requirement even without AI. Similar readable architectures can be opened before creating a new architecture. Creation keeps the new repository and working-copy identities across transport recovery.

## Atomicity and publication

Requirement mutation, canonical portfolio contribution, semantic journal entry and checkpoint intent share one database transaction. The workspace lock precedes project locks; project locks have stable ordering. Git publication follows the transaction, outside ORM locks.

Each intent has a stable UUID and exact fingerprint. Retrying an accepted intent returns its retained result without repeating project creation, requirement creation, text-version creation or realization review. Even a semantically unchanged accepted integration receives a durable operation receipt. Older accepted operations retain their own version and checkpoint after later operations.

An interrupted Git write remains CHECKPOINT_PENDING. A moved or rejected target becomes RECONCILIATION_REQUIRED. Neither state is reported as completed. Publication proof reads actual Git state and retained operation evidence, rather than trusting only a database completion flag.

## Reconciliation

Reconciliation compares original base, local journal source and incoming Git source. Independent changes can be combined. Conflicting blocks and document-level annotations require explicit LOCAL or INCOMING choices and a fresh preview. The reserved document conflict identity is `$document`.

The combined source is independently validated for DSL semantics, dependencies and immutable portfolio consistency. A plausible textual merge is insufficient. Portfolio materialization joins the journal transaction before a rebase or publication intent is written. A conflict rolls back portfolio, journal and checkpoint metadata. Managed portfolio removal requires a supported archival operation; choosing incoming source cannot silently erase retained immutable data.

## Native Git history

Product history uses JGit and the existing Hibernate-backed repository integration. It never starts a Git shell process. Queries walk real reachable ancestry at a captured HEAD and return actual parents, signatures and requirement/realization changes. Bounded scans and pagination expose COMPLETE, PARTIAL or LIMIT_REACHED coverage. A changed HEAD invalidates the continuation.

Object transfer preserves original commit objects and identities. Publishing divergent work creates a real merge parent; reintegrating a descendant fast-forwards when valid. Source authority is checked again after transfer before ref publication. Ref mutation uses the same repository monitor as existing checkpoint/import paths.

## Ergonomic contract

All six views share navigation, business identities, status vocabulary, context and contextual help. The primary display shows architecture name, project, requirement and human version, plus the selected branch/stand. Full technical identities remain accessible in a disclosure. Current context and retained source context are separately labeled.

The lifecycle table distinguishes text version in the selected stand from the reviewed version, and exposes review evidence, contributing projects and links. Native history provides text search, requirement filtering and bounded continuation. Commit subjects remain readable; complete messages, parents, signature evidence and changes are available in details.

Historical selection is read-only. Delayed overview/history/preview/accept responses cannot move the person to another scope or relabel current requirement text as an old version. Review and reconciliation dialogs bind their intent to the exact scope, source and selected stand. Drafts survive failures and later typing is retained after an earlier asynchronous save finishes.

## Acceptance scenario

Start with requirements 1–9 reviewed as COMPLETE. Add requirement 10, review it first PARTIAL and then COMPLETE. Add 11 and 12 as OPEN. Change requirement 9 to text version 2 after 10 was completed. The current architecture must show 9/v2 as REVIEW_REQUIRED against reviewed v1, 10 COMPLETE and 11/12 OPEN. The earlier checkpoint and semantic revision still show 9/v1. Reload and a real file-backed application-context restart retain these distinctions and the searchable Git history.

## Recovery note

The original unpushed working tree disappeared during verification. This design and implementation were reconstructed in an independent checkout from the approved decisions and retained change context. Results from the lost source are not used as evidence for the reconstructed branch; verification must be repeated.
