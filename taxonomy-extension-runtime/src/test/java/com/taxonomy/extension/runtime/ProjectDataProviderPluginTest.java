package com.taxonomy.extension.runtime;

import com.taxonomy.extension.api.plugin.ExtensionKey;
import com.taxonomy.extension.api.project.ProjectDataProvider;
import com.taxonomy.shared.extension.ExtensionKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ProjectDataProviderPluginTest {
    @TempDir Path directory;

    @Test void externallyCompiledStartupPluginProvidesTwoDistinctNamedUnitsAcrossTheSdkBoundary() throws Exception {
        Path operator = directory.resolve("plugins");
        var jar = PluginJarFixture.create(operator, "city.jar", "example.city",
                Map.of("Plugin-Requires", ">=1.6.0 & <2.0.0", "Taxonomy-Plugin-Mode", "STARTUP",
                        "Taxonomy-Plugin-Capabilities", "project-data"),
                """
                return List.of(new com.taxonomy.extension.api.project.ProjectDataProvider() {
                    public String id() { return "city-directory"; }
                    public String displayName() { return "Municipal directory"; }
                    public Dataset dataset() {
                        return new Dataset("v1", null, List.of(new Unit("1", "Registry", null), new Unit("2", "Registry", "1")));
                    }
                });
                """, Map.of());
        var catalog = new PluginCatalog();
        try (var runtime = new Pf4jPluginRuntime(operator, directory.resolve("cache"), catalog)) {
            var artifact = runtime.install(jar);
            runtime.start(artifact.id());
            try (var lease = catalog.acquire(new ExtensionKey(ExtensionKind.PROJECT_DATA_PROVIDER, "city-directory"),
                    ProjectDataProvider.class)) {
                assertThat(lease.plugin()).isEqualTo(artifact);
                assertThat(lease.extension().dataset().organizations()).extracting(ProjectDataProvider.Unit::sourceId)
                        .containsExactly("1", "2");
            }
        }
    }
}
