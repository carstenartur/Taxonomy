package com.taxonomy.portfolio;

import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.UUID;

/** Keeps each test context's initial Git project with the catalogue generation retained in its own database. */
public abstract class RetainedPortfolioCatalogue {
    @DynamicPropertySource
    protected static void portfolioDatabase(DynamicPropertyRegistry properties) {
        String database = "jdbc:hsqldb:mem:portfolio-" + UUID.randomUUID() + ";shutdown=true";
        properties.add("spring.datasource.url", () -> database);
    }

    @BeforeAll
    protected static void seedFixtureDraft(@Autowired DslGitRepositoryFactory repositories,
                                           @Autowired TaxDslExportService exporter) throws IOException {
        // The first context in the JVM may already have consumed the application's draft bootstrap.
        var repository = repositories.getSystemRepository();
        if (repository.getHeadCommit("draft") == null) {
            repository.commitDsl("draft", exporter.newProject("default"), "system", "Initialize portfolio test project");
        }
    }
}
