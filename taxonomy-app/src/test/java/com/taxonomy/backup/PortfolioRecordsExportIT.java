package com.taxonomy.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.taxonomy.exchange.backup.PortableRows;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.portfolio.reformulation.*;
import com.taxonomy.workspace.model.RepositoryTenantIdentity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;

import static com.taxonomy.backup.CurrentStateExportIT.*;
import static com.taxonomy.portfolio.model.PortfolioTypes.*;
import static org.assertj.core.api.Assertions.*;

/** Actual persisted relationships, including historical and foreign records with the same business keys. */
class PortfolioRecordsExportIT {
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
