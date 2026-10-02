package com.taxonomy.portfolio.backup;

import com.taxonomy.backup.*;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.exchange.backup.PortableRows.Query;
import com.taxonomy.exchange.backup.PrincipalScopeCapture;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.workspace.backup.BackupRowScope;
import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import static com.taxonomy.exchange.backup.PortableRows.*;

/** Version 1 business records and source-reference mappings. SQL projections and profile closure are explicit. */
public final class PortfolioBackupContributor implements BackupDataContributor {
    private final PortableRows rows;
    public PortfolioBackupContributor(DataSource database) { rows = new PortableRows(database); }
    /** The same current ownership rows as the record export; creator/author labels remain historical metadata. */
    public Set<String> principalScopes(SnapshotContext snapshot, BackupCheckpoint checkpoint) throws IOException {
        var scope = new BackupRowScope(snapshot); var queries = new ArrayList<Query>();
        if (!scope.selectedVersion()) {
            var tenant = scope.tenants("scope_key");
            for (String table : List.of("arch_project", "project_requirement", "solution_definition"))
                queries.add(new Query("select scope_key,owner_username from " + table + " where " + tenant.sql() + " order by id", tenant.parameters()));
        }
        return new PrincipalScopeCapture(rows).capture(queries, row -> {
            if (!scope.includesTenant(text(row, "scope_key"))) throw new IOException("Principal reference belongs to a different portfolio tenant");
            return new PrincipalScopeCapture.Scope(text(row, "owner_username"));
        }, checkpoint);
    }
    @Override public BackupComponentId componentId() { return new BackupComponentId("portfolio"); }
    @Override public int schemaVersion() { return 1; }
    @Override public Set<String> categories() { return Set.of(
            "com.taxonomy.portfolio.model.ArchitectureProject",
            "com.taxonomy.portfolio.model.ProjectRequirement",
            "com.taxonomy.portfolio.model.ProjectRequirementVersion",
            "com.taxonomy.portfolio.model.RequirementAnalysisJob",
            "com.taxonomy.portfolio.model.RequirementAnalysisSnapshot",
            "com.taxonomy.portfolio.model.RequirementAnalysisJobItem",
            "com.taxonomy.portfolio.model.RequirementElementMapping",
            "com.taxonomy.portfolio.model.RequirementRelationMapping",
            "com.taxonomy.portfolio.model.SolutionDefinition",
            "com.taxonomy.portfolio.model.ProductCatalogEntry",
            "com.taxonomy.portfolio.model.ProjectSolution",
            "com.taxonomy.portfolio.model.ProductTaxonomyCoverage",
            "com.taxonomy.portfolio.model.SolutionTaxonomyCoverage",
            "com.taxonomy.portfolio.model.RequirementSolutionLink",
            "com.taxonomy.portfolio.model.SolutionProductCandidate",
            "com.taxonomy.portfolio.model.ProjectConflict",
            "com.taxonomy.portfolio.reformulation.ReformulationProposal",
            "com.taxonomy.portfolio.reformulation.ReformulationRevision",
            "com.taxonomy.portfolio.reformulation.ReformulationRun",
            "com.taxonomy.portfolio.reformulation.ReformulationNodeCheckpoint",
            "com.taxonomy.portfolio.reformulation.ReformulationUsageSession",
            "com.taxonomy.portfolio.reformulation.ReformulationUsageAttempt",
            "com.taxonomy.portfolio.reformulation.ReformulationAdoptionPreview",
            "com.taxonomy.portfolio.reformulation.ReformulationAdoption",
            "com.taxonomy.portfolio.reformulation.ReformulationPortableEvidence"); }
    @Override public List<String> omissions(BackupProfile profile) {
        if (profile == BackupProfile.SELECTED_VERSION) return List.of("portfolio: present-day database rows are excluded; the projected selected Git document is authoritative");
        if (!profile.includesHistory()) return List.of("portfolio: non-current requirement/analysis/reformulation versions, original text, change reasons and adoption ancestry are excluded", "portfolio: worker ownership, error diagnostics and automatic execution are excluded");
        return List.of("portfolio: worker ownership, error diagnostics and automatic execution are excluded");
    }
    /** Uses the same version selection as the business export; never infers ownership from business IDs. */
    public List<BackupSourceReference> sourceReferences(SnapshotContext snapshot) throws IOException {
        var scope=new BackupRowScope(snapshot);
        var result=new ArrayList<BackupSourceReference>();
        rows.visit(query(scope,"select t.id,t.scope_key,owner.scope_key as requirement_scope,t.source_artifact_id,t.source_version_id,t.source_fragment_ids from project_req_version t left join project_requirement owner on owner.id=t.requirement_id",
                scope.tenants("t.scope_key"),"exists (select 1 from project_requirement r where r.id=t.requirement_id and r.scope_key=t.scope_key and r.current_version_id=t.id)","t.id"),
                scoped(scope, r -> {
                    requireSameTenant(r, "requirement_scope");
                    return new BackupSourceReference(reference(r,"portfolio.requirement-version","id"),
                            reference(r,"application.source-artifact","source_artifact_id"),
                            reference(r,"application.source-version","source_version_id"),fragmentReferences(text(r,"source_fragment_ids")));
                }),
                ref -> {
                    if (ref.artifact()!=null || ref.version()!=null || !ref.fragments().isEmpty()) {
                        if (result.size()>=100_000) throw new IOException("Source dependency limit exceeded");
                        result.add(ref);
                    }
                });
        return List.copyOf(result);
    }
    /** The identity closure includes old snapshots so the consumer can distinguish stale from foreign links. */
    public List<BackupAnalysisReference> analysisReferences(SnapshotContext snapshot) throws IOException {
        var scope = new BackupRowScope(snapshot);
        if (scope.selectedVersion()) return List.of();
        var tenant = scope.tenants("s.scope_key");
        rows.requireEmpty(new Query("select 1 from req_analysis_snapshot s left join arch_project p on p.id=s.project_id "
                + "left join project_requirement r on r.id=s.requirement_id left join project_req_version v on v.id=s.requirement_version_id where " + tenant.sql()
                + " and (p.id is null or r.id is null or v.id is null or p.scope_key<>s.scope_key or r.scope_key<>s.scope_key "
                + "or r.project_id<>s.project_id or v.requirement_id<>s.requirement_id or v.scope_key<>s.scope_key)", tenant.parameters()));
        var result = new ArrayList<BackupAnalysisReference>();
        rows.visit(new Query("select s.id,s.scope_key,p.scope_key as project_scope,r.scope_key as requirement_scope,v.scope_key as version_scope,"
                + "s.project_id,s.requirement_id,s.requirement_version_id,s.analysis_session_id,"
                + "p.repository_id,p.workspace_id,r.current_version_id,r.current_snapshot_id from req_analysis_snapshot s "
                + "join arch_project p on p.id=s.project_id join project_requirement r on r.id=s.requirement_id "
                + "join project_req_version v on v.id=s.requirement_version_id where "
                + tenant.sql() + " order by s.id", tenant.parameters()), scoped(scope, r -> {
                    requireSameTenant(r, "project_scope");
                    requireSameTenant(r, "requirement_scope");
                    requireSameTenant(r, "version_scope");
                    return new BackupAnalysisReference(
                            reference(r,"portfolio.analysis-snapshot","id"), reference(r,"portfolio.project","project_id"),
                            reference(r,"portfolio.requirement","requirement_id"), new BackupRepositoryKey(text(r,"repository_id"),text(r,"workspace_id")),
                            text(r,"analysis_session_id"), scope.history() || Objects.equals(text(r,"id"),text(r,"current_snapshot_id"))
                                    && Objects.equals(number(r,"requirement_version_id"),number(r,"current_version_id")));
                }), ref -> {
                    if (result.size() >= 100_000) throw new IOException("Analysis dependency limit exceeded");
                    result.add(ref);
                });
        return List.copyOf(result);
    }
    @Override public void write(SnapshotContext snapshot, ComponentSink sink) throws IOException {
        var scope = new BackupRowScope(snapshot); var profile = snapshot.authorization().request().profile();
        if (!scope.selectedVersion()) validateScopeClosure(scope);
        var includedRuns = new HashSet<String>();
        write(scope,sink,"project",profile,query(scope,"select t.id,t.scope_key,t.repository_id,t.workspace_scope,t.branch_name,t.workspace_id,t.owner_username,t.project_key,t.title,t.description,t.status,t.target_architecture,t.target_date,t.budget_amount,t.budget_currency,t.created_at,t.updated_at from arch_project t",scope.tenants("t.scope_key"),"1=1","t.id"),r -> {
            return new ProjectRecord(reference(r,"portfolio.project","id"),
                    text(r,"scope_key"),
                    text(r,"repository_id"),
                    text(r,"workspace_scope"),
                    text(r,"branch_name"),
                    text(r,"workspace_id"),
                    text(r,"owner_username"),
                    text(r,"project_key"),
                    text(r,"title"),
                    text(r,"description"),
                    enumeration(r,"status",ProjectStatus.class),
                    text(r,"target_architecture"),
                    date(r,"target_date"),
                    decimal(r,"budget_amount"),
                    text(r,"budget_currency"),
                    instant(r,"created_at"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"requirement",profile,query(scope,"select t.id,t.scope_key,t.project_id,t.requirement_key,t.title,t.status,t.priority,t.criticality,t.requirement_type,t.review_status,t.owner_username,t.current_version_id,(select v.scope_key from project_req_version v where v.id=t.current_version_id and v.requirement_id=t.id) as current_version_scope,(case when "+historyOr(scope)+"exists (select 1 from req_analysis_snapshot valid_snapshot where valid_snapshot.id=t.current_snapshot_id and valid_snapshot.scope_key=t.scope_key and valid_snapshot.requirement_id=t.id and valid_snapshot.requirement_version_id=t.current_version_id) then t.current_snapshot_id else null end) as current_snapshot_id,t.created_at,t.updated_at from project_requirement t",scope.tenants("t.scope_key"),"1=1","t.id"),r -> {
            if (number(r,"current_version_id") != null) requireSameTenant(r, "current_version_scope");
            return new ProjectRequirementRecord(reference(r,"portfolio.requirement","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.project","project_id"),
                    text(r,"requirement_key"),
                    text(r,"title"),
                    enumeration(r,"status",RequirementStatus.class),
                    r.getInt("priority"),
                    enumeration(r,"criticality",Criticality.class),
                    enumeration(r,"requirement_type",RequirementType.class),
                    enumeration(r,"review_status",ReviewStatus.class),
                    text(r,"owner_username"),
                    reference(r,"portfolio.requirement-version","current_version_id"),
                    reference(r,"portfolio.analysis-snapshot","current_snapshot_id"),
                    instant(r,"created_at"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"requirement-version",profile,query(scope,"select t.id,t.scope_key,t.requirement_id,t.version_number,t.requirement_text,t.content_hash,t.change_reason,t.created_by,t.created_at,t.source_artifact_id,t.source_version_id,t.source_fragment_ids,t.section_ref,t.page_number,t.original_text from project_req_version t",scope.tenants("t.scope_key"),"exists (select 1 from project_requirement current_requirement where current_requirement.scope_key=t.scope_key and current_requirement.id=t.requirement_id and current_requirement.current_version_id=t.id)","t.id"),r -> {
            return new ProjectRequirementVersionRecord(reference(r,"portfolio.requirement-version","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.requirement","requirement_id"),
                    r.getInt("version_number"),
                    text(r,"requirement_text"),
                    text(r,"content_hash"),
                    scope.history() ? text(r,"change_reason") : null,
                    text(r,"created_by"),
                    instant(r,"created_at"),
                    reference(r,"application.source-artifact","source_artifact_id"),
                    reference(r,"application.source-version","source_version_id"),
                    fragmentReferences(text(r,"source_fragment_ids")),
                    text(r,"section_ref"),
                    number(r,"page_number"),
                    scope.history() ? text(r,"original_text") : null);
        });
        write(scope,sink,"analysis-job",profile,query(scope,"select t.id,t.scope_key,t.project_id,t.status,t.idempotency_key,t.provider,t.max_architecture_nodes,t.requested_by,t.workspace_id,t.created_at,t.started_at,t.completed_at,t.total_items,t.successful_items,t.partial_items,t.failed_items from req_analysis_job t",scope.tenants("t.scope_key"),"(t.status in ('PENDING','RUNNING') or exists (select 1 from req_analysis_snapshot snapshot join project_requirement current_requirement on current_requirement.id=snapshot.requirement_id and current_requirement.scope_key=snapshot.scope_key where snapshot.job_id=t.id and snapshot.scope_key=t.scope_key and current_requirement.current_snapshot_id=snapshot.id and current_requirement.current_version_id=snapshot.requirement_version_id))","t.id"),r -> {
            return new RequirementAnalysisJobRecord(reference(r,"portfolio.analysis-job","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.project","project_id"),
                    enumeration(r,"status",AnalysisStatus.class),
                    text(r,"idempotency_key"),
                    text(r,"provider"),
                    r.getInt("max_architecture_nodes"),
                    text(r,"requested_by"),
                    text(r,"workspace_id"),
                    instant(r,"created_at"),
                    instant(r,"started_at"),
                    instant(r,"completed_at"),
                    r.getInt("total_items"),
                    r.getInt("successful_items"),
                    r.getInt("partial_items"),
                    r.getInt("failed_items"));
        });
        write(scope,sink,"analysis-snapshot",profile,query(scope,"select t.id,t.scope_key,t.project_id,t.requirement_id,t.requirement_version_id,t.job_id,t.status,t.analysis_session_id,t.provider,t.model_name,t.prompt_fingerprint,t.taxonomy_fingerprint,t.workspace_id,t.branch_name,t.commit_sha,t.created_by,t.created_at,t.duration_ms,t.warning_count,t.analysis_payload,t.gap_payload,t.pattern_payload,t.recommendation_payload from req_analysis_snapshot t",scope.tenants("t.scope_key"),"exists (select 1 from project_requirement current_requirement where current_requirement.scope_key=t.scope_key and current_requirement.id=t.requirement_id and current_requirement.current_version_id=t.requirement_version_id and current_requirement.current_snapshot_id=t.id)","t.id"),r -> {
            return new RequirementAnalysisSnapshotRecord(reference(r,"portfolio.analysis-snapshot","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.project","project_id"),
                    reference(r,"portfolio.requirement","requirement_id"),
                    reference(r,"portfolio.requirement-version","requirement_version_id"),
                    reference(r,"portfolio.analysis-job","job_id"),
                    enumeration(r,"status",AnalysisStatus.class),
                    text(r,"analysis_session_id"),
                    text(r,"provider"),
                    text(r,"model_name"),
                    text(r,"prompt_fingerprint"),
                    text(r,"taxonomy_fingerprint"),
                    text(r,"workspace_id"),
                    text(r,"branch_name"),
                    text(r,"commit_sha"),
                    text(r,"created_by"),
                    instant(r,"created_at"),
                    r.getLong("duration_ms"),
                    r.getInt("warning_count"),
                    text(r,"analysis_payload"),
                    text(r,"gap_payload"),
                    text(r,"pattern_payload"),
                    text(r,"recommendation_payload"));
        });
        write(scope,sink,"analysis-item",profile,query(scope,"select t.id,t.scope_key,t.project_id,t.job_id,t.requirement_id,t.requirement_version_id,t.status,(case when "+historyOr(scope)+"exists (select 1 from project_requirement current_requirement where current_requirement.id=t.requirement_id and current_requirement.scope_key=t.scope_key and current_requirement.current_snapshot_id=t.snapshot_id and current_requirement.current_version_id=t.requirement_version_id) then t.snapshot_id else null end) as snapshot_id,t.attempt,t.started_at,t.completed_at from req_analysis_item t",scope.tenants("t.scope_key"),"exists (select 1 from project_requirement current_requirement join req_analysis_job job on job.id=t.job_id and job.scope_key=t.scope_key where current_requirement.id=t.requirement_id and current_requirement.scope_key=t.scope_key and current_requirement.current_version_id=t.requirement_version_id and (t.snapshot_id=current_requirement.current_snapshot_id or job.status in ('PENDING','RUNNING')))","t.id"),r -> {
            return new RequirementAnalysisJobItemRecord(reference(r,"portfolio.analysis-item","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.project","project_id"),
                    reference(r,"portfolio.analysis-job","job_id"),
                    reference(r,"portfolio.requirement","requirement_id"),
                    reference(r,"portfolio.requirement-version","requirement_version_id"),
                    enumeration(r,"status",AnalysisStatus.class),
                    reference(r,"portfolio.analysis-snapshot","snapshot_id"),
                    r.getInt("attempt"),
                    instant(r,"started_at"),
                    instant(r,"completed_at"));
        });
        write(scope,sink,"element-decision",profile,query(scope,"select t.id,t.scope_key,t.snapshot_id,t.node_code,t.node_title,t.taxonomy_root,t.direct_score,t.relevance,t.confidence,t.mapping_origin,t.hierarchy_path,t.presence_reason,t.selected_for_impact,t.review_status,t.action_status,t.action_evidence,t.decision_by,t.decision_at,t.decision_comment from req_element_mapping t",scope.tenants("t.scope_key"),"exists (select 1 from req_analysis_snapshot snapshot join project_requirement current_requirement on current_requirement.id=snapshot.requirement_id and current_requirement.scope_key=snapshot.scope_key where snapshot.id=t.snapshot_id and snapshot.scope_key=t.scope_key and current_requirement.current_snapshot_id=snapshot.id and current_requirement.current_version_id=snapshot.requirement_version_id)","t.id"),r -> {
            return new RequirementElementMappingRecord(reference(r,"portfolio.element-decision","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.analysis-snapshot","snapshot_id"),
                    text(r,"node_code"),
                    text(r,"node_title"),
                    text(r,"taxonomy_root"),
                    r.getInt("direct_score"),
                    r.getDouble("relevance"),
                    r.getDouble("confidence"),
                    enumeration(r,"mapping_origin",MappingOrigin.class),
                    text(r,"hierarchy_path"),
                    text(r,"presence_reason"),
                    r.getBoolean("selected_for_impact"),
                    enumeration(r,"review_status",ReviewStatus.class),
                    enumeration(r,"action_status",ActionStatus.class),
                    text(r,"action_evidence"),
                    text(r,"decision_by"),
                    instant(r,"decision_at"),
                    text(r,"decision_comment"));
        });
        write(scope,sink,"relation-decision",profile,query(scope,"select t.id,t.scope_key,t.snapshot_id,t.source_code,t.target_code,t.relation_type,t.relation_origin,t.relation_category,t.relevance,t.confidence,t.presence_reason,t.review_status,t.decision_by,t.decision_at,t.decision_comment from req_relation_mapping t",scope.tenants("t.scope_key"),"exists (select 1 from req_analysis_snapshot snapshot join project_requirement current_requirement on current_requirement.id=snapshot.requirement_id and current_requirement.scope_key=snapshot.scope_key where snapshot.id=t.snapshot_id and snapshot.scope_key=t.scope_key and current_requirement.current_snapshot_id=snapshot.id and current_requirement.current_version_id=snapshot.requirement_version_id)","t.id"),r -> {
            return new RequirementRelationMappingRecord(reference(r,"portfolio.relation-decision","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.analysis-snapshot","snapshot_id"),
                    text(r,"source_code"),
                    text(r,"target_code"),
                    text(r,"relation_type"),
                    text(r,"relation_origin"),
                    text(r,"relation_category"),
                    r.getDouble("relevance"),
                    r.getDouble("confidence"),
                    text(r,"presence_reason"),
                    enumeration(r,"review_status",ReviewStatus.class),
                    text(r,"decision_by"),
                    instant(r,"decision_at"),
                    text(r,"decision_comment"));
        });
        write(scope,sink,"solution",profile,query(scope,"select t.id,t.scope_key,t.repository_id,t.workspace_scope,t.branch_name,t.workspace_id,t.solution_key,t.title,t.description,t.solution_type,t.operating_model,t.lifecycle_status,t.maturity_level,t.owner_username,t.responsible_organization,t.cost_amount,t.cost_currency,t.risk_notes,t.lead_time_days,t.extension_attributes,t.created_at,t.updated_at from solution_definition t",scope.tenants("t.scope_key"),"1=1","t.id"),r -> {
            return new SolutionDefinitionRecord(reference(r,"portfolio.solution","id"),
                    text(r,"scope_key"),
                    text(r,"repository_id"),
                    text(r,"workspace_scope"),
                    text(r,"branch_name"),
                    text(r,"workspace_id"),
                    text(r,"solution_key"),
                    text(r,"title"),
                    text(r,"description"),
                    enumeration(r,"solution_type",SolutionType.class),
                    enumeration(r,"operating_model",OperatingModel.class),
                    enumeration(r,"lifecycle_status",LifecycleStatus.class),
                    r.getInt("maturity_level"),
                    text(r,"owner_username"),
                    text(r,"responsible_organization"),
                    decimal(r,"cost_amount"),
                    text(r,"cost_currency"),
                    text(r,"risk_notes"),
                    number(r,"lead_time_days"),
                    text(r,"extension_attributes"),
                    instant(r,"created_at"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"product",profile,query(scope,"select t.id,t.scope_key,t.repository_id,t.workspace_scope,t.branch_name,t.workspace_id,t.product_key,t.manufacturer,t.product_family,t.product_name,t.edition_version,t.product_status,t.end_of_support,t.license_model,t.operating_model,t.supported_platforms,t.security_features,t.compliance_features,t.cost_amount,t.cost_currency,t.cost_basis,t.source_reference,t.verified_at,t.created_by,t.created_at,t.updated_at from product_catalog t",scope.tenants("t.scope_key"),"1=1","t.id"),r -> {
            return new ProductCatalogEntryRecord(reference(r,"portfolio.product","id"),
                    text(r,"scope_key"),
                    text(r,"repository_id"),
                    text(r,"workspace_scope"),
                    text(r,"branch_name"),
                    text(r,"workspace_id"),
                    text(r,"product_key"),
                    text(r,"manufacturer"),
                    text(r,"product_family"),
                    text(r,"product_name"),
                    text(r,"edition_version"),
                    enumeration(r,"product_status",ProductStatus.class),
                    date(r,"end_of_support"),
                    text(r,"license_model"),
                    enumeration(r,"operating_model",OperatingModel.class),
                    text(r,"supported_platforms"),
                    text(r,"security_features"),
                    text(r,"compliance_features"),
                    decimal(r,"cost_amount"),
                    text(r,"cost_currency"),
                    text(r,"cost_basis"),
                    text(r,"source_reference"),
                    instant(r,"verified_at"),
                    text(r,"created_by"),
                    instant(r,"created_at"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"project-solution",profile,query(scope,"select parent.scope_key,solution.scope_key as solution_scope,t.id,t.project_id,t.solution_id,t.status,t.action_status,t.priority,t.rationale,t.created_by,t.created_at,t.updated_at from project_solution t join arch_project parent on parent.id=t.project_id join solution_definition solution on solution.id=t.solution_id and solution.scope_key=parent.scope_key",scope.tenants("parent.scope_key"),"1=1","t.id"),r -> {
            requireSameTenant(r, "solution_scope");
            return new ProjectSolutionRecord(reference(r,"portfolio.project-solution","id"),
                    reference(r,"portfolio.project","project_id"),
                    reference(r,"portfolio.solution","solution_id"),
                    enumeration(r,"status",ProjectSolutionStatus.class),
                    enumeration(r,"action_status",ActionStatus.class),
                    r.getInt("priority"),
                    text(r,"rationale"),
                    text(r,"created_by"),
                    instant(r,"created_at"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"product-coverage",profile,query(scope,"select parent.scope_key,t.id,t.product_id,t.node_code,t.coverage_percent,t.evidence,t.review_status,t.updated_by,t.updated_at from product_taxonomy t join product_catalog parent on parent.id=t.product_id",scope.tenants("parent.scope_key"),"1=1","t.id"),r -> {
            return new ProductTaxonomyCoverageRecord(reference(r,"portfolio.product-coverage","id"),
                    reference(r,"portfolio.product","product_id"),
                    text(r,"node_code"),
                    r.getInt("coverage_percent"),
                    text(r,"evidence"),
                    enumeration(r,"review_status",ReviewStatus.class),
                    text(r,"updated_by"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"solution-coverage",profile,query(scope,"select parent.scope_key,t.id,t.solution_id,t.node_code,t.coverage_percent,t.evidence,t.review_status,t.updated_by,t.updated_at from solution_taxonomy t join solution_definition parent on parent.id=t.solution_id",scope.tenants("parent.scope_key"),"1=1","t.id"),r -> {
            return new SolutionTaxonomyCoverageRecord(reference(r,"portfolio.solution-coverage","id"),
                    reference(r,"portfolio.solution","solution_id"),
                    text(r,"node_code"),
                    r.getInt("coverage_percent"),
                    text(r,"evidence"),
                    enumeration(r,"review_status",ReviewStatus.class),
                    text(r,"updated_by"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"requirement-solution",profile,query(scope,"select parent.scope_key,requirement.scope_key as requirement_scope,t.id,t.project_solution_id,t.requirement_id,(case when "+historyOr(scope)+"exists (select 1 from req_analysis_snapshot valid_snapshot join project_requirement req on req.id=valid_snapshot.requirement_id and req.scope_key=valid_snapshot.scope_key where valid_snapshot.id=t.snapshot_id and req.id=t.requirement_id and req.current_snapshot_id=valid_snapshot.id and req.current_version_id=valid_snapshot.requirement_version_id) then t.snapshot_id else null end) as snapshot_id,t.coverage_percent,t.solution_role,t.review_status,t.evidence,t.updated_by,t.updated_at from req_solution_link t join project_solution decision on decision.id=t.project_solution_id join arch_project parent on parent.id=decision.project_id join project_requirement requirement on requirement.id=t.requirement_id and requirement.project_id=parent.id and requirement.scope_key=parent.scope_key",scope.tenants("parent.scope_key"),"1=1","t.id"),r -> {
            requireSameTenant(r, "requirement_scope");
            return new RequirementSolutionLinkRecord(reference(r,"portfolio.requirement-solution","id"),
                    reference(r,"portfolio.project-solution","project_solution_id"),
                    reference(r,"portfolio.requirement","requirement_id"),
                    reference(r,"portfolio.analysis-snapshot","snapshot_id"),
                    r.getInt("coverage_percent"),
                    enumeration(r,"solution_role",RequirementSolutionRole.class),
                    enumeration(r,"review_status",ReviewStatus.class),
                    text(r,"evidence"),
                    text(r,"updated_by"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"solution-product",profile,query(scope,"select parent.scope_key,product.scope_key as product_scope,t.id,t.project_solution_id,t.product_id,t.coverage_percent,t.hard_exclusions,t.strengths,t.weaknesses,t.open_evidence,t.confidence,t.review_status,t.selection_status,t.updated_by,t.updated_at from solution_product t join project_solution decision on decision.id=t.project_solution_id join arch_project parent on parent.id=decision.project_id join product_catalog product on product.id=t.product_id and product.scope_key=parent.scope_key",scope.tenants("parent.scope_key"),"1=1","t.id"),r -> {
            requireSameTenant(r, "product_scope");
            return new SolutionProductCandidateRecord(reference(r,"portfolio.solution-product","id"),
                    reference(r,"portfolio.project-solution","project_solution_id"),
                    reference(r,"portfolio.product","product_id"),
                    r.getInt("coverage_percent"),
                    text(r,"hard_exclusions"),
                    text(r,"strengths"),
                    text(r,"weaknesses"),
                    text(r,"open_evidence"),
                    r.getDouble("confidence"),
                    enumeration(r,"review_status",ReviewStatus.class),
                    enumeration(r,"selection_status",ProductSelectionStatus.class),
                    text(r,"updated_by"),
                    instant(r,"updated_at"));
        });
        write(scope,sink,"conflict",profile,query(scope,"select parent.scope_key,ra.scope_key as requirement_a_scope,rb.scope_key as requirement_b_scope,t.id,t.project_id,t.requirement_a_id,t.requirement_b_id,t.conflict_type,t.status,t.fingerprint,t.title,t.evidence,t.confidence,t.resolution_note,t.detected_at,t.reviewed_by,t.reviewed_at from project_conflict t join arch_project parent on parent.id=t.project_id join project_requirement ra on ra.id=t.requirement_a_id and ra.project_id=parent.id and ra.scope_key=parent.scope_key join project_requirement rb on rb.id=t.requirement_b_id and rb.project_id=parent.id and rb.scope_key=parent.scope_key",scope.tenants("parent.scope_key"),"1=1","t.id"),r -> {
            requireSameTenant(r, "requirement_a_scope");
            requireSameTenant(r, "requirement_b_scope");
            return new ProjectConflictRecord(reference(r,"portfolio.conflict","id"),
                    reference(r,"portfolio.project","project_id"),
                    reference(r,"portfolio.requirement","requirement_a_id"),
                    reference(r,"portfolio.requirement","requirement_b_id"),
                    enumeration(r,"conflict_type",ConflictType.class),
                    enumeration(r,"status",ConflictStatus.class),
                    text(r,"fingerprint"),
                    text(r,"title"),
                    text(r,"evidence"),
                    r.getDouble("confidence"),
                    text(r,"resolution_note"),
                    instant(r,"detected_at"),
                    text(r,"reviewed_by"),
                    instant(r,"reviewed_at"));
        });
        write(scope,sink,"reformulation-proposal",profile,query(scope,"select t.id,t.scope_key,t.project_id,t.requirement_id,t.source_version_id,t.snapshot_id,t.baseline_payload,t.created_by,t.created_at,t.current_revision from reformulation_proposal t",scope.tenants("t.scope_key"),"exists (select 1 from project_requirement current_requirement where current_requirement.scope_key=t.scope_key and current_requirement.project_id=t.project_id and current_requirement.id=t.requirement_id and current_requirement.current_version_id=t.source_version_id and current_requirement.current_snapshot_id=t.snapshot_id)","t.id"),r -> {
            return new ReformulationProposalRecord(reference(r,"portfolio.reformulation-proposal","id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.project","project_id"),
                    reference(r,"portfolio.requirement","requirement_id"),
                    reference(r,"portfolio.requirement-version","source_version_id"),
                    reference(r,"portfolio.analysis-snapshot","snapshot_id"),
                    scope.history() ? text(r,"baseline_payload") : currentBaseline(text(r,"baseline_payload")),
                    text(r,"created_by"),
                    instant(r,"created_at"),
                    r.getLong("current_revision"));
        });
        write(scope,sink,"reformulation-revision",profile,query(scope,"select t.id,t.proposal_id,t.scope_key,t.revision_number,t.revision_payload from reformulation_revision t",scope.tenants("t.scope_key"),"exists (select 1 from reformulation_proposal proposal join project_requirement current_requirement on current_requirement.id=proposal.requirement_id and current_requirement.scope_key=proposal.scope_key and current_requirement.project_id=proposal.project_id where proposal.id=t.proposal_id and proposal.scope_key=t.scope_key and current_requirement.current_version_id=proposal.source_version_id and current_requirement.current_snapshot_id=proposal.snapshot_id) and exists (select 1 from reformulation_proposal proposal where proposal.id=t.proposal_id and proposal.scope_key=t.scope_key and proposal.current_revision=t.revision_number)","t.id"),r -> {
            return new ReformulationRevisionRecord(reference(r,"portfolio.reformulation-revision","id"),
                    reference(r,"portfolio.reformulation-proposal","proposal_id"),
                    text(r,"scope_key"),
                    r.getLong("revision_number"),
                    scope.history() ? text(r,"revision_payload") : currentRevision(text(r,"revision_payload")));
        });
        write(scope,sink,"reformulation-run",profile,query(scope,"select t.id,t.proposal_id,t.scope_key,t.run_payload,t.cancelled_by,t.cancelled_at,(select proposal.current_revision from reformulation_proposal proposal where proposal.id=t.proposal_id and proposal.scope_key=t.scope_key) as selected_revision from reformulation_run t",scope.tenants("t.scope_key"),"exists (select 1 from reformulation_proposal proposal join project_requirement current_requirement on current_requirement.id=proposal.requirement_id and current_requirement.scope_key=proposal.scope_key and current_requirement.project_id=proposal.project_id where proposal.id=t.proposal_id and proposal.scope_key=t.scope_key and current_requirement.current_version_id=proposal.source_version_id and current_requirement.current_snapshot_id=proposal.snapshot_id)","t.id"),r -> {
            String runId = text(r,"id");
            if (!scope.history() && !currentRun(text(r,"run_payload"),r.getLong("selected_revision"))) return null;
            if (includedRuns.size() >= 100_000) throw new IOException("Reformulation run selection limit exceeded");
            includedRuns.add(runId);
            return new ReformulationRunRecord(reference(r,"portfolio.reformulation-run","id"),
                    reference(r,"portfolio.reformulation-proposal","proposal_id"),
                    text(r,"scope_key"),
                    text(r,"run_payload"),
                    text(r,"cancelled_by"),
                    instant(r,"cancelled_at"));
        });
        write(scope,sink,"reformulation-checkpoint",profile,query(scope,"select t.id,t.proposal_id,t.scope_key,t.run_id,t.task_kind,t.input_fingerprint,t.result_payload,t.created_at from reformulation_node_checkpoint t",scope.tenants("t.scope_key"),"exists (select 1 from reformulation_proposal proposal join project_requirement current_requirement on current_requirement.id=proposal.requirement_id and current_requirement.scope_key=proposal.scope_key and current_requirement.project_id=proposal.project_id where proposal.id=t.proposal_id and proposal.scope_key=t.scope_key and current_requirement.current_version_id=proposal.source_version_id and current_requirement.current_snapshot_id=proposal.snapshot_id)","t.id"),r -> {
            if (!scope.history() && !includedRuns.contains(text(r,"run_id"))) return null;
            return new ReformulationNodeCheckpointRecord(reference(r,"portfolio.reformulation-checkpoint","id"),
                    reference(r,"portfolio.reformulation-proposal","proposal_id"),
                    text(r,"scope_key"),
                    reference(r,"portfolio.reformulation-run","run_id"),
                    text(r,"task_kind"),
                    text(r,"input_fingerprint"),
                    text(r,"result_payload"),
                    instant(r,"created_at"));
        });
        write(scope,sink,"reformulation-usage-session",profile,query(scope,"select t.run_id,t.proposal_id,t.scope_key,t.created_at,t.from_first_attempt,t.schema_version from reformulation_usage_session t",scope.tenants("t.scope_key"),"exists (select 1 from reformulation_proposal proposal join project_requirement current_requirement on current_requirement.id=proposal.requirement_id and current_requirement.scope_key=proposal.scope_key and current_requirement.project_id=proposal.project_id where proposal.id=t.proposal_id and proposal.scope_key=t.scope_key and current_requirement.current_version_id=proposal.source_version_id and current_requirement.current_snapshot_id=proposal.snapshot_id)","t.run_id"),r -> {
            if (!scope.history() && !includedRuns.contains(text(r,"run_id"))) return null;
            return new ReformulationUsageSessionRecord(reference(r,"portfolio.reformulation-usage-session","run_id"),
                    reference(r,"portfolio.reformulation-run","run_id"),
                    reference(r,"portfolio.reformulation-proposal","proposal_id"),
                    text(r,"scope_key"),
                    instant(r,"created_at"),
                    r.getBoolean("from_first_attempt"),
                    r.getInt("schema_version"));
        });
        write(scope,sink,"reformulation-usage-attempt",profile,query(scope,"select run.scope_key,t.id,t.run_id,t.invocation_id,t.provider,t.source_kind,t.retry_index,t.started_at,t.completed_at,t.status_code,t.outcome,t.duration_millis,t.input_tokens,t.output_tokens,t.total_tokens,t.cached_input_tokens,t.reasoning_tokens,t.invalid_usage from reformulation_usage_attempt t join reformulation_run run on run.id=t.run_id",scope.tenants("run.scope_key"),"1=1","t.id"),r -> {
            if (!scope.history() && !includedRuns.contains(text(r,"run_id"))) return null;
            return new ReformulationUsageAttemptRecord(reference(r,"portfolio.reformulation-usage-attempt","id"),
                    reference(r,"portfolio.reformulation-run","run_id"),
                    text(r,"invocation_id"),
                    text(r,"provider"),
                    text(r,"source_kind"),
                    r.getInt("retry_index"),
                    instant(r,"started_at"),
                    instant(r,"completed_at"),
                    number(r,"status_code"),
                    text(r,"outcome"),
                    number(r,"duration_millis"),
                    number(r,"input_tokens"),
                    number(r,"output_tokens"),
                    number(r,"total_tokens"),
                    number(r,"cached_input_tokens"),
                    number(r,"reasoning_tokens"),
                    (Boolean) r.getObject("invalid_usage"));
        });
        write(scope,sink,"reformulation-preview",profile,query(scope,"select t.id,t.proposal_id,t.scope_key,t.content_hash,t.preview_payload,t.created_at from reformulation_adoption_preview t",scope.tenants("t.scope_key"),"1=0","t.id"),r -> {
            return new ReformulationAdoptionPreviewRecord(reference(r,"portfolio.reformulation-preview","id"),
                    reference(r,"portfolio.reformulation-proposal","proposal_id"),
                    text(r,"scope_key"),
                    text(r,"content_hash"),
                    text(r,"preview_payload"),
                    instant(r,"created_at"));
        });
        write(scope,sink,"reformulation-adoption",profile,query(scope,"select t.id,t.proposal_id,t.preview_id,t.scope_key,t.command_hash,t.requirement_id,t.target_version_id,t.receipt_payload,t.created_at from reformulation_adoption t",scope.tenants("t.scope_key"),"1=0","t.id"),r -> {
            return new ReformulationAdoptionRecord(reference(r,"portfolio.reformulation-adoption","id"),
                    reference(r,"portfolio.reformulation-proposal","proposal_id"),
                    reference(r,"portfolio.reformulation-preview","preview_id"),
                    text(r,"scope_key"),
                    text(r,"command_hash"),
                    reference(r,"portfolio.requirement","requirement_id"),
                    reference(r,"portfolio.requirement-version","target_version_id"),
                    text(r,"receipt_payload"),
                    instant(r,"created_at"));
        });
        write(scope,sink,"reformulation-evidence",profile,query(scope,"select t.id,t.scope_key,t.project_key,t.requirement_key,t.target_version_number,t.schema_version,t.evidence_hash,t.target_text_hash,t.evidence_payload,t.created_at from reformulation_portable_evidence t",scope.tenants("t.scope_key"),"1=0","t.id"),r -> {
            return new ReformulationPortableEvidenceRecord(reference(r,"portfolio.reformulation-evidence","id"),
                    text(r,"scope_key"),
                    text(r,"project_key"),
                    text(r,"requirement_key"),
                    r.getInt("target_version_number"),
                    text(r,"schema_version"),
                    text(r,"evidence_hash"),
                    text(r,"target_text_hash"),
                    text(r,"evidence_payload"),
                    instant(r,"created_at"));
        });
    }
    private void validateScopeClosure(BackupRowScope scope) throws IOException {
        var tenant=scope.tenants("parent.scope_key");
        var checks=List.of(
                "from project_solution decision join arch_project parent on parent.id=decision.project_id left join solution_definition solution on solution.id=decision.solution_id where %s and (solution.id is null or solution.scope_key<>parent.scope_key)",
                "from solution_product candidate join project_solution decision on decision.id=candidate.project_solution_id join arch_project parent on parent.id=decision.project_id left join product_catalog product on product.id=candidate.product_id where %s and (product.id is null or product.scope_key<>parent.scope_key)",
                "from req_solution_link link join project_solution decision on decision.id=link.project_solution_id join arch_project parent on parent.id=decision.project_id left join project_requirement requirement on requirement.id=link.requirement_id where %s and (requirement.id is null or requirement.project_id<>parent.id or requirement.scope_key<>parent.scope_key)",
                "from project_conflict conflict join arch_project parent on parent.id=conflict.project_id left join project_requirement ra on ra.id=conflict.requirement_a_id left join project_requirement rb on rb.id=conflict.requirement_b_id where %s and (ra.id is null or rb.id is null or ra.project_id<>parent.id or rb.project_id<>parent.id or ra.scope_key<>parent.scope_key or rb.scope_key<>parent.scope_key)");
        for (String check:checks) rows.requireEmpty(new Query("select 1 "+check.formatted(tenant.sql()),tenant.parameters()));
        var requirementTenant=scope.tenants("r.scope_key");
        rows.requireEmpty(new Query("select 1 from project_requirement r where "+requirementTenant.sql()
                +" and r.current_version_id is not null and not exists (select 1 from project_req_version v where v.id=r.current_version_id and v.requirement_id=r.id and v.scope_key=r.scope_key)",requirementTenant.parameters()));
    }
    private static String historyOr(BackupRowScope scope) { return scope.history() ? "1=1 or " : ""; }
    private <T extends Record> void write(BackupRowScope scope, ComponentSink sink, String kind, BackupProfile profile,
                                         Query query, PortableRows.Mapper<T> mapper) throws IOException {
        rows.write(sink, "portfolio", kind, profile, query, scoped(scope, mapper));
    }
    /** SQL is only the first filter: database collations cannot widen the captured tenant. */
    private static <T extends Record> PortableRows.Mapper<T> scoped(BackupRowScope scope, PortableRows.Mapper<T> mapper) {
        return row -> {
            String tenant = text(row, "scope_key");
            boolean included = false;
            try { included = tenant != null && scope.includesTenant(tenant); }
            catch (IllegalArgumentException malformed) { /* Source identity details do not enter capture errors. */ }
            if (!included) throw new IOException("Portable selection contains an invalid or different portfolio tenant");
            return mapper.read(row);
        };
    }
    private static void requireSameTenant(ResultSet row, String linkedColumn) throws SQLException, IOException {
        if (!Objects.equals(text(row, "scope_key"), text(row, linkedColumn)))
            throw new IOException("Portable selection has a missing or cross-tenant dependency");
    }
    private static Query query(BackupRowScope scope,String select,Query tenant,String current,String order) {
        if (scope.selectedVersion()) return null;
        return new Query(select+" where "+tenant.sql()+(scope.history()?"":" and ("+current+")")+" order by "+order,tenant.parameters());
    }
    private static <E extends Enum<E>> E enumeration(ResultSet row,String column,Class<E> type) throws SQLException,IOException {
        String value=text(row,column); return value==null?null:Enum.valueOf(type,value);
    }
    private static String date(ResultSet row,String column) throws SQLException { var value=row.getDate(column); return value==null?null:value.toLocalDate().toString(); }
    private static String decimal(ResultSet row,String column) throws SQLException { var value=row.getBigDecimal(column); return value==null?null:value.toPlainString(); }
    private static List<SourceRecordId> fragmentReferences(String source) throws IOException {
        if (source==null) return List.of(); var array=json().readTree(source);
        if (!array.isArray() || array.size()>10000) throw new IOException("Invalid source fragment references");
        var result=new ArrayList<SourceRecordId>();
        for (var id:array) { if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue()<1) throw new IOException("Invalid source fragment reference"); result.add(new SourceRecordId("application.source-fragment",id.asText())); }
        return List.copyOf(result);
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode object(String payload) throws IOException {
        var node=json().readTree(payload); if (node==null || !node.isObject()) throw new IOException("Invalid reformulation document");
        return (com.fasterxml.jackson.databind.node.ObjectNode) node;
    }
    private static String currentBaseline(String payload) throws IOException {
        var node=object(payload); node.putObject("frozenContext");
        return json().writeValueAsString(node);
    }
    private static String currentRevision(String payload) throws IOException {
        var node=object(payload); node.putNull("predecessor"); node.putNull("variantOrigin");
        return json().writeValueAsString(node);
    }
    private static boolean currentRun(String payload,long revision) throws IOException {
        var node=object(payload); return node.path("sourceRevision").isIntegralNumber() && node.path("sourceRevision").longValue()==revision;
    }
    public record ProjectRecord(SourceRecordId sourceId, String tenant, String repositoryId, String workspaceScope, String branchName, String workspaceId, String ownerUsername, String projectKey, String title, String description, ProjectStatus status, String targetArchitecture, String targetDate, String budgetAmount, String budgetCurrency, String createdAt, String updatedAt) { }
    public record ProjectRequirementRecord(SourceRecordId sourceId, String tenant, SourceRecordId projectId, String requirementKey, String title, RequirementStatus status, int priority, Criticality criticality, RequirementType requirementType, ReviewStatus reviewStatus, String ownerUsername, SourceRecordId currentVersionId, SourceRecordId currentAnalysisSnapshotId, String createdAt, String updatedAt) { }
    public record ProjectRequirementVersionRecord(SourceRecordId sourceId, String tenant, SourceRecordId requirementId, int versionNumber, String text, String contentHash, String changeReason, String createdBy, String createdAt, SourceRecordId sourceArtifactId, SourceRecordId sourceVersionId, List<SourceRecordId> sourceFragments, String sectionReference, Long pageNumber, String originalText) { }
    public record RequirementAnalysisJobRecord(SourceRecordId sourceId, String tenant, SourceRecordId projectId, AnalysisStatus status, String idempotencyKey, String provider, int maxArchitectureNodes, String requestedBy, String workspaceId, String createdAt, String startedAt, String completedAt, int totalItems, int successfulItems, int partialItems, int failedItems) { }
    public record RequirementAnalysisSnapshotRecord(SourceRecordId sourceId, String tenant, SourceRecordId projectId, SourceRecordId requirementId, SourceRecordId requirementVersionId, SourceRecordId jobId, AnalysisStatus status, String analysisSessionId, String provider, String modelName, String promptFingerprint, String taxonomyFingerprint, String workspaceId, String branchName, String commitSha, String createdBy, String createdAt, long durationMs, int warningCount, String analysisPayload, String gapAnalysisPayload, String patternDetectionPayload, String recommendationPayload) { }
    public record RequirementAnalysisJobItemRecord(SourceRecordId sourceId, String tenant, SourceRecordId projectId, SourceRecordId jobId, SourceRecordId requirementId, SourceRecordId requirementVersionId, AnalysisStatus status, SourceRecordId snapshotId, int attempt, String startedAt, String completedAt) { }
    public record RequirementElementMappingRecord(SourceRecordId sourceId, String tenant, SourceRecordId snapshotId, String nodeCode, String nodeTitle, String taxonomyRoot, int directScore, double relevance, double confidence, MappingOrigin mappingOrigin, String hierarchyPath, String presenceReason, boolean selectedForImpact, ReviewStatus reviewStatus, ActionStatus actionStatus, String actionEvidence, String decisionBy, String decisionAt, String decisionComment) { }
    public record RequirementRelationMappingRecord(SourceRecordId sourceId, String tenant, SourceRecordId snapshotId, String sourceCode, String targetCode, String relationType, String relationOrigin, String relationCategory, double relevance, double confidence, String presenceReason, ReviewStatus reviewStatus, String decisionBy, String decisionAt, String decisionComment) { }
    public record SolutionDefinitionRecord(SourceRecordId sourceId, String tenant, String repositoryId, String workspaceScope, String branchName, String workspaceId, String solutionKey, String title, String description, SolutionType solutionType, OperatingModel operatingModel, LifecycleStatus lifecycleStatus, int maturityLevel, String ownerUsername, String responsibleOrganization, String costAmount, String costCurrency, String riskNotes, Long leadTimeDays, String extensionAttributesJson, String createdAt, String updatedAt) { }
    public record ProductCatalogEntryRecord(SourceRecordId sourceId, String tenant, String repositoryId, String workspaceScope, String branchName, String workspaceId, String productKey, String manufacturer, String productFamily, String productName, String editionVersion, ProductStatus productStatus, String endOfSupport, String licenseModel, OperatingModel operatingModel, String supportedPlatforms, String securityFeatures, String complianceFeatures, String costAmount, String costCurrency, String costBasis, String sourceReference, String verifiedAt, String createdBy, String createdAt, String updatedAt) { }
    public record ProjectSolutionRecord(SourceRecordId sourceId, SourceRecordId projectId, SourceRecordId solutionId, ProjectSolutionStatus status, ActionStatus actionStatus, int priority, String rationale, String createdBy, String createdAt, String updatedAt) { }
    public record ProductTaxonomyCoverageRecord(SourceRecordId sourceId, SourceRecordId productId, String nodeCode, int coveragePercent, String evidence, ReviewStatus reviewStatus, String updatedBy, String updatedAt) { }
    public record SolutionTaxonomyCoverageRecord(SourceRecordId sourceId, SourceRecordId solutionId, String nodeCode, int coveragePercent, String evidence, ReviewStatus reviewStatus, String updatedBy, String updatedAt) { }
    public record RequirementSolutionLinkRecord(SourceRecordId sourceId, SourceRecordId projectSolutionId, SourceRecordId requirementId, SourceRecordId snapshotId, int coveragePercent, RequirementSolutionRole role, ReviewStatus reviewStatus, String evidence, String updatedBy, String updatedAt) { }
    public record SolutionProductCandidateRecord(SourceRecordId sourceId, SourceRecordId projectSolutionId, SourceRecordId productId, int coveragePercent, String hardExclusions, String strengths, String weaknesses, String openEvidence, double confidence, ReviewStatus reviewStatus, ProductSelectionStatus selectionStatus, String updatedBy, String updatedAt) { }
    public record ProjectConflictRecord(SourceRecordId sourceId, SourceRecordId projectId, SourceRecordId requirementAId, SourceRecordId requirementBId, ConflictType conflictType, ConflictStatus status, String fingerprint, String title, String evidence, double confidence, String resolutionNote, String detectedAt, String reviewedBy, String reviewedAt) { }
    public record ReformulationProposalRecord(SourceRecordId sourceId, String tenant, SourceRecordId projectId, SourceRecordId requirementId, SourceRecordId sourceVersionId, SourceRecordId snapshotId, String baselinePayload, String createdBy, String createdAt, long currentRevision) { }
    public record ReformulationRevisionRecord(SourceRecordId sourceId, SourceRecordId proposalId, String tenant, long number, String payload) { }
    public record ReformulationRunRecord(SourceRecordId sourceId, SourceRecordId proposalId, String tenant, String payload, String cancelledBy, String cancelledAt) { }
    public record ReformulationNodeCheckpointRecord(SourceRecordId sourceId, SourceRecordId proposalId, String tenant, SourceRecordId runId, String taskKind, String inputFingerprint, String resultPayload, String createdAt) { }
    public record ReformulationUsageSessionRecord(SourceRecordId sourceId, SourceRecordId runId, SourceRecordId proposalId, String tenant, String createdAt, boolean fromFirstAttempt, int schemaVersion) { }
    public record ReformulationUsageAttemptRecord(SourceRecordId sourceId, SourceRecordId runId, String invocationId, String provider, String source, int retryIndex, String startedAt, String completedAt, Long statusCode, String outcome, Long durationMillis, Long inputTokens, Long outputTokens, Long totalTokens, Long cachedInputTokens, Long reasoningTokens, Boolean invalidUsage) { }
    public record ReformulationAdoptionPreviewRecord(SourceRecordId sourceId, SourceRecordId proposalId, String tenant, String contentHash, String payload, String createdAt) { }
    public record ReformulationAdoptionRecord(SourceRecordId sourceId, SourceRecordId proposalId, SourceRecordId previewId, String tenant, String commandHash, SourceRecordId requirementId, SourceRecordId targetVersionId, String payload, String createdAt) { }
    public record ReformulationPortableEvidenceRecord(SourceRecordId sourceId, String tenant, String projectKey, String requirementKey, int targetVersionNumber, String schemaVersion, String evidenceHash, String targetTextHash, String payload, String createdAt) { }
}
