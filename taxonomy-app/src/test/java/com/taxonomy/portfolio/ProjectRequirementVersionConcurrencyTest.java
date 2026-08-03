package com.taxonomy.portfolio;

import com.taxonomy.portfolio.dto.PortfolioDtos.CreateProjectRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementRequest;
import com.taxonomy.portfolio.dto.PortfolioDtos.CreateRequirementVersionRequest;
import com.taxonomy.portfolio.model.PortfolioTypes.Criticality;
import com.taxonomy.portfolio.model.PortfolioTypes.ProjectStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementStatus;
import com.taxonomy.portfolio.model.PortfolioTypes.RequirementType;
import com.taxonomy.portfolio.model.PortfolioTypes.ReviewStatus;
import com.taxonomy.portfolio.service.ProjectPortfolioService;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProjectRequirementVersionConcurrencyTest {

    @Autowired
    private ProjectPortfolioService projectService;

    @Test
    void allocatesMonotonicUniqueVersionNumbersUnderConcurrentWriters() throws Exception {
        WorkspaceContext context = new WorkspaceContext(
                "version-writer", "ws-version-" + shortId(), "draft");
        var project = projectService.createProject(
                new CreateProjectRequest(
                        "P-" + shortId(),
                        "Concurrent version project",
                        null,
                        ProjectStatus.ACTIVE,
                        null,
                        null,
                        null,
                        null),
                context.username(),
                context);
        var requirement = projectService.createRequirement(
                project.id(),
                new CreateRequirementRequest(
                        "REQ-001",
                        "Concurrent requirement",
                        "Initial immutable text",
                        RequirementStatus.APPROVED,
                        50,
                        Criticality.MEDIUM,
                        RequirementType.FUNCTIONAL,
                        ReviewStatus.CONFIRMED,
                        context.username(),
                        "Initial version",
                        null),
                context.username(),
                context);

        int writers = 8;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < writers; index++) {
                int writer = index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent writers did not start together");
                    }
                    return projectService.addRequirementVersion(
                            project.id(),
                            requirement.id(),
                            new CreateRequirementVersionRequest(
                                    "Immutable text from writer " + writer,
                                    "Concurrent update " + writer,
                                    null),
                            context.username(),
                            context).versionNumber();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> allocated = new ArrayList<>();
            for (Future<Integer> future : futures) {
                allocated.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(allocated)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder(2, 3, 4, 5, 6, 7, 8, 9);
            assertThat(projectService.listRequirementVersions(
                    project.id(), requirement.id(), context.username(), context))
                    .extracting(version -> version.versionNumber())
                    .containsExactly(9, 8, 7, 6, 5, 4, 3, 2, 1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
