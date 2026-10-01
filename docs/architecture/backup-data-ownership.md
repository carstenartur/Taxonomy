# Portable backup ownership inventory (v1)

Issue: [#1146](https://github.com/carstenartur/Taxonomy/issues/1146).
Baseline: `a0da6b20e1980ae2e71d4100b2d1bbd951802a1d`.

This is an ownership contract, not evidence that export/restore adapters are
already complete. `BackupCoverageInventoryTest` discovers JPA entities from
compiled classes and rejects any missing classification. The runtime catalogue
is `taxonomy-app/src/main/resources/backup/coverage-inventory.tsv`. New persistent
categories must be added deliberately, with an owner and rationale. Collection
and join tables are part of their owning entity's explicit portable record;
`user_roles` is additionally inventoried below. Schema bookkeeping is recreated
by versioned migrations, never imported on top of a different database schema.

PORTABLE_PRIMARY categories require explicit versioned DTOs and reference
mapping, not serialization of managed entities. GIT_PRIMARY categories use Git
objects as their single portable authority. REBUILDABLE is permitted only with
the named reconstruction path; restored readiness/worker state is not trusted.
TRANSIENT excludes live sessions and leases, not the saved work they refer to.
EXTERNAL_DEPENDENCY must appear in the manifest if required and not captured.

## Persistent categories

| Category / entity | Module owner | Rule | Database table | Contract |
|---|---|---|---|---|
| `com.taxonomy.analysis.recovery.AnalysisContinuationRun` | analysis | PORTABLE_PRIMARY | `analysis_continuation` | Durable work/status retained; restore interrupted, never restore leases or automatically start external work |
| `com.taxonomy.analysis.recovery.AnalysisQuestionCheckpoint` | analysis | PORTABLE_PRIMARY | `analysis_question_checkpoint` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.analysis.session.AnalysisWorkingDraft` | analysis | PORTABLE_PRIMARY | `analysis_working_draft` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.provenance.model.RequirementSourceLink` | application | PORTABLE_PRIMARY | `requirement_source_link` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.provenance.model.SourceArtifact` | application | PORTABLE_PRIMARY | `source_artifact` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.provenance.model.SourceFragment` | application | PORTABLE_PRIMARY | `source_fragment` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.provenance.model.SourceVersion` | application | PORTABLE_PRIMARY | `source_version` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.security.model.AppRole` | application | PORTABLE_PRIMARY | `app_role` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.security.model.AppUser` | application | PORTABLE_PRIMARY | `app_user` | Current identity metadata is portable; password hashes only in authorized encrypted DR; no sessions |
| `com.taxonomy.security.webdav.WebDavApplicationCredential` | application | TRANSIENT | `webdav_application_credential` | Revocable application login tokens must be issued again on target |
| `com.taxonomy.architecture.model.ArchitectureDslDocument` | architecture | PORTABLE_PRIMARY | `architecture_dsl_document` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.ExternalIdentityMappingEntity` | interop | PORTABLE_PRIMARY | `interop_identity` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.IntegrationCheckpointEntity` | interop | PORTABLE_PRIMARY | `interop_checkpoint` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.IntegrationConnectionEntity` | interop | PORTABLE_PRIMARY | `interop_connection` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.IntegrationEventEntity` | interop | PORTABLE_PRIMARY | `interop_event` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.IntegrationOperationEntity` | interop | PORTABLE_PRIMARY | `interop_operation` | Durable work/status retained; restore interrupted, never restore leases or automatically start external work |
| `com.taxonomy.interop.persistence.IntegrationPublicationEntity` | interop | PORTABLE_PRIMARY | `interop_publication` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.IntegrationPublishAttemptEntity` | interop | PORTABLE_PRIMARY | `interop_publish_attempt` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.interop.persistence.IntegrationPublishItemEntity` | interop | PORTABLE_PRIMARY | `interop_publish_item` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.catalog.model.TaxonomyNode` | knowledge | PORTABLE_PRIMARY | `taxonomy_node` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.catalog.model.TaxonomyRelation` | knowledge | PORTABLE_PRIMARY | `taxonomy_relation` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.relations.model.RelationDecisionProjection` | knowledge | REBUILDABLE | `relation_decision_projection` | Rebuild using RelationBranchProjectionRebuildService from captured DSL |
| `com.taxonomy.relations.model.RelationDecisionProjectionCheckpoint` | knowledge | REBUILDABLE | `relation_decision_projection_checkpoint` | Rebuild together with relation projection; never restore stale readiness |
| `com.taxonomy.relations.model.RelationEvidence` | knowledge | PORTABLE_PRIMARY | `relation_evidence` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.relations.model.RelationHypothesis` | knowledge | PORTABLE_PRIMARY | `relation_hypothesis` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.relations.model.RelationProjectionRecovery` | knowledge | PORTABLE_PRIMARY | `relation_projection_recovery` | Durable recovery journal with commit provenance and failure history; restore pending work without old worker claims |
| `com.taxonomy.relations.model.RelationProposal` | knowledge | PORTABLE_PRIMARY | `relation_proposal` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.relations.model.RequirementCoverage` | knowledge | PORTABLE_PRIMARY | `requirement_coverage` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.ArchitectureProject` | portfolio | PORTABLE_PRIMARY | `arch_project` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.ProductCatalogEntry` | portfolio | PORTABLE_PRIMARY | `product_catalog` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.ProductTaxonomyCoverage` | portfolio | PORTABLE_PRIMARY | `product_taxonomy` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.ProjectConflict` | portfolio | PORTABLE_PRIMARY | `project_conflict` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.ProjectRequirement` | portfolio | PORTABLE_PRIMARY | `project_requirement` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.ProjectRequirementVersion` | portfolio | PORTABLE_PRIMARY | `project_req_version` | History profile includes full records; stand export only selected current content/provenance, never inverse/old payloads |
| `com.taxonomy.portfolio.model.ProjectSolution` | portfolio | PORTABLE_PRIMARY | `project_solution` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.RequirementAnalysisJob` | portfolio | PORTABLE_PRIMARY | `req_analysis_job` | Durable work/status retained; restore interrupted, never restore leases or automatically start external work |
| `com.taxonomy.portfolio.model.RequirementAnalysisJobItem` | portfolio | PORTABLE_PRIMARY | `req_analysis_item` | Durable work/status retained; restore interrupted, never restore leases or automatically start external work |
| `com.taxonomy.portfolio.model.RequirementAnalysisSnapshot` | portfolio | PORTABLE_PRIMARY | `req_analysis_snapshot` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.RequirementElementMapping` | portfolio | PORTABLE_PRIMARY | `req_element_mapping` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.RequirementRelationMapping` | portfolio | PORTABLE_PRIMARY | `req_relation_mapping` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.RequirementSolutionLink` | portfolio | PORTABLE_PRIMARY | `req_solution_link` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.SolutionDefinition` | portfolio | PORTABLE_PRIMARY | `solution_definition` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.SolutionProductCandidate` | portfolio | PORTABLE_PRIMARY | `solution_product` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.model.SolutionTaxonomyCoverage` | portfolio | PORTABLE_PRIMARY | `solution_taxonomy` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationAdoption` | portfolio | PORTABLE_PRIMARY | `reformulation_adoption` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationAdoptionPreview` | portfolio | PORTABLE_PRIMARY | `reformulation_adoption_preview` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationNodeCheckpoint` | portfolio | PORTABLE_PRIMARY | `reformulation_node_checkpoint` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationPortableEvidence` | portfolio | PORTABLE_PRIMARY | `reformulation_portable_evidence` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationProposal` | portfolio | PORTABLE_PRIMARY | `reformulation_proposal` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationRecoveryLease` | portfolio | TRANSIENT | `reformulation_recovery_lease` | Worker lease only; restore resumable runs without live claims |
| `com.taxonomy.portfolio.reformulation.ReformulationRevision` | portfolio | PORTABLE_PRIMARY | `reformulation_revision` | History profile includes full records; stand export only selected current content/provenance, never inverse/old payloads |
| `com.taxonomy.portfolio.reformulation.ReformulationRun` | portfolio | PORTABLE_PRIMARY | `reformulation_run` | Durable work/status retained; restore interrupted, never restore leases or automatically start external work |
| `com.taxonomy.portfolio.reformulation.ReformulationUsageAttempt` | portfolio | PORTABLE_PRIMARY | `reformulation_usage_attempt` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.portfolio.reformulation.ReformulationUsageSession` | portfolio | PORTABLE_PRIMARY | `reformulation_usage_session` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.editor.persistence.EditorCheckpoint` | workspace | PORTABLE_PRIMARY | `editor_checkpoint` | History profile includes full records; stand export only selected current content/provenance, never inverse/old payloads |
| `com.taxonomy.editor.persistence.EditorOperation` | workspace | PORTABLE_PRIMARY | `editor_operation` | History profile includes full records; stand export only selected current content/provenance, never inverse/old payloads |
| `com.taxonomy.editor.persistence.EditorWorkspace` | workspace | PORTABLE_PRIMARY | `editor_workspace` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.versioning.model.ArchitectureCommitIndex` | workspace | PORTABLE_PRIMARY | `architecture_commit_index` | Retain commit index until complete reconstruction for all retained contexts is proven; Lucene remains rebuildable |
| `com.taxonomy.versioning.model.ContextHistoryRecord` | workspace | PORTABLE_PRIMARY | `context_history_record` | History profile includes full records; stand export only selected current content/provenance, never inverse/old payloads |
| `com.taxonomy.workspace.model.RepositoryMembership` | workspace | PORTABLE_PRIMARY | `repository_membership` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.workspace.model.SyncState` | workspace | PORTABLE_PRIMARY | `sync_state` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.workspace.model.SystemRepository` | workspace | PORTABLE_PRIMARY | `system_repository` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.workspace.model.UserWorkspace` | workspace | PORTABLE_PRIMARY | `user_workspace` | Explicit versioned records; preserve business IDs and map technical foreign keys |
| `com.taxonomy.workspace.model.WorkspaceProjection` | workspace | REBUILDABLE | `workspace_projection` | Rebuild workspace projection from captured DSL and Git; old readiness is invalid |
| `storage.jgit.objects-refs-reflogs` | workspace | GIT_PRIMARY | `—` | All logical repositories via Git objects/refs plus explicit reflog records; never duplicate physical pack tables |
| `storage.git.preferences` | application | GIT_PRIMARY | `—` | Current sanitized preferences or full authorized history; credentials excluded by default |
| `storage.git.document-templates` | templates | GIT_PRIMARY | `—` | Template manifests and all OOXML/blob parts from document-template Git repository |
| `storage.git.portfolio` | portfolio | GIT_PRIMARY | `—` | Portable portfolio Git files plus module-owned durable records; same repository only once |
| `storage.files.source-content` | application | PORTABLE_PRIMARY | `—` | All referenced source bytes and fragments by content digest; dangling file references fail capture |
| `storage.files.catalogue` | knowledge | PORTABLE_PRIMARY | `—` | Exact retained catalogue inputs; originals require history, never infer missing originals from configured resources |
| `com.taxonomy.catalog.provenance.CatalogueSourceBlob` | knowledge | PORTABLE_PRIMARY | `catalogue_source_blob` | Immutable retained bytes with verified digest and length; raw inputs require history |
| `com.taxonomy.catalog.provenance.CatalogueSourceRevision` | knowledge | PORTABLE_PRIMARY | `catalogue_source_revision` | Exact input-use provenance; current metadata only, older revisions require history |
| `com.taxonomy.catalog.provenance.CatalogueSourceState` | knowledge | PORTABLE_PRIMARY | `catalogue_source_state` | Current revision pointer; target owns optimistic locking state |
| `storage.indices.search` | knowledge | REBUILDABLE | `—` | Lucene and Hibernate Search indices rebuilt from captured primary records and Git |
| `storage.schema-migrations` | application | REBUILDABLE | `—` | Run versioned target migrations; SQL schema and sequences are target-owned |
| `storage.security.user-roles` | application | PORTABLE_PRIMARY | `—` | Join table user_roles included in explicit identity export; role mapping requires approval |
| `storage.security.principals` | application | PORTABLE_PRIMARY | `app_principal`, `principal_binding`, `principal_installation` | Stable identities and verified provider bindings; historical unresolved owners remain disabled |
| `storage.security.backup-grants` | application | PORTABLE_PRIMARY | `backup_capability_grant`, `backup_version_grant`, `principal_access_audit` | Explicit grants and stable-actor audit; target privileges require a separate decision |
| `configuration.application` | application | PORTABLE_PRIMARY | `—` | Versioned non-secret business settings; operational endpoints require explicit target mapping |
| `configuration.deployment` | application | EXTERNAL_DEPENDENCY | `—` | Deployment manifests, database credentials, hostnames, TLS and external file paths inventoried; not blindly applied |
| `external.identity-providers` | application | EXTERNAL_DEPENDENCY | `—` | AD/OIDC/Keycloak provider backups remain operator-owned; issuer/subject bindings must be verified |
| `external.git-servers` | workspace | EXTERNAL_DEPENDENCY | `—` | Local fetched state is not a full server backup; declare observed refs and capture time |
| `external.secret-stores` | application | EXTERNAL_DEPENDENCY | `—` | Encryption and remote credentials supplied out of band; key custody outside archive |
| `external.object-storage` | application | EXTERNAL_DEPENDENCY | `—` | Referenced blobs must be copied or declared a blocking external prerequisite |
| `runtime.sessions-caches-browser` | application | TRANSIENT | `—` | No browser-only edits, HTTP sessions, remember-me tokens, caches or executing LLM calls |

| `runtime.backup-barrier` | application | TRANSIENT | `backup_barrier_state`, `backup_writer_lease` | Live process coordination only; never reactivate leases during restore |
| `runtime.backup-jobs` | application | TRANSIENT | `backup_job_queue`, `backup_job`, private spool/archive files | Never reactivate export claims or download receipts during restore |

## Capture boundary

The write barrier must cover editor operations/checkpoints, workspace catalogue
and memberships, Git refs/packs/reflogs, portfolio edits and asynchronous job
completion, analysis drafts/recovery, reformulation/adoption/usage accounting,
knowledge relation decisions, templates, preferences, source ingestion, identity
and role changes, WebDAV, integration publications and external synchronization.
An inventory entry alone does not establish that its writer is fenced.

Capture must include commits referenced by editor checkpoints, sync state,
workspace metadata, projections, analysis/reformulation provenance and retained
reflogs even after normal branches have been removed. Snapshot profiles instead
carry only authorized current/selected content and explicit scalar provenance;
old operation payloads, inverse commands and deleted historical source material
must be absent. Operational secrets and all tokens are excluded by default.

## Database representation

Wire IDs are typed business keys or explicit source-ID mappings, never assumed
to be portable sequence values. Decimal values use decimal strings when precision
matters; binary values are archive entries with SHA-256 and length. UTC timestamps
use ISO-8601 `Z`. Null and empty text are distinct on the wire; Oracle adapters
must preserve that distinction explicitly for fields where it is meaningful.
No database family is declared supported until its real roundtrip passes.

## Knowledge current-record adapter

`KnowledgeBackupContributor` owns seven explicit bounded datasets: materialized catalogue nodes, scoped relations, analysis hypotheses and their evidence, current review decisions, projection recovery, and installation-only legacy coverage. Scoped nodes comprise captured-document selections, relation/proposal/hypothesis endpoints and a validated parent closure. Installation exports retain every materialized catalogue node. Missing endpoints, missing/cyclic/inconsistent parent identities and missing or foreign analysis dependencies fail before output. Embeddings, counts, readiness and diagnostic messages remain excluded.

The portfolio owner supplies an identity-only `BackupAnalysisReference` domain port. Repository/workspace, project, requirement, session and active version must match; old snapshots are recognized without reading their payloads. Current unlinked hypotheses require selection proof from the captured Git/working document. Rejected review decisions remain current decisions. Full history retains earlier scoped evidence; selected-version exports never consult present-day unversioned records.

Current recovery includes only pending work with `REBUILD_FROM_CAPTURE`, without former HEAD or automatic execution. Full history retains completed recovery provenance; diagnostic messages are never portable. Global legacy coverage has no repository authority, stays in its own installation namespace, and includes earlier requirement text only in full history.

The single reviewed forward dependency `knowledge/com.taxonomy.catalog.backup -> workspace/com.taxonomy.workspace.backup` reuses the existing exact scope predicate. Cycle and context rules are unchanged. `CatalogueSourceBackupContributor` separately captures retained input evidence as described in `backup-current-records.md`; materialized records are not proof that shipped Excel matches the imported input. Neither adapter alone completes P05 or enables production capture.
