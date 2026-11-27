package com.taxonomy.portfolio.reformulation;

import com.taxonomy.dsl.parser.TaxDslParser;
import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementVersionView;
import com.taxonomy.portfolio.dto.PortfolioDtos.RequirementView;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.model.RequirementAnalysisJob;
import com.taxonomy.portfolio.model.RequirementAnalysisSnapshot;
import com.taxonomy.portfolio.model.SnapshotReference;
import com.taxonomy.portfolio.model.PortfolioTypes.*;
import com.taxonomy.portfolio.repository.ArchitectureProjectRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementRepository;
import com.taxonomy.portfolio.repository.ProjectRequirementVersionRepository;
import com.taxonomy.portfolio.service.PortfolioJsonCodec;
import com.taxonomy.portfolio.service.PortfolioScope;
import com.taxonomy.reformulation.ReformulationBaseline;
import com.taxonomy.reformulation.ValidationReport;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReformulationEvidenceReadTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String ORIGINAL = "Sicherung aufbewahren";
    private static final String ADOPTED = "Sicherung am zweiten Standort aufbewahren";
    private static final String SNAPSHOT = "retained-snapshot";
    private static final WorkspaceContext CONTEXT = new WorkspaceContext("owner", "workspace", "draft", "repository");
    private static final String SCOPE = PortfolioScope.key("owner", CONTEXT);
    private final PortfolioJsonCodec json = new PortfolioJsonCodec(JsonMapper.builder().build());

    @Test
    void portableEvidenceReadKeepsLargeProposalAndSnapshotPayloadsOutOfHibernateEntities() {
        var configuration = new Configuration()
                .setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver")
                .setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:reformulation-evidence-" + UUID.randomUUID() + ";shutdown=true")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.generate_statistics", "true")
                .setProperty("hibernate.search.enabled", "false");
        for (var type : List.of(ArchitectureProject.class, ProjectRequirement.class, ProjectRequirementVersion.class,
                RequirementAnalysisJob.class, RequirementAnalysisSnapshot.class, SnapshotReference.class,
                ReformulationProposal.class, ReformulationAdoptionPreview.class, ReformulationAdoption.class,
                ReformulationPortableEvidence.class)) {
            configuration.addAnnotatedClass(type);
        }
        try (var factory = configuration.buildSessionFactory(); var entities = factory.createEntityManager()) {
            seedAdoption(entities);
            entities.clear();
            var repositories = new JpaRepositoryFactory(entities);
            var codec = new ReformulationEvidenceCodec(json,
                    repositories.getRepository(ReformulationAdoptionRepository.class),
                    repositories.getRepository(ReformulationPortableEvidenceRepository.class),
                    repositories.getRepository(ArchitectureProjectRepository.class),
                    repositories.getRepository(ProjectRequirementRepository.class),
                    repositories.getRepository(ProjectRequirementVersionRepository.class),
                    new ReformulationAncestryRepository(entities));
            var statistics = factory.getStatistics();
            statistics.clear();
            entities.getTransaction().begin();
            String dsl = codec.contributeTo(null, SCOPE);
            entities.getTransaction().commit();

            var blocks = new TaxDslParser().parse(dsl, "portable-evidence-read.taxdsl")
                    .blocksOfKind("reformulationEvidence");
            assertThat(blocks).hasSize(1);
            var block = blocks.getFirst();
            entities.clear();
            entities.getTransaction().begin();
            var target = entities.createQuery("from ProjectRequirementVersion where versionNumber = 2",
                    ProjectRequirementVersion.class).getSingleResult();
            entities.clear();
            var lineage = codec.freezeSource(SCOPE, target.getRequirementId(), target.getId(), "P", "R", 2,
                    StableIdentityHash.sha256(ADOPTED));
            entities.getTransaction().commit();
            assertThat(lineage.roots()).containsExactly(block.property("evidenceHash"));
            assertThat(block.getHeaderTokens().subList(0, 3)).containsExactly("P", "R", "2");
            assertThat(block.property("evidenceHash")).isEqualTo(StableIdentityHash.sha256(block.property("payload")));
            assertThat(block.property("targetTextHash")).isEqualTo(StableIdentityHash.sha256(ADOPTED));
            var payload = json.read(block.property("payload"), ReformulationEvidenceCodec.Payload.class);
            assertThat(payload.sourceVersionNumber()).isEqualTo(1);
            assertThat(payload.analysisSnapshotId()).isEqualTo(SNAPSHOT);
            assertThat(payload.originalText()).isEqualTo(ORIGINAL);
            assertThat(payload.finalText()).isEqualTo(ADOPTED);
            assertThat(payload.rationale()).isEqualTo("Review completed");
            for (var type : List.of(RequirementAnalysisSnapshot.class, ReformulationProposal.class)) {
                assertThat(statistics.getEntityStatistics(type.getName()).getLoadCount())
                        .as("entities hydrated by a portable evidence read: %s", type.getSimpleName()).isZero();
            }
        }
    }

    private void seedAdoption(EntityManager entities) {
        entities.getTransaction().begin();
        var project = new ArchitectureProject(SCOPE, CONTEXT.workspaceId(), "owner", "P", "Project", "",
                ProjectStatus.PLANNING, NOW);
        entities.persist(project);
        var requirement = new ProjectRequirement(project, "R", "Backup", RequirementStatus.APPROVED,
                50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED, "owner", NOW);
        entities.persist(requirement);
        var source = version(requirement, 1, ORIGINAL);
        var target = version(requirement, 2, ADOPTED);
        entities.persist(source);
        entities.persist(target);
        var job = new RequirementAnalysisJob("job", project, "operation", "CUSTOM_OPENAI", 10,
                "owner", CONTEXT.workspaceId(), 1, NOW);
        entities.persist(job);
        String catalogue = "Katalogeintrag für Rücksicherung ".repeat(16000);
        var snapshot = new RequirementAnalysisSnapshot(SNAPSHOT, project, requirement, source, job,
                AnalysisStatus.PARTIAL, "analysis-reference", "CUSTOM_OPENAI", "model", "p".repeat(64), "t".repeat(64),
                CONTEXT.workspaceId(), CONTEXT.currentBranch(), "c".repeat(40), "owner", NOW, 1L, 0, null,
                json.write(Map.of("catalogue", catalogue)), null, null, null);
        entities.persist(snapshot);
        var baseline = new ReformulationBaseline(new ReformulationBaseline.Scope(CONTEXT.repositoryId(),
                CONTEXT.workspaceId(), CONTEXT.currentBranch(), project.getId(), requirement.getId()),
                source.getId(), ORIGINAL, source.getContentHash(), SNAPSHOT, "{}", Map.of("catalogue", catalogue), "de", "v1");
        var proposal = new ReformulationProposal("proposal", SCOPE, project.getId(), requirement.getId(), source.getId(),
                SNAPSHOT, json.write(baseline), "owner", NOW);
        entities.persist(proposal);
        var pendingBaseline = new ReformulationAncestryRepository(entities).read("proposal", SCOPE,
                requirement.getId(), source.getId(), reader -> json.read(reader, ReformulationBaseline.class));
        assertThat(pendingBaseline).hasValueSatisfying(row -> assertThat(row.baseline()).isEqualTo(baseline));
        var versionView = new RequirementVersionView(source.getId(), 1, ORIGINAL, source.getContentHash(),
                "Initial", "owner", NOW, null);
        var requirementView = new RequirementView(requirement.getId(), project.getId(), "R", "Backup",
                RequirementStatus.APPROVED, 50, Criticality.HIGH, RequirementType.FUNCTIONAL, ReviewStatus.PROPOSED,
                "owner", source.getId(), SNAPSHOT, NOW, NOW, versionView);
        var revision = new ReformulationDtos.Revision(2L, 1L, ADOPTED, List.of(), List.of(), List.of(), List.of(),
                new ValidationReport(List.of()), "owner", NOW, "Review completed");
        String preview = json.write(new ReformulationAdoptionDtos.PreviewContent("preview", "proposal", source.getId(),
                ORIGINAL, SNAPSHOT, requirementView, 0L, revision, ADOPTED, List.of(), List.of(), List.of(), "owner", NOW));
        entities.persist(new ReformulationAdoptionPreview("preview", "proposal", SCOPE, StableIdentityHash.sha256(preview), preview, NOW));
        String receipt = json.write(new ReformulationAdoptionDtos.Result("command", "preview", "proposal", 2L,
                source.getId(), target.getId(), 2, true, true, List.of(), "owner", NOW, "Review completed", true));
        entities.persist(new ReformulationAdoption("receipt", "proposal", "preview", SCOPE, "command", requirement.getId(),
                target.getId(), receipt, NOW));
        entities.getTransaction().commit();
    }

    private static ProjectRequirementVersion version(ProjectRequirement requirement, int number, String text) {
        return new ProjectRequirementVersion(requirement, number, text, StableIdentityHash.sha256(text), "Reason", "owner",
                NOW, null, null, null, null, null, null);
    }
}
