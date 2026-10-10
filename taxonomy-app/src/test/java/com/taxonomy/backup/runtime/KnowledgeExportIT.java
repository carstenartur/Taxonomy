package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.catalog.backup.KnowledgeBackupContributor;
import com.taxonomy.catalog.model.*;
import com.taxonomy.relations.model.*;
import com.taxonomy.model.*;
import com.taxonomy.portfolio.backup.PortfolioBackupContributor;
import com.taxonomy.portfolio.model.*;
import com.taxonomy.workspace.service.SystemRepositoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.orm.jpa.hibernate.SpringBeanContainer;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;

import java.io.IOException;
import java.util.*;

import static com.taxonomy.backup.runtime.CurrentStateExportIT.*;
import static org.assertj.core.api.Assertions.*;

class KnowledgeExportIT {
    static final BackupScope SCOPE = new BackupScope.Workspace("repo-a", "private-a");

    @Test void currentCatalogueClosureKeepsDecisionsWithoutEarlierOrForeignEvidence() throws Exception {
        try (var f = new Fixture()) {
            var current = f.hypothesis("repo-a", "private-a", "current");
            var old = f.hypothesis("repo-a", "private-a", "old");
            var foreign = f.hypothesis("repo-b", "private-a", "foreign");
            f.evidence(current, "CURRENT-EVIDENCE"); f.evidence(old, "DELETED-SECRET"); f.evidence(foreign, "FOREIGN-SECRET");
            var relation = new TaxonomyRelation(); relation.setRepositoryId("repo-a"); relation.setWorkspaceId("private-a");
            relation.setSourceNode(f.a); relation.setTargetNode(f.b); relation.setRelationType(RelationType.DEPENDS_ON); f.persist(relation);
            var proposal = new RelationProposal(); proposal.setRepositoryId("repo-a"); proposal.setWorkspaceId("private-a");
            proposal.setSourceNode(f.a); proposal.setTargetNode(f.b); proposal.setRelationType(RelationType.DEPENDS_ON);
            proposal.setStatus(ProposalStatus.REJECTED); proposal.setRationale("CURRENT-REJECTION"); f.persist(proposal);
            f.persist(new RequirementCoverage("R-SAME", "UNATTRIBUTED-OLD-TEXT", "A", 90, NOW));
            var contributor = f.contributor(Set.of(new SourceRecordId("knowledge.hypothesis", current.getId().toString())));
            var currentOutput = new Contents(); contributor.write(snapshot(BackupProfile.CURRENT_STATE, SCOPE), currentOutput);
            assertThat(currentOutput.text()).contains("CURRENT-EVIDENCE", "CURRENT-REJECTION", "REJECTED", "Actual imported A", "Parent catalogue")
                    .doesNotContain("DELETED-SECRET", "FOREIGN-SECRET", "UNATTRIBUTED-OLD-TEXT", "Unrelated imported C");
            var history = new Contents(); contributor.write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), history);
            assertThat(history.text()).contains("DELETED-SECRET", "CURRENT-EVIDENCE").doesNotContain("FOREIGN-SECRET", "UNATTRIBUTED-OLD-TEXT");
        }
    }

    @Test void analysisHypothesesRequireTheExactProjectRequirementSessionAndActiveVersion() throws Exception {
        try (var f = new Fixture()) {
            f.rows.requirement("repo-a", "private-a", "OLD", "CURRENT");
            f.rows.requirement("repo-b", "private-a", "FOREIGN-OLD", "FOREIGN");
            var own = f.snapshot("repo-a"); var foreign = f.snapshot("repo-b");
            var hypothesis = f.hypothesis("repo-a", "private-a", own.getAnalysisSessionId());
            f.rows.jdbc.update("update relation_hypothesis set project_id=?, requirement_id=?, analysis_snapshot_id=? where id=?",
                    own.getProjectId(), own.getRequirementId(), own.getId(), hypothesis.getId());
            f.evidence(hypothesis, "ACTIVE-ANALYSIS");
            var output = new Contents(); f.contributor(Set.of()).write(snapshot(BackupProfile.CURRENT_STATE, SCOPE), output);
            assertThat(output.text()).contains("ACTIVE-ANALYSIS");
            f.rows.jdbc.update("update project_requirement set current_snapshot_id=null where id=?", own.getRequirementId());
            var stale = new Contents(); f.contributor(Set.of()).write(snapshot(BackupProfile.CURRENT_STATE, SCOPE), stale);
            assertThat(stale.text()).doesNotContain("ACTIVE-ANALYSIS");
            var history = new Contents(); f.contributor(Set.of()).write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), history);
            assertThat(history.text()).contains("ACTIVE-ANALYSIS");
            f.rows.jdbc.update("update relation_hypothesis set project_id=? where id=?", foreign.getProjectId(), hypothesis.getId());
            var invalid = new Contents();
            assertThatThrownBy(() -> f.contributor(Set.of()).write(snapshot(BackupProfile.REPOSITORY_HISTORY, SCOPE), invalid))
                    .isInstanceOf(IOException.class);
            assertThat(invalid.entries).isEmpty();
        }
    }

    @Test void brokenCatalogueHierarchyFailsBeforeWritingAnyEntry() throws Exception {
        try (var f = new Fixture()) {
            f.rows.jdbc.update("update taxonomy_node set parent_code='MISSING' where code='A'");
            assertBroken(f);
            f.rows.jdbc.update("update taxonomy_node set parent_code='P' where code='A'");
            f.rows.jdbc.update("update taxonomy_node set parent_code='A' where code='P'");
            assertBroken(f);
            f.rows.jdbc.update("update taxonomy_node set parent_code=null where code='P'");
            f.rows.jdbc.update("update taxonomy_node set parent_id=? where code='A'", f.b.getId());
            assertBroken(f);
        }
    }

    @Test void selectedVersionNeverReadsPresentDayCatalogueOrReviewRows() throws Exception {
        try (var f = new Fixture()) {
            var output = new Contents();
            new KnowledgeBackupContributor(f.rows.database, context -> { throw new AssertionError("present-day analysis read"); },
                    context -> { throw new AssertionError("present-day selection read"); }).write(snapshot(BackupProfile.SELECTED_VERSION, SCOPE), output);
            assertThat(output.entries).hasSize(7);
            assertThat(output.text()).doesNotContain("Actual imported A", "Parent catalogue");
        }
    }

    @Test void installationRetainsCurrentReviewAndPendingRecoveryButDoesNotReactivateWorkers() throws Exception {
        try (var f = new Fixture()) {
            f.persist(new RequirementCoverage("legacy", "OLD-LEGACY-TEXT", "A", 80, NOW));
            var pending = new RelationProjectionRecovery(); pending.setRepositoryId("repo-a"); pending.setWorkspaceId("private-a");
            pending.setBranch("draft"); pending.setPreviousHeadCommit("b".repeat(40)); pending.setAuthoritativeCommitId(COMMIT);
            pending.setCausationId("pending-operation"); pending.recordFailure(new IllegalStateException("PRIVATE-DIAGNOSTICS")); f.persist(pending);
            var completed = new RelationProjectionRecovery(); completed.setRepositoryId("repo-a"); completed.setWorkspaceId("private-a");
            completed.setBranch("draft"); completed.setAuthoritativeCommitId("c".repeat(40)); completed.setCausationId("old-operation");
            completed.recordFailure(new IllegalStateException("OLD-DIAGNOSTICS")); completed.complete(RelationProjectionRecovery.RecoveryStatus.RECOVERED); f.persist(completed);
            var output = new Contents(); f.contributor(Set.of()).write(snapshot(BackupProfile.INSTALLATION_CURRENT, new BackupScope.Installation()), output);
            assertThat(output.text()).contains("pending-operation", "REBUILD_FROM_CAPTURE", "Unrelated imported C", "legacy")
                    .doesNotContain("old-operation", "PRIVATE-DIAGNOSTICS", "OLD-DIAGNOSTICS", "OLD-LEGACY-TEXT", "b".repeat(40));
            var history = new Contents(); f.contributor(Set.of()).write(snapshot(BackupProfile.INSTALLATION_FULL, new BackupScope.Installation()), history);
            assertThat(history.text()).contains("old-operation", "OLD-LEGACY-TEXT", "b".repeat(40))
                    .doesNotContain("PRIVATE-DIAGNOSTICS", "OLD-DIAGNOSTICS");
        }
    }

    private void assertBroken(Fixture f) {
        var output = new Contents();
        assertThatThrownBy(() -> f.contributor(Set.of()).write(snapshot(BackupProfile.CURRENT_STATE, SCOPE), output)).isInstanceOf(IOException.class);
        assertThat(output.entries).isEmpty();
    }

    static final class Fixture implements AutoCloseable {
        final CurrentStateExportIT.Fixture rows;
        final TaxonomyNode a, b;
        Fixture() {
            rows = new CurrentStateExportIT.Fixture(config -> {
                config.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy());
                var beans = new DefaultListableBeanFactory();
                beans.registerSingleton("seedListener", new PrimaryRepositorySeedRelationListener(beans.getBeanProvider(SystemRepositoryService.class)));
                config.getProperties().put("hibernate.resource.beans.container", new SpringBeanContainer(beans));
            }, TaxonomyNode.class, TaxonomyRelation.class, RelationHypothesis.class, RelationEvidence.class,
                    RelationProposal.class, RelationProjectionRecovery.class, RequirementCoverage.class);
            node("P", "Parent catalogue", null); a = node("A", "Actual imported A", "P");
            b = node("B", "Actual imported B", null); node("C", "Unrelated imported C", null);
        }
        TaxonomyNode node(String code, String name, String parent) {
            var node = new TaxonomyNode(); node.setCode(code); node.setNameEn(name); node.setParentCode(parent); return persist(node);
        }
        <T> T persist(T entity) {
            try (var em = rows.factory.createEntityManager()) {
                var tx = em.getTransaction(); tx.begin(); em.persist(entity); tx.commit(); return entity;
            }
        }
        RelationHypothesis hypothesis(String repository, String workspace, String session) {
            var h = new RelationHypothesis(); h.setRepositoryId(repository); h.setWorkspaceId(workspace);
            h.setSourceNodeId("A"); h.setTargetNodeId("B"); h.setRelationType(RelationType.DEPENDS_ON); h.setAnalysisSessionId(session); return persist(h);
        }
        void evidence(RelationHypothesis hypothesis, String text) {
            var e = new RelationEvidence(); e.setHypothesis(hypothesis); e.setEvidenceType("MANUAL"); e.setSummary(text); e.setFullText(text); persist(e);
        }
        RequirementAnalysisSnapshot snapshot(String repo) {
            try (var em = rows.factory.createEntityManager()) {
                return em.createQuery("from RequirementAnalysisSnapshot where project.repositoryId=:repo", RequirementAnalysisSnapshot.class)
                        .setParameter("repo", repo).getSingleResult();
            }
        }
        KnowledgeBackupContributor contributor(Set<SourceRecordId> selected) {
            return new KnowledgeBackupContributor(rows.database, new PortfolioBackupContributor(rows.database)::analysisReferences,
                    context -> new KnowledgeBackupContributor.Selection(Set.of("A"), selected));
        }
        @Override public void close() { rows.close(); }
    }
}
