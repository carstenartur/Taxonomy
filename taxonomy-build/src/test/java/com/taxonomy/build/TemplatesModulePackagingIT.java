package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;

class TemplatesModulePackagingIT {
    @Test void genericImplementationPublicApiAndReportDefaultHaveExactlyOnePhysicalOwner() throws Exception {
        var found = new HashSet<String>();
        var libraries = DistributionArchives.inspect(PackagedPluginSupport.repository(), (archive, path, bytes) -> {
            if (path.startsWith("com/taxonomy/templates/") && path.endsWith(".class")) {
                boolean api = path.startsWith("com/taxonomy/templates/api/");
                DistributionArchives.assertOwner(archive, api ? "taxonomy-templates-api" : "taxonomy-templates", !api);
                assertThat(found.add(path)).as("single occurrence of %s", path).isTrue();
            } else if (path.equals("document-templates/decision-rationale-report.dotx")) {
                DistributionArchives.assertOwner(archive, "taxonomy-reporting", true);
                assertThat(found.add(path)).as("single occurrence of report template").isTrue();
            }
        });
        assertThat(libraries.stream().filter(n -> n.matches("features/taxonomy-templates-[0-9].*\\.jar"))).hasSize(1);
        assertThat(libraries.stream().filter(n -> n.matches("features/taxonomy-reporting-[0-9].*\\.jar"))).hasSize(1);
        assertThat(found).contains("document-templates/decision-rationale-report.dotx",
                "com/taxonomy/templates/api/DocumentTemplates.class",
                "com/taxonomy/templates/DocumentTemplateGitRepository.class",
                "com/taxonomy/templates/OoxmlTemplatePackageCodec.class",
                "com/taxonomy/templates/DocumentTemplateWebDavServlet.class",
                "com/taxonomy/templates/DefaultDocumentTemplateBootstrap.class");
    }
}
