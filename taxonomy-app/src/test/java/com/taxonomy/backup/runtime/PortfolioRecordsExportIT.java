package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.reformulation.*;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

import static com.taxonomy.backup.runtime.CurrentStateExportIT.*;
import static com.taxonomy.portfolio.model.PortfolioTypes.*;
import static org.assertj.core.api.Assertions.*;

/** Actual persisted relationships, including historical and foreign records with the same business keys. */
class PortfolioRecordsExportIT {
    @Test void portableJobsRetainTheExactProviderArtifactAndConfiguration() throws Exception {
        try(var fixture=new Fixture()) {
            graph(fixture,"repo-a","private-a","ALICE");
            fixture.jdbc.update("update req_analysis_job set provider=?,provider_plugin_id=?,provider_plugin_version=?,provider_plugin_sha256=?,provider_config_revision=?",
                    "P".repeat(128),"example.provider","1.0.0","a".repeat(64),"b".repeat(64));
            var contents=new Contents();
            new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.CURRENT_STATE,new BackupScope.Workspace("repo-a","private-a")),contents);
            assertThat(one(contents,"analysis-job").path("provider").asText()).isEqualTo("P".repeat(128));
            var binding=one(contents,"analysis-job").path("providerBinding");
            assertThat(binding.path("plugin").path("artifactSha256").asText()).isEqualTo("a".repeat(64));
            assertThat(binding.path("configurationRevision").asText()).isEqualTo("b".repeat(64));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"arch_project", "project_requirement", "project_req_version",
            "req_analysis_job", "req_analysis_snapshot", "req_analysis_item", "req_element_mapping", "req_relation_mapping",
            "solution_definition", "product_catalog", "reformulation_proposal", "reformulation_revision", "reformulation_run",
            "reformulation_node_checkpoint", "reformulation_usage_session", "reformulation_adoption_preview",
            "reformulation_adoption", "reformulation_portable_evidence"})
    void caseInsensitiveSqlCannotExportRowsOutsideTheExactCapturedTenant(String table) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            String selected = tenant("private-a", "draft");
            fixture.jdbc.update("update " + table + " set scope_key=?", tenant("PRIVATE-A", "draft"));
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + table + " where scope_key like ?", Integer.class, selected))
                    .as("SQL really includes the differently spelled workspace").isPositive();
            assertThatThrownBy(() -> new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Workspace("repo-a", "private-a")), new Contents())).isInstanceOf(java.io.IOException.class);
        }
    }

    @ParameterizedTest @CsvSource({"solution,CURRENT_STATE", "solution,INSTALLATION_FULL",
            "product,CURRENT_STATE", "product,INSTALLATION_FULL", "requirement,CURRENT_STATE", "requirement,INSTALLATION_FULL",
            "conflict,CURRENT_STATE", "conflict,INSTALLATION_FULL", "current-version,CURRENT_STATE", "current-version,INSTALLATION_FULL"})
    void tenantClosureRequiresTheExactBranchEvenInsideAnAuthorizedWorkspace(String link, BackupProfile profile) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            String alias = tenant("private-a", "DRAFT");
            String join = switch (link) {
                case "solution" -> {
                    fixture.jdbc.update("update solution_definition set scope_key=?", alias);
                    yield "project_solution d join arch_project p on p.id=d.project_id join solution_definition c on c.id=d.solution_id and c.scope_key=p.scope_key";
                }
                case "product" -> {
                    fixture.jdbc.update("update product_catalog set scope_key=?", alias);
                    yield "solution_product d join project_solution s on s.id=d.project_solution_id join arch_project p on p.id=s.project_id join product_catalog c on c.id=d.product_id and c.scope_key=p.scope_key";
                }
                case "requirement" -> {
                    fixture.jdbc.update("update project_requirement set scope_key=? where requirement_key='R-SAME'", alias);
                    fixture.jdbc.update("update project_req_version set scope_key=?", alias);
                    yield "req_solution_link d join project_solution s on s.id=d.project_solution_id join arch_project p on p.id=s.project_id join project_requirement c on c.id=d.requirement_id and c.project_id=p.id and c.scope_key=p.scope_key";
                }
                case "conflict" -> {
                    fixture.jdbc.update("update project_requirement set scope_key=? where requirement_key='R-CONFLICT'", alias);
                    yield "project_conflict d join arch_project p on p.id=d.project_id join project_requirement c on c.id=d.requirement_b_id and c.project_id=p.id and c.scope_key=p.scope_key";
                }
                default -> {
                    fixture.jdbc.update("update project_req_version set scope_key=? where version_number=2", alias);
                    yield "project_requirement p join project_req_version c on c.id=p.current_version_id and c.requirement_id=p.id and c.scope_key=p.scope_key";
                }
            };
            assertThat(fixture.jdbc.queryForObject("select count(*) from " + join, Integer.class)).isEqualTo(1);
            BackupScope scope = profile.isInstallation() ? new BackupScope.Installation() : new BackupScope.Workspace("repo-a", "private-a");
            assertThatThrownBy(() -> new PortfolioBackupContributor(fixture.database).write(snapshot(profile, scope), new Contents()))
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @ParameterizedTest @CsvSource({"CURRENT_STATE,true", "CURRENT_STATE,false", "REPOSITORY_HISTORY,true", "REPOSITORY_HISTORY,false"})
    void sourceDiscoveryRejectsBothForeignTenantsAndCaseAliasedRequirementBranches(BackupProfile profile, boolean foreignTenant) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            fixture.requirement("repo-a", "private-a", "OLD", "CURRENT");
            fixture.jdbc.update("update project_req_version set source_artifact_id=11,source_version_id=12,source_fragment_ids='[13]'");
            String selected = tenant("private-a", "draft");
            var contributor = new PortfolioBackupContributor(fixture.database);
            var snapshot = snapshot(profile, new BackupScope.Workspace("repo-a", "private-a"));
            if (foreignTenant) {
                fixture.jdbc.update("update project_req_version set scope_key=?", tenant("PRIVATE-A", "draft"));
                assertThat(fixture.jdbc.queryForObject("select count(*) from project_req_version where scope_key like ?", Integer.class, selected)).isEqualTo(2);
            } else {
                fixture.jdbc.update("update project_requirement set scope_key=?", tenant("private-a", "DRAFT"));
                assertThat(fixture.jdbc.queryForObject("select count(*) from project_req_version v join project_requirement r on r.id=v.requirement_id and r.scope_key=v.scope_key", Integer.class)).isEqualTo(2);
            }
            assertThatThrownBy(() -> contributor.sourceReferences(snapshot)).isInstanceOf(java.io.IOException.class);
        }
    }

    @Test void malformedSelectedTenantFailsWithASanitizedCaptureError() throws Exception {
        try (var fixture = new Fixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            fixture.jdbc.update("update reformulation_portable_evidence set scope_key=?", tenant("private-a", "draft") + "TRAILING");
            assertThatThrownBy(() -> new PortfolioBackupContributor(fixture.database).write(snapshot(BackupProfile.REPOSITORY_HISTORY,
                    new BackupScope.Workspace("repo-a", "private-a")), new Contents()))
                    .isInstanceOf(java.io.IOException.class).hasMessageNotContaining("TRAILING");
        }
    }

    @ParameterizedTest @CsvSource({"req_analysis_snapshot,PRIVATE-A,draft", "arch_project,private-a,DRAFT",
            "project_requirement,private-a,DRAFT", "project_req_version,private-a,DRAFT"})
    void analysisDiscoveryCannotBorrowCaseAliasedTenantEvidence(String table, String workspace, String branch) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            fixture.requirement("repo-a", "private-a", "OLD", "CURRENT");
            fixture.jdbc.update("update " + table + " set scope_key=?", tenant(workspace, branch));
            assertThat(fixture.jdbc.queryForObject("select count(*) from req_analysis_snapshot s join arch_project p on p.id=s.project_id and p.scope_key=s.scope_key "
                    + "join project_requirement r on r.id=s.requirement_id and r.scope_key=s.scope_key join project_req_version v on v.id=s.requirement_version_id and v.scope_key=s.scope_key", Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> new PortfolioBackupContributor(fixture.database).analysisReferences(snapshot(BackupProfile.CURRENT_STATE,
                    new BackupScope.Workspace("repo-a", "private-a")))).isInstanceOf(java.io.IOException.class);
        }
    }

    @ParameterizedTest @EnumSource(value = BackupProfile.class, names = {"INSTALLATION_CURRENT", "INSTALLATION_FULL"})
    void exactTenantChecksPreserveValidInstallationGraphsAndProfileOmissions(BackupProfile profile) throws Exception {
        try (var fixture = caseInsensitiveFixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            graph(fixture, "repo-b", "private-b", "BOB");
            var contributor = new PortfolioBackupContributor(fixture.database);
            var snapshot = snapshot(profile, new BackupScope.Installation());
            var output = new Contents(); contributor.write(snapshot, output);
            assertThat(output.entries).hasSize(25);
            assertThat(records(output, "project")).hasSize(2);
            assertThat(output.text()).contains("CURRENT-ALICE", "CURRENT-BOB");
            assertThat(records(output, "requirement-version")).hasSize(profile.includesHistory() ? 4 : 2);
            assertThat(contributor.analysisReferences(snapshot)).hasSize(2);
            assertThat(contributor.sourceReferences(snapshot)).isEmpty();
            if (!profile.includesHistory()) assertThat(output.text()).doesNotContain("OLD-ALICE", "OLD-BOB", "FROZEN-ALICE", "FROZEN-BOB");
        }
    }

    private static Fixture caseInsensitiveFixture() {
        return new Fixture(configuration -> new org.springframework.jdbc.core.JdbcTemplate(
                (javax.sql.DataSource) configuration.getProperties().get("hibernate.connection.datasource"))
                .execute("SET DATABASE SQL IGNORECASE TRUE"));
    }

    private static String tenant(String workspace, String branch) {
        return new RepositoryTenantIdentity("repo-a", "WORKSPACE:" + workspace, branch).scopeKey();
    }

    @Test void exportsTheCompleteBusinessGraphWithStableReferencesAndExactTenantIsolation() throws Exception {
        try (var fixture = new Fixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            graph(fixture, "repo-a", "private-b", "FOREIGN");
            var contributor = new PortfolioBackupContributor(fixture.database);
            var scope = new BackupScope.Workspace("repo-a", "private-a");
            var current = new Contents();
            contributor.write(snapshot(BackupProfile.CURRENT_STATE, scope), current);
            assertThat(current.entries).hasSize(25);
            assertThat(current.text()).contains("CURRENT-ALICE", "Solution ALICE", "Product ALICE", "Coverage ALICE", "Conflict ALICE")
                    .doesNotContain("FOREIGN", "OLD-ALICE", "FROZEN-ALICE", "PREDECESSOR-ALICE", "VARIANT-ALICE", "OLD-RUN-ALICE",
                            "NO-REVISION-ALICE", "PREVIEW-ALICE", "ADOPTION-ALICE", "EVIDENCE-ALICE", "WORKER-OWNER");
            var product = one(current, "product");
            assertThat(new BigDecimal(product.path("costAmount").asText())).isEqualByComparingTo("1234.50");
            assertThat(product.path("endOfSupport").asText()).isEqualTo("2030-12-31");
            assertThat(product.path("verifiedAt").asText()).isEqualTo(NOW.toString());
            assertThat(one(current, "project-solution").path("solutionId")).isEqualTo(one(current, "solution").path("sourceId"));
            assertThat(one(current, "product-coverage").path("productId")).isEqualTo(product.path("sourceId"));
            assertThat(one(current, "solution-coverage").path("solutionId")).isEqualTo(one(current, "solution").path("sourceId"));
            assertThat(one(current, "solution-product").path("productId")).isEqualTo(product.path("sourceId"));
            assertThat(one(current, "analysis-item").path("snapshotId")).isEqualTo(one(current, "analysis-snapshot").path("sourceId"));
            assertThat(one(current, "relation-decision").path("snapshotId")).isEqualTo(one(current, "analysis-snapshot").path("sourceId"));
            JsonNode proposal = one(current, "reformulation-proposal");
            assertThat(PortableRows.json().readTree(proposal.path("baselinePayload").asText()).path("frozenContext").isEmpty()).isTrue();
            assertThat(one(current, "reformulation-revision").path("number").asLong()).isEqualTo(2);
            var runId = one(current, "reformulation-run").path("sourceId");
            for (String dataset : List.of("reformulation-checkpoint", "reformulation-usage-session", "reformulation-usage-attempt")) {
                assertThat(one(current, dataset).path("runId")).as(dataset).isEqualTo(runId);
            }
            assertThat(one(current, "reformulation-usage-attempt").path("totalTokens").asLong()).isEqualTo(17);
            for (String dataset : List.of("reformulation-preview", "reformulation-adoption", "reformulation-evidence")) {
                assertThat(records(current, dataset)).as(dataset).isEmpty();
            }

            var history = new Contents();
            contributor.write(snapshot(BackupProfile.REPOSITORY_HISTORY, scope), history);
            for (String path : history.entries.keySet()) {
                assertThat(new String(history.entries.get(path), StandardCharsets.UTF_8).lines().count()).as(path).isGreaterThan(1);
            }
            assertThat(history.text()).contains("OLD-ALICE", "FROZEN-ALICE", "PREDECESSOR-ALICE", "VARIANT-ALICE", "OLD-RUN-ALICE",
                    "NO-REVISION-ALICE", "PREVIEW-ALICE", "ADOPTION-ALICE", "EVIDENCE-ALICE").doesNotContain("FOREIGN", "WORKER-OWNER");
            assertThat(records(history, "requirement-version")).hasSize(2);
            assertThat(records(history, "reformulation-revision")).hasSize(2);
            for (String dataset : List.of("reformulation-run", "reformulation-checkpoint", "reformulation-usage-session", "reformulation-usage-attempt")) {
                assertThat(records(history, dataset)).as(dataset).hasSize(3);
            }
            assertThat(fixture.jdbc.queryForObject("select count(*) from reformulation_run", Integer.class)).isEqualTo(6);
            assertThat(fixture.jdbc.queryForList("select baseline_payload from reformulation_proposal", String.class))
                    .anyMatch(value -> value.contains("FROZEN-ALICE"));
            assertThat(contributor.componentId()).isEqualTo(new BackupComponentId("portfolio"));
            assertThat(contributor.schemaVersion()).isEqualTo(1);
            assertThat(contributor.categories()).hasSize(25).contains(ProductCatalogEntry.class.getName(), ReformulationUsageAttempt.class.getName());
            assertThat(contributor.omissions(BackupProfile.CURRENT_STATE)).anyMatch(value -> value.contains("original text"));
            assertThat(contributor.omissions(BackupProfile.REPOSITORY_HISTORY)).noneMatch(value -> value.contains("original text"));
            assertThat(contributor.omissions(BackupProfile.SELECTED_VERSION)).anyMatch(value -> value.contains("selected Git document"));
        }
    }

    @Test void rejectsMalformedCurrentDocumentsAndSourceReferencesInsteadOfDroppingThem() throws Exception {
        try (var fixture = new Fixture()) {
            graph(fixture, "repo-a", "private-a", "ALICE");
            var contributor = new PortfolioBackupContributor(fixture.database);
            var snapshot = snapshot(BackupProfile.CURRENT_STATE, new BackupScope.Workspace("repo-a", "private-a"));
            for (String table : List.of("reformulation_proposal", "reformulation_revision", "reformulation_run")) {
                String column = switch (table) { case "reformulation_proposal" -> "baseline_payload"; case "reformulation_revision" -> "revision_payload"; default -> "run_payload"; };
                var saved = fixture.jdbc.queryForList("select id," + column + " from " + table);
                fixture.jdbc.update("update " + table + " set " + column + "='[]'");
                assertThatThrownBy(() -> contributor.write(snapshot, new Contents())).as(table)
                        .isInstanceOf(java.io.IOException.class).hasMessageContaining("Invalid reformulation document");
                for (var row : saved) fixture.jdbc.update("update " + table + " set " + column + "=? where id=?", row.get(column.toUpperCase(Locale.ROOT)), row.get("ID"));
            }
            var selected = snapshot(BackupProfile.SELECTED_VERSION, new BackupScope.Workspace("repo-a", "private-a"));
            var output = new Contents();
            contributor.write(selected, output);
            assertThat(output.entries).hasSize(25);
            for (var entry : output.entries.entrySet()) {
                assertThat(new String(entry.getValue(), StandardCharsets.UTF_8).lines()).as(entry.getKey())
                        .hasSize(1).allMatch(line -> line.contains("\"schemaVersion\":1") && line.contains("SELECTED_VERSION"));
            }
            assertThat(contributor.analysisReferences(selected)).isEmpty();
            assertThat(contributor.sourceReferences(selected)).isEmpty();
            for (String invalid : List.of("{}", "[0]", "[\"not-an-id\"]", "[9223372036854775808]")) {
                fixture.jdbc.update("update project_req_version set source_fragment_ids=? where version_number=2", invalid);
                assertThatThrownBy(() -> contributor.sourceReferences(snapshot)).as(invalid).isInstanceOf(java.io.IOException.class);
            }
            fixture.jdbc.update("update project_req_version set source_artifact_id=11,source_version_id=12,source_fragment_ids='[13,14]' where version_number=2");
            var reference = contributor.sourceReferences(snapshot).getFirst();
            assertThat(reference.artifact()).isEqualTo(new SourceRecordId("application.source-artifact", "11"));
            assertThat(reference.version()).isEqualTo(new SourceRecordId("application.source-version", "12"));
            assertThat(reference.fragments()).containsExactly(new SourceRecordId("application.source-fragment", "13"), new SourceRecordId("application.source-fragment", "14"));
        }
    }

    private static JsonNode one(Contents contents, String dataset) {
        var values = records(contents, dataset);
        assertThat(values).as(dataset).hasSize(1);
        var record = values.getFirst();
        assertThat(record.path("sourceId").isObject()).as(dataset + " source identity").isTrue();
        assertThat(record.path("sourceId").path("kind").asText()).isEqualTo("portfolio." + dataset);
        assertThat(record.path("sourceId").path("value").isTextual()).isTrue();
        assertThat(record.path("sourceId").path("value").asText()).isNotBlank();
        return record;
    }

    private static List<JsonNode> records(Contents contents, String dataset) {
        byte[] content = contents.entries.get("data/portfolio/" + dataset + ".ndjson");
        assertThat(content).as(dataset).isNotNull();
        return new String(content, StandardCharsets.UTF_8).lines().skip(1).map(line -> {
            try { return PortableRows.json().readTree(line); }
            catch (java.io.IOException error) { throw new AssertionError(error); }
        }).toList();
    }

    private static void graph(Fixture fixture, String repository, String workspace, String marker) {
        fixture.requirement(repository, workspace, "OLD-" + marker, "CURRENT-" + marker);
        String scope = new RepositoryTenantIdentity(repository, "WORKSPACE:" + workspace, "draft").scopeKey();
        String proposalId = UUID.randomUUID().toString();
        var runs = new ArrayList<String>();
        try (var em = fixture.factory.createEntityManager()) {
            var tx = em.getTransaction(); tx.begin();
            var project = em.createQuery("from ArchitectureProject where scopeKey=:scope", ArchitectureProject.class).setParameter("scope", scope).getSingleResult();
            var requirement = em.createQuery("from ProjectRequirement where scopeKey=:scope", ProjectRequirement.class).setParameter("scope", scope).getSingleResult();
            var version = em.find(ProjectRequirementVersion.class, requirement.getCurrentVersionId());
            var analysis = em.find(RequirementAnalysisSnapshot.class, requirement.getCurrentAnalysisSnapshotId());
            var job = em.find(RequirementAnalysisJob.class, analysis.getJobId());
            var item = new RequirementAnalysisJobItem(job, requirement, version);
            item.markRunning(NOW); item.complete(AnalysisStatus.SUCCESS, analysis.getId(), NOW); em.persist(item);
            var relation = new RequirementRelationMapping(analysis, "N-SAME", "N-TARGET", "USES", "MANUAL", "STRUCTURAL", .8, .9, "Relation " + marker);
            relation.review(ReviewStatus.CONFIRMED, "alice", "Reviewed " + marker, NOW); em.persist(relation);
            var solution = new SolutionDefinition(scope, workspace, "S-SAME", "Solution " + marker, "Description " + marker,
                    SolutionType.APPLICATION, OperatingModel.PRIVATE_CLOUD, LifecycleStatus.ACTIVE, 3, "alice", "Organization", NOW);
            em.persist(solution); em.flush();
            var product = new ProductCatalogEntry(scope, workspace, "PRODUCT-SAME", "Vendor", "Family", "Product " + marker, "1.0",
                    ProductStatus.ACTIVE, LocalDate.of(2030, 12, 31), "License", OperatingModel.PRIVATE_CLOUD, "Linux", "Security", "Compliance",
                    new BigDecimal("1234.50"), "EUR", "year", "source-reference", NOW, "alice", NOW);
            em.persist(product); em.flush();
            var decision = new ProjectSolution(project, solution, ProjectSolutionStatus.SELECTED, ActionStatus.REUSE, 50, "Decision " + marker, "alice", NOW);
            em.persist(decision); em.flush();
            em.persist(new ProductTaxonomyCoverage(product, "N-SAME", 75, "Coverage " + marker, ReviewStatus.CONFIRMED, "alice", NOW));
            em.persist(new SolutionTaxonomyCoverage(solution, "N-SAME", 80, "Coverage " + marker, ReviewStatus.CONFIRMED, "alice", NOW));
            em.persist(new RequirementSolutionLink(decision, requirement, analysis.getId(), 80, RequirementSolutionRole.USES, ReviewStatus.CONFIRMED, "Link " + marker, "alice", NOW));
            em.persist(new SolutionProductCandidate(decision, product, 75, "Exclusions", "Strengths", "Weaknesses", "Evidence", .9,
                    ReviewStatus.CONFIRMED, ProductSelectionStatus.SELECTED, "alice", NOW));
            var second = new ProjectRequirement(project, "R-CONFLICT", "Other requirement " + marker, RequirementStatus.DRAFT, 20,
                    Criticality.HIGH, RequirementType.SECURITY, ReviewStatus.PROPOSED, "alice", NOW);
            em.persist(second); em.flush();
            var conflict = new ProjectConflict(project, requirement, second, ConflictType.HOSTING, "c".repeat(64), "Conflict " + marker, "Conflict evidence", .85, NOW);
            conflict.review(ConflictStatus.RESOLVED, "Resolution " + marker, "alice", NOW); em.persist(conflict);
            var proposal = new ReformulationProposal(proposalId, scope, project.getId(), requirement.getId(), version.getId(), analysis.getId(),
                    "{\"currentText\":\"CURRENT-" + marker + "\",\"frozenContext\":{\"text\":\"FROZEN-" + marker + "\"}}", "alice", NOW);
            proposal.advanceRevision(); em.persist(proposal); em.flush();
            em.persist(new ReformulationRevision(proposalId, scope, 1, "{\"text\":\"OLD-" + marker + "\"}"));
            em.persist(new ReformulationRevision(proposalId, scope, 2, "{\"text\":\"CURRENT-" + marker + "\",\"predecessor\":\"PREDECESSOR-" + marker + "\",\"variantOrigin\":\"VARIANT-" + marker + "\"}"));
            for (String payload : List.of("{\"sourceRevision\":2,\"text\":\"CURRENT-" + marker + "\"}",
                    "{\"sourceRevision\":1,\"text\":\"OLD-RUN-" + marker + "\"}", "{\"text\":\"NO-REVISION-" + marker + "\"}")) {
                String runId = UUID.randomUUID().toString(); runs.add(runId);
                em.persist(new ReformulationRun(runId, proposalId, scope, payload)); em.flush();
                em.persist(new ReformulationNodeCheckpoint(UUID.randomUUID().toString(), proposalId, scope, runId, "VALIDATION",
                        "d".repeat(64), payload, NOW));
            }
            String previewId = UUID.randomUUID().toString();
            em.persist(new ReformulationAdoptionPreview(previewId, proposalId, scope, "e".repeat(64), "{\"text\":\"PREVIEW-" + marker + "\"}", NOW)); em.flush();
            em.persist(new ReformulationAdoption(UUID.randomUUID().toString(), proposalId, previewId, scope, "f".repeat(64), requirement.getId(), version.getId(),
                    "{\"receipt\":\"ADOPTION-" + marker + "\"}", NOW));
            em.persist(new ReformulationPortableEvidence(UUID.randomUUID().toString(), scope, "P-SAME", "R-SAME", 2, "1", "1".repeat(64), "2".repeat(64),
                    "{\"text\":\"EVIDENCE-" + marker + "\"}", NOW));
            tx.commit();
        }
        // The usage constructors deliberately have package visibility; write the same constrained persistent schema.
        for (String runId : runs) {
            fixture.jdbc.update("insert into reformulation_usage_session(run_id,proposal_id,scope_key,created_at,from_first_attempt,schema_version) values(?,?,?,?,true,1)",
                    runId, proposalId, scope, Timestamp.from(NOW));
            fixture.jdbc.update("insert into reformulation_usage_attempt(id,run_id,owner_id,lease_epoch,invocation_id,provider,source_kind,retry_index,started_at,completed_at,status_code,outcome,duration_millis,input_tokens,output_tokens,total_tokens,cached_input_tokens,reasoning_tokens,invalid_usage,row_version) values(?,?,?,1,?,?,?,0,?,?,200,'SUCCESS',25,10,7,17,0,0,false,0)",
                    UUID.randomUUID().toString(), runId, "WORKER-OWNER", UUID.randomUUID().toString(), "LOCAL", "HTTP", Timestamp.from(NOW), Timestamp.from(NOW));
        }
    }
}
