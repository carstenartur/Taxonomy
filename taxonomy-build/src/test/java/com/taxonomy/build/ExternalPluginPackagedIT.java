package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import static org.assertj.core.api.Assertions.*;

/** Real independently built plugin, unchanged packaged host, authenticated HTTP exports. */
class ExternalPluginPackagedIT {
    @TempDir Path temporary;
    @Test void externalMermaidJarIsOptionalAndDoesNotRequireRebuildingTheHost() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path host = PackagedPluginSupport.application(root);
        String original = PackagedPluginSupport.sha256(host);
        try (var jar = new JarFile(host.toFile())) {
            assertThat(jar.getJarEntry("BOOT-INF/classes/com/taxonomy/export/service/MermaidExportExtension.class"))
                    .as("Mermaid adapter must no longer be built into the host").isNull();
            assertThat(jar.stream().map(e -> e.getName())).noneMatch(n -> n.contains("taxonomy-mermaid-plugin"));
        }
        Path plugin = PackagedPluginSupport.buildIndependentPlugin(root, temporary);
        Path installed = Files.createDirectory(temporary.resolve("installed plugins"));
        Files.copy(plugin, installed.resolve("mermaid plugin.jar"));
        try (var app = PackagedPluginSupport.start(host, installed, temporary.resolve("with-plugin"))) {
            assertThat(app.request("GET", "/api/extensions", null).body()).contains("mermaid");
            for (String locale : List.of("en", "de")) {
                String request = "{\"businessText\":\"Coordinate civil hospital logistics and medical supplies\",\"locale\":\"" + locale + "\"}";
                var generic = app.request("POST", "/api/diagram/export/mermaid", request);
                assertThat(generic.statusCode()).as(generic.body()).isEqualTo(200);
                assertThat(generic.headers().firstValue("Content-Disposition").orElseThrow()).contains(".mmd");
                assertThat(generic.body()).startsWith("flowchart");
                var legacy = app.request("POST", "/api/diagram/mermaid", request);
                assertThat(legacy.statusCode()).as(legacy.body()).isEqualTo(200);
                assertThat(legacy.body()).isEqualTo(generic.body());
            }
        }
        Path absent = Files.createDirectory(temporary.resolve("no plugins"));
        try (var app = PackagedPluginSupport.start(host, absent, temporary.resolve("without-plugin"))) {
            assertThat(app.request("GET", "/api/extensions", null).body()).doesNotContain("mermaid");
            assertThat(app.request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
            for (String route : List.of("/api/diagram/export/mermaid", "/api/diagram/mermaid")) {
                assertThat(app.request("POST", route, "{\"businessText\":\"Offline export\"}").statusCode()).isEqualTo(404);
            }
        }
        assertThat(PackagedPluginSupport.sha256(host)).isEqualTo(original);
        Files.writeString(root.resolve("taxonomy-build/target/plugin-packaging-evidence.txt"),
                "host.sha256=" + original + "\nmermaid.sha256=" + PackagedPluginSupport.sha256(plugin)
                        + "\nindependent.emptyRepository=true\nhttp.locales=en,de\nwithout.plugin=ready,format404\n");
    }
}
