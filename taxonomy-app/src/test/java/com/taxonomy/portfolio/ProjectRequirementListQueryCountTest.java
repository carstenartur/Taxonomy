package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class ProjectRequirementListQueryCountTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void listsOneHundredRequirementsWithConstantQueryCount() {
        WorkspaceContext context = new WorkspaceContext(
                "scale-user", "ws-scale-" + shortId(), "draft");
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-SCALE-" + shortId(),
                        "Scale query project",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);

        for (int index = 1; index <= 100; index++) {
            String key = "REQ-%04d".formatted(index);
            projectService.createRequirement(
                    project.id(),
                    new CreateRequirementRequest(
                            key,
                            "Requirement " + index,
                            "Traceable requirement text " + index,
                            RequirementStatus.APPROVED,
                            50,
                            Criticality.MEDIUM,
                            RequirementType.FUNCTIONAL,
                            ReviewStatus.CONFIRMED,
                            context.username(),
                            "Scale fixture",
                            null),
                    context.username(),
                    context);
        }

        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.clear();

        var requirements = projectService.listRequirements(
                project.id(), context.username(), context);

        assertThat(requirements).hasSize(100);
        assertThat(requirements)
                .allSatisfy(requirement -> assertThat(requirement.currentVersion()).isNotNull());
        assertThat(statistics.getQueryExecutionCount())
                .as("project lookup plus one joined requirement/current-version query")
                .isLessThanOrEqualTo(3L);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
