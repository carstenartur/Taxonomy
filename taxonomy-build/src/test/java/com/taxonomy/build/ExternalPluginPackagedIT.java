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
    @Test void standardDistributionIncludesChecksumsForEveryExternalPlugin() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path directory = root.resolve("taxonomy-app/target/plugins");
        Path plugin = PackagedPluginSupport.artifact(directory, "taxonomy-mermaid-plugin-");
        assertThat(Files.readString(plugin.resolveSibling(plugin.getFileName() + ".sha256")).strip())
                .isEqualTo(PackagedPluginSupport.sha256(plugin) + " *" + plugin.getFileName());
    }

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
            var disabled = app.request("POST", "/api/admin/plugins/taxonomy.mermaid/deactivate", null);
            assertThat(disabled.statusCode()).as(disabled.body()).isEqualTo(409);
            assertThat(disabled.body()).contains("DYNAMIC_DISABLED");
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
        try (var app = PackagedPluginSupport.start(host, installed, host.getParent().resolve("features"),
                temporary.resolve("dynamic"), List.of("--taxonomy.plugins.dynamic.enabled=true"))) {
            for (int cycle = 0; cycle < 3; cycle++) {
                var stopped = app.request("POST", "/api/admin/plugins/taxonomy.mermaid/deactivate", null);
                assertThat(stopped.statusCode()).as(stopped.body()).isEqualTo(200);
                assertThat(stopped.body()).contains("STOPPED");
                assertThat(app.request("GET", "/api/extensions", null).body()).doesNotContain("mermaid");
                assertThat(app.request("POST", "/api/diagram/export/mermaid", "{\"businessText\":\"Offline export\"}").statusCode()).isEqualTo(404);
                var active = app.request("POST", "/api/admin/plugins/taxonomy.mermaid/activate", null);
                assertThat(active.statusCode()).as(active.body()).isEqualTo(200);
                assertThat(active.body()).contains(PackagedPluginSupport.sha256(plugin));
                assertThat(app.request("POST", "/api/diagram/export/mermaid", "{\"businessText\":\"Offline export\"}").statusCode()).isEqualTo(200);
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
                        + "\nindependent.emptyRepository=true\nhttp.locales=en,de\nwithout.plugin=ready,format404\ndynamic.cycles=3\ndynamic.default=disabled\n");
    }
}
