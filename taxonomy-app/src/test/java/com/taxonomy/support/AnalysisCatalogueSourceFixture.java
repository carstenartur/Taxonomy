package com.taxonomy.support;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dsl.export.TaxDslExportService;
import com.taxonomy.workspace.service.SystemRepositoryService;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Provisions the real catalogue checkpoint in the Spring test's own database. */
public interface AnalysisCatalogueSourceFixture {
    @BeforeAll
    static void prepareAnalysisSource(@Autowired TaxonomyService catalogue,
                                      @Autowired TaxDslExportService export,
                                      @Autowired SystemRepositoryService systems,
                                      @Autowired DslGitRepositoryFactory repositories) throws IOException {
        assertThat(catalogue.isInitialized()).as("real catalogue startup completed").isTrue();
        var primary = systems.getPrimaryRepository();
        var source = repositories.getCentralRepository(primary.getRepositoryId());
        String branch = primary.getDefaultBranch();
        if (source.getHeadCommit(branch) == null) {
            source.commitDsl(branch, export.exportAll("analysis-test-source"), "system",
                    "Catalogue checkpoint for analysis test");
        }
        assertThat(source.getDslAtHead(branch)).as("readable source checkpoint for analysis workspace").isNotBlank();
    }
}
