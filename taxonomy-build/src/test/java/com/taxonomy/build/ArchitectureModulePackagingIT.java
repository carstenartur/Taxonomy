package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureModulePackagingIT {
    @Test
    void distributionShipsArchitectureOnlyInItsExternalFeature() throws Exception {
        var ownedClasses = new HashSet<String>();
        var registrations = new HashSet<String>();
        var hostClasses = List.of("com/taxonomy/composition/report/PreferenceArchitectureReportMetadata.class",
                "com/taxonomy/composition/report/ReportApiController.class");
        var foundHostClasses = new HashSet<String>();
        var libraries = DistributionArchives.inspect(PackagedPluginSupport.repository(), (archive, path, bytes) -> {
            if (path.startsWith("com/taxonomy/architecture/") && path.endsWith(".class")) {
                DistributionArchives.assertOwner(archive, "taxonomy-architecture", true);
                assertThat(ownedClasses.add(path)).as("unique class %s", path).isTrue();
            }
            if (hostClasses.contains(path)) {
                assertThat(archive).as("host composition owning %s", path).isEqualTo("taxonomy-app");
                assertThat(foundHostClasses.add(path)).as("unique host class %s", path).isTrue();
            }
            if (path.equals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                for (String registration : new String(bytes.readAllBytes(), StandardCharsets.UTF_8)
                        .lines().map(String::strip).filter(line -> line.startsWith("com.taxonomy.architecture.")).toList()) {
                    DistributionArchives.assertOwner(archive, "taxonomy-architecture", true);
                    assertThat(registrations.add(registration)).as("unique registration %s", registration).isTrue();
                }
            }
        });
        DistributionArchives.assertSingleLibrary(libraries, "taxonomy-architecture", true);
        assertThat(ownedClasses).contains("com/taxonomy/architecture/service/ArchitectureReportService.class",
                "com/taxonomy/architecture/pipeline/ArchitectureViewPipeline.class",
                "com/taxonomy/architecture/service/ArchitectureReportMetadataPort.class",
                "com/taxonomy/architecture/config/ArchitectureFeatureAutoConfiguration.class");
        assertThat(foundHostClasses).containsExactlyInAnyOrderElementsOf(hostClasses);
        assertThat(registrations).containsExactly("com.taxonomy.architecture.config.ArchitectureFeatureAutoConfiguration");
        // Excluding the optional feature must retain its shared runtime dependencies in the host.
        for (String dependency : List.of("taxonomy-domain", "taxonomy-dsl", "taxonomy-export", "taxonomy-extension-api",
                "taxonomy-workspace", "taxonomy-knowledge", "taxonomy-reporting-api", "spring-context", "spring-tx",
                "spring-security-core", "springdoc-openapi-starter-common")) {
            DistributionArchives.assertSingleLibrary(libraries, dependency, false);
        }
    }
}
