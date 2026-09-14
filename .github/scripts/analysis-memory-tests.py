from pathlib import Path

files = {
    'taxonomy-app/src/test/java/com/taxonomy/analysis/runtime/HsqldbFileProfileTest.java': r'''package com.taxonomy.analysis.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HsqldbFileProfileTest {
    @TempDir Path directory;

    @Configuration(proxyBeanMethods = false)
    static class ConfigurationOnly { }

    private ConfigurableApplicationContext configuration(String... extra) {
        SpringApplication app = new SpringApplication(ConfigurationOnly.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setAdditionalProfiles("hsqldb-file");
        app.setDefaultProperties(Map.of("spring.main.banner-mode", "off"));
        String[] args = new String[extra.length + 2];
        args[0] = "--TAXONOMY_HSQLDB_FILE_PATH=" + directory.resolve("taxonomydb");
        args[1] = "--TAXONOMY_SEARCH_DIRECTORY_ROOT=" + directory.resolve("index");
        System.arraycopy(extra, 0, args, 2, extra.length);
        return app.run(args);
    }

    @Test
    void fileProfilePreservesDataAndMovesBothStoresOffHeap() {
        try (var context = configuration()) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:hsqldb:file:")
                    .contains("hsqldb.default_table_type=cached", "hsqldb.cache_size=4096",
                            "hsqldb.cache_rows=10000", "hsqldb.write_delay_millis=0", "shutdown=true");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.search.backend.directory.type"))
                    .isEqualTo("local-filesystem");
            assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("1");
            assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("true");
            assertThat(env.getProperty("spring.jpa.properties.hibernate.hbm2ddl.schema_filter_provider"))
                    .endsWith("JgitStorageHibernateSchemaFilterProvider");
        }
    }

    @Test
    void realHsqldbCreatesCachedTablesAndKeepsRowsAfterRestart() throws Exception {
        String url;
        try (var context = configuration()) {
            url = context.getEnvironment().getProperty("spring.datasource.url");
            assertThat(url).startsWith("jdbc:hsqldb:file:");
        }
        try (var connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("CREATE TABLE restart_probe (id INTEGER PRIMARY KEY, payload VARCHAR(100))");
            connection.createStatement().execute("INSERT INTO restart_probe VALUES (1, 'accepted analysis')");
            try (var rows = connection.createStatement().executeQuery(
                    "SELECT HSQLDB_TYPE FROM INFORMATION_SCHEMA.SYSTEM_TABLES WHERE TABLE_NAME='RESTART_PROBE'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CACHED");
            }
        }
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var rows = connection.createStatement().executeQuery("SELECT payload FROM restart_probe WHERE id=1")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("accepted analysis");
        }
        assertThat(directory.resolve("taxonomydb.properties")).exists();
    }

    @Test
    void explicitStorageLimitsRemainConfigurable() {
        try (var context = configuration("--TAXONOMY_HSQLDB_CACHE_SIZE_KB=2048", "--TAXONOMY_HSQLDB_CACHE_ROWS=5000")) {
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .contains("hsqldb.cache_size=2048", "hsqldb.cache_rows=5000");
        }
    }
}
''',
    'taxonomy-app/src/test/java/com/taxonomy/analysis/service/LlmTranscriptBoundsTest.java': r'''package com.taxonomy.analysis.service;

import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.LlmCallDetail;
import com.taxonomy.shared.service.LocalEmbeddingService;
import com.taxonomy.shared.service.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LlmTranscriptBoundsTest {
    @Test
    void aggregatedTranscriptsAreBoundedWithoutLosingAnyScoresOrReasons() {
        LlmService service = new LlmService(mock(LlmProviderConfig.class), mock(LlmGatewayRegistry.class),
                new ObjectMapper(), mock(TaxonomyService.class), mock(PromptTemplateService.class),
                mock(LocalEmbeddingService.class), mock(SavedAnalysisService.class));
        List<LlmCallDetail> details = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            LlmCallDetail detail = new LlmCallDetail();
            detail.setScores(Map.of("IP-" + index, 73));
            detail.setReasons(Map.of("IP-" + index, "reason " + index));
            detail.setPrompt("p".repeat(8192));
            detail.setRawResponse("r".repeat(8192));
            detail.setDurationMs(10);
            details.add(detail);
        }
        LlmCallDetail merged = ReflectionTestUtils.invokeMethod(service, "mergeDetails", details);
        assertThat(merged).isNotNull();
        assertThat(merged.getScores()).hasSize(100).containsEntry("IP-99", 73);
        assertThat(merged.getReasons()).hasSize(100).containsEntry("IP-99", "reason 99");
        assertThat(merged.getDurationMs()).isEqualTo(1000);
        assertThat(merged.getPrompt().length()).isLessThanOrEqualTo(32768);
        assertThat(merged.getRawResponse().length()).isLessThanOrEqualTo(32768);
        assertThat(merged.getPrompt()).contains("truncated");
    }
}
''',
    'taxonomy-app/src/test/java/com/taxonomy/analysis/runtime/AnalysisCapacityTest.java': r'''package com.taxonomy.analysis.runtime;

import com.taxonomy.AppConfig;
import org.junit.jupiter.api.Test;
import java.util.concurrent.ThreadPoolExecutor;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisCapacityTest {
    @Test
    void streamingAnalysisExecutorHasABoundedQueue() {
        try (var executor = new AppConfig().analysisExecutor()) {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            assertThat(((ThreadPoolExecutor) executor).getQueue().remainingCapacity()).isBetween(1, 32);
        }
    }
}
'''
}
for name, content in files.items():
    path = Path(name)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise SystemExit(f'Refusing to overwrite existing test: {name}')
    path.write_text(content, encoding='utf-8')
print('Created', len(files), 'behavioral regression classes')
