package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.model.ArchitectureProject;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.model.ProjectRequirement;
import com.taxonomy.portfolio.model.ProjectRequirementVersion;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controlled 1,000-requirement scale contract for the portfolio list path.
 * Fixture creation is excluded from the measured read operation.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class ProjectRequirementThousandScaleContractTest {

    private static final int REQUIREMENT_COUNT = 1_000;
    private static final int MAXIMUM_READ_STATEMENTS = 3;
    private static final Duration MAXIMUM_READ_DURATION = Duration.ofSeconds(30);

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void listsOneThousandCurrentRequirementVersionsWithConstantDatabaseWork() {
        WorkspaceContext context = new WorkspaceContext(
                "scale-thousand-user", "ws-scale-thousand-" + shortId(), "draft");
        var projectView = projectService.createProject(
                new CreateProjectRequest(
                        "P-SCALE-1000-" + shortId(),
                        "One thousand requirement scale contract",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);

        ArchitectureProject project = entityManager.getReference(
                ArchitectureProject.class, projectView.id());
        Instant now = Instant.parse("2026-08-03T10:00:00Z");
        for (int index = 1; index <= REQUIREMENT_COUNT; index++) {
            ProjectRequirement requirement = new ProjectRequirement(
                    project,
                    "REQ-%04d".formatted(index),
                    "Requirement " + index,
                    RequirementStatus.APPROVED,
                    50,
                    Criticality.MEDIUM,
                    RequirementType.FUNCTIONAL,
                    ReviewStatus.CONFIRMED,
                    context.username(),
                    now);
            entityManager.persist(requirement);

            ProjectRequirementVersion version = new ProjectRequirementVersion(
                    requirement,
                    1,
                    "Traceable requirement text " + index,
                    "%064x".formatted(index),
                    "Scale fixture",
                    context.username(),
                    now,
                    null,
                    null,
                    "[]",
                    null,
                    null,
                    null);
            entityManager.persist(version);
            requirement.pointToVersion(version.getId(), now);

            if (index % 100 == 0) {
                entityManager.flush();
                entityManager.clear();
                project = entityManager.getReference(
                        ArchitectureProject.class, projectView.id());
            }
        }

        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        long startedAt = System.nanoTime();
        var requirements = projectService.listRequirements(
                projectView.id(), context.username(), context);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(requirements).hasSize(REQUIREMENT_COUNT);
        assertThat(requirements)
                .allSatisfy(requirement -> assertThat(requirement.currentVersion()).isNotNull());
        assertThat(statistics.getPrepareStatementCount())
                .as("project authorization plus current-version list query count")
                .isLessThanOrEqualTo(MAXIMUM_READ_STATEMENTS);
        assertThat(elapsed)
                .as("controlled in-memory database read duration; fixture creation excluded")
                .isLessThan(MAXIMUM_READ_DURATION);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
