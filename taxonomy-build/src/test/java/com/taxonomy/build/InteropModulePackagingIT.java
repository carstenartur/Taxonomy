package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InteropModulePackagingIT {
    @Test
    void distributionShipsInteropOnlyInItsExternalFeature() throws Exception {
        var ownedClasses = new HashSet<String>();
        var mappingClasses = new HashSet<String>();
        var registrations = new HashSet<String>();
        var libraries = DistributionArchives.inspect(PackagedPluginSupport.repository(), (archive, path, bytes) -> {
            if (path.startsWith("com/taxonomy/sparx/mapping/") && path.endsWith(".class")) {
                DistributionArchives.assertOwner(archive, "taxonomy-sparx-mapping", false);
                assertThat(mappingClasses.add(path)).as("single occurrence of shared mapping %s", path).isTrue();
            }
            if (path.startsWith("com/taxonomy/interop/") && path.endsWith(".class")) {
                DistributionArchives.assertOwner(archive, "taxonomy-interop", true);
                assertThat(ownedClasses.add(path)).as("single occurrence of %s", path).isTrue();
            }
            if (path.equals("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                for (String registration : new String(bytes.readAllBytes(), StandardCharsets.UTF_8)
                        .lines().map(String::strip).filter(line -> line.startsWith("com.taxonomy.interop.")).toList()) {
                    DistributionArchives.assertOwner(archive, "taxonomy-interop", true);
                    assertThat(registrations.add(registration)).as("unique registration %s", registration).isTrue();
                }
            }
        });
        DistributionArchives.assertSingleLibrary(libraries, "taxonomy-interop", true);
        assertThat(ownedClasses).contains("com/taxonomy/interop/IntegrationService.class",
                "com/taxonomy/interop/persistence/IntegrationStore.class",
                "com/taxonomy/interop/oslc/OslcProviderService.class",
                "com/taxonomy/interop/config/InteropFeatureAutoConfiguration.class");
        assertThat(registrations).containsExactly("com.taxonomy.interop.config.InteropFeatureAutoConfiguration");
        assertThat(mappingClasses).contains("com/taxonomy/sparx/mapping/SparxSemanticTypes.class");
        // The external feature still uses the shared host libraries, including the OSLC HTTP transport.
        for (String dependency : List.of("taxonomy-domain", "taxonomy-dsl", "taxonomy-sparx-mapping", "taxonomy-export", "taxonomy-extension-api",
                "taxonomy-workspace", "spring-context", "spring-tx", "springdoc-openapi-starter-common",
                "httpclient5", "httpcore5", "httpcore5-h2")) {
            DistributionArchives.assertSingleLibrary(libraries, dependency, false);
        }
    }
}
