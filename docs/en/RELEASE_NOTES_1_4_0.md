# 1.4.0 release notes — unreleased

The [complete release scope](../../release_notes.md) is the authoritative release
description. These notes summarize the user-visible changes; publication still
requires the final candidate's release gates.

## Requirements and architecture workflow

- A project/requirement portfolio with immutable versions, recoverable analysis jobs,
  evidence review, comparison, history and reports.
- Reviewed reformulation offers with explicit answers/deferrals, protected manual
  drafts, historical exports and explicit adoption as a new requirement version.
- Versioned planning profiles for a go-live year/date and standard references,
  carried through the existing editor/history and bounded ReqIF exchange.
- Versioned Word templates, snapshot-based architecture views and deterministic
  exports. Information Product overlay classifications remain provisional.
- Isolated workspace repositories and tab-bound workspace selection; central shared
  views are read-only until an editable workspace is selected.

## Analysis, local search and operation

Root relevance now keeps its independent 0–100 score. Child categories still
allocate the parent budget; concrete products retain independent suitability scores.
Paused analyses distinguish unassessed nodes from assessed zero relevance.

Local ONNX search uses normalized CLS embeddings and a separate ANN exploration
budget. Earlier vectors must be rebuilt through the controlled initializer. ONNX
cannot generate relationship assessments: analyses retain explicit partial evidence
instead of attempting unsupported completion calls. The English embedding model's
German reference misses remain visible and do not constitute a German-quality pass.

Optional local-user administration, guided Rancher/Helm configuration and the native
setup foundation reuse existing authentication and configuration. Backup instructions
now resolve the real Compose volume. PostgreSQL is the external production migration
path; MSSQL/Oracle remain compatibility profiles pending complete qualification.
Existing file-backed HSQLDB installations now prepare repository and portfolio
tenancy before Hibernate startup. The upgrade preserves recorded identities and
rejects ambiguous provenance instead of leaving populated tables unmigrated.
Follow the [upgrade notes](../../release_notes.md#upgrade-notes) and test restore,
search and Git history before production rollout.

GUI language currently does not guarantee the language of generated AI reasons.
Localized report labels do not translate stored reasons; the proposed independent
language controls are [documented separately](../dev/ANALYSIS_LANGUAGE_POLICY.md)
and are not part of this release change.

## Experimental Visio visual handoff (#965)

The Architecture Workbench now downloads the selected immutable snapshot as a bundle containing `diagram.vsdx`, a versioned mapping profile and a checksum-bound loss manifest. Export performs no new analysis. Native VSDX downloads carry the same profile and handoff data embedded in the file.

Stable Taxonomy element/relationship identities, original types, normalized scores, selection flags and authorized persisted reviews survive as typed Shape Data. Document properties and the manifest carry snapshot, requirement/version and repository/workspace/branch/commit authority when retained by the source snapshot. Missing metadata and deliberate layout/semantic losses are explicit.

Every generated package undergoes OPC/reference and pinned Visio schema validation. The supported profile uses masterless connectors, explicit styles and one or two snapshot pages. Repeated exports are deterministic. Automated coverage includes independent Apache POI loading/rendering and canonical graph comparison.

**Microsoft Visio desktop open/edit/save/reopen certification remains pending.** The format remains an experimental bounded visual handoff; no production-ready editing or universal Visio compatibility claim is made. External edits do not update Taxonomy. For semantic architecture interchange, use ArchiMate Exchange with authorized JSON evidence, subject to its separate #967 acceptance.

See the [user guide](USER_GUIDE.md) for download instructions and [profile/limits and desktop acceptance procedure](../dev/VISIO_HANDOFF_PROFILE.md) for exact scope and remaining evidence. The POI preview is supplementary and has observed glyph/arrowhead rendering limitations.
