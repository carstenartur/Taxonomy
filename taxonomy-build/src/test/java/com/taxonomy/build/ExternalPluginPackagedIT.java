package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import static org.assertj.core.api.Assertions.*;

/** Real independently built plugins, unchanged packaged host, authenticated HTTP exchanges. */
class ExternalPluginPackagedIT {
    @TempDir Path temporary;
    @Test void standardDistributionIncludesChecksumsForEveryExternalPlugin() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path directory = root.resolve("taxonomy-app/target/plugins");
        Path plugin = PackagedPluginSupport.artifact(directory, "taxonomy-mermaid-plugin-");
        assertThat(Files.readString(plugin.resolveSibling(plugin.getFileName() + ".sha256")).strip())
                .isEqualTo(PackagedPluginSupport.sha256(plugin) + " *" + plugin.getFileName());
    }

    @Test void externalLeanIxJarLoadsWithoutCredentialsAndWithoutBundledHostClasses() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path host = PackagedPluginSupport.application(root);
        String original = PackagedPluginSupport.sha256(host);
        Path plugin = PackagedPluginSupport.buildIndependentPlugin(root, temporary,
                "taxonomy-leanix-plugin", 31, "leanix-sdk-verification.txt");
        try (var jar = new JarFile(plugin.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Taxonomy-Plugin-Mode")).isEqualTo("STARTUP");
            assertThat(jar.getManifest().getMainAttributes().getValue("Plugin-Requires")).contains("1.1.0");
            assertThat(jar.getJarEntry("META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin")).isNotNull();
            assertThat(jar.stream().map(e -> e.getName())).noneMatch(n -> n.startsWith("com/taxonomy/extension/")
                    || n.startsWith("com/fasterxml/jackson/") || n.startsWith("org/apache/hc/") || n.endsWith(".jar"));
            assertThat(jar.getJarEntry("com/taxonomy/plugins/leanix/internal/jackson/databind/ObjectMapper.class")).isNotNull();
        }
        Path installed = Files.createDirectory(temporary.resolve("leanix-installed"));
        Files.copy(plugin, installed.resolve("leanix.jar"));
        try (var app = PackagedPluginSupport.start(host, installed, host.getParent().resolve("features"),
                temporary.resolve("leanix-state"), List.of("--taxonomy.plugins.dynamic.enabled=true"))) {
            var metadata = app.request("GET", "/api/extensions", null);
            assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
            assertThat(metadata.body()).contains("leanix", "INVENTORY_SOURCE");
            assertThat(app.request("POST", "/api/admin/plugins/taxonomy.leanix/deactivate", null).statusCode()).isEqualTo(409);
        }
        assertThat(PackagedPluginSupport.sha256(host)).isEqualTo(original);
    }

    @Test void externalAlfabetJarLoadsWithoutCredentialsAndWithoutBundledHostClasses() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path host = PackagedPluginSupport.application(root);
        String original = PackagedPluginSupport.sha256(host);
        Path plugin = PackagedPluginSupport.buildIndependentPlugin(root, temporary,
                "taxonomy-alfabet-plugin", 42, "alfabet-sdk-verification.txt");
        try (var jar = new JarFile(plugin.toFile())) {
            assertThat(jar.getManifest().getMainAttributes().getValue("Taxonomy-Plugin-Mode")).isEqualTo("STARTUP");
            assertThat(jar.getManifest().getMainAttributes().getValue("Plugin-Requires")).contains("1.2.0");
            assertThat(jar.getJarEntry("META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin")).isNotNull();
            assertThat(jar.stream().map(e -> e.getName())).noneMatch(n -> n.startsWith("com/taxonomy/extension/")
                    || n.startsWith("com/taxonomy/interop/") || n.startsWith("com/fasterxml/jackson/")
                    || n.startsWith("org/apache/hc/") || n.endsWith(".jar"));
            assertThat(jar.getJarEntry("com/taxonomy/plugins/alfabet/internal/jackson/databind/ObjectMapper.class")).isNotNull();
        }
        Path installed = Files.createDirectory(temporary.resolve("alfabet-installed"));
        Files.copy(plugin, installed.resolve("alfabet.jar"));
        try (var app = PackagedPluginSupport.start(host, installed, host.getParent().resolve("features"),
                temporary.resolve("alfabet-state"), List.of("--taxonomy.plugins.dynamic.enabled=true"))) {
            var metadata = app.request("GET", "/api/extensions", null);
            assertThat(metadata.statusCode()).as(metadata.body()).isEqualTo(200);
            assertThat(metadata.body()).contains("alfabet", "INVENTORY_SOURCE");
            assertThat(app.request("POST", "/api/admin/plugins/taxonomy.alfabet/deactivate", null).statusCode()).isEqualTo(409);
        }
        Path absent = Files.createDirectory(temporary.resolve("alfabet-absent"));
        try (var app = PackagedPluginSupport.start(host, absent, temporary.resolve("alfabet-without-plugin"))) {
            assertThat(app.request("GET", "/api/extensions", null).body()).doesNotContain("alfabet");
            assertThat(app.request("GET", "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
        }
        assertThat(PackagedPluginSupport.sha256(host)).isEqualTo(original);
    }

    @Test void externalSparxQeaJarImportsReviewedSnapshotsAndRemainsOptional() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path host = PackagedPluginSupport.application(root);
        String original = PackagedPluginSupport.sha256(host);
        try (var jar = new JarFile(host.toFile())) {
            assertThat(jar.stream().map(entry -> entry.getName()))
                    .noneMatch(name -> name.contains("taxonomy-sparx-plugin") || name.contains("com/taxonomy/plugins/sparx/"));
        }
        Path plugin = PackagedPluginSupport.buildIndependentPlugin(root, temporary,
                "taxonomy-sparx-plugin", 25, "sparx-sdk-verification.txt");
        String pluginVersion;
        try (var jar = new JarFile(plugin.toFile())) {
            var manifest = jar.getManifest().getMainAttributes();
            assertThat(manifest.getValue("Plugin-Id")).isEqualTo("taxonomy.sparx");
            assertThat(manifest.getValue("Taxonomy-Plugin-Mode")).isEqualTo("STARTUP");
            assertThat(manifest.getValue("Plugin-Requires")).contains("1.5.0");
            pluginVersion = manifest.getValue("Plugin-Version");
            assertThat(pluginVersion).isNotBlank();
            assertThat(jar.getJarEntry("META-INF/services/com.taxonomy.extension.api.plugin.TaxonomyPlugin")).isNotNull();
            assertThat(jar.getJarEntry("org/sqlite/JDBC.class")).isNotNull();
            assertThat(jar.stream().map(entry -> entry.getName()))
                    .anyMatch(name -> name.startsWith("org/sqlite/native/") && name.endsWith("/libsqlitejdbc.so"));
            assertThat(jar.stream().map(entry -> entry.getName())).noneMatch(name -> name.startsWith("com/taxonomy/extension/")
                    || name.startsWith("com/taxonomy/interop/") || name.startsWith("com/taxonomy/dsl/")
                    || name.startsWith("org/springframework/") || name.endsWith(".jar"));
        }
        Path fixtures = plugin.getParent().resolve("packaged-fixtures");
        Path base = fixtures.resolve("base.qea"), renamed = fixtures.resolve("renamed.qea");
        assertThat(base).isRegularFile();
        assertThat(renamed).isRegularFile();
        String baseDigest = PackagedPluginSupport.sha256(base), renamedDigest = PackagedPluginSupport.sha256(renamed);
        assertThat(renamedDigest).isNotEqualTo(baseDigest);
        String pluginDigest = PackagedPluginSupport.sha256(plugin);
        Path installed = Files.createDirectory(temporary.resolve("sparx-installed"));
        Files.copy(plugin, installed.resolve("sparx.jar"));
        Path state = temporary.resolve("sparx-retained-state");
        var arguments = List.of("--taxonomy.features.multi-repository-api.enabled=true",
                "--spring.datasource.url=jdbc:hsqldb:file:" + state.resolve("database") + ";shutdown=true",
                "--spring.jpa.hibernate.ddl-auto=update");
        SparxQeaPackagedChecks.RetainedState retained;
        try (var app = PackagedPluginSupport.start(host, installed, host.getParent().resolve("features"),
                state, arguments)) {
            retained = SparxQeaPackagedChecks.reviewedImports(app, base, renamed, pluginVersion, pluginDigest);
        }
        Files.copy(state.resolve("application.log"), state.resolve("application-with-plugin.log"));
        Path absent = Files.createDirectory(temporary.resolve("sparx-absent"));
        try (var app = PackagedPluginSupport.start(host, absent, host.getParent().resolve("features"),
                state, arguments)) {
            SparxQeaPackagedChecks.absentPlugin(app, retained, renamed);
        }
        assertThat(PackagedPluginSupport.sha256(base)).isEqualTo(baseDigest);
        assertThat(PackagedPluginSupport.sha256(renamed)).isEqualTo(renamedDigest);
        assertThat(PackagedPluginSupport.sha256(host)).isEqualTo(original);
        Files.writeString(root.resolve("taxonomy-build/target/sparx-packaging-evidence.txt"),
                "host.sha256=" + original + "\nsparx.sha256=" + pluginDigest
                        + "\nindependent.emptyRepository=true\nfixtures=synthetic\nhttp.reviewedImport=completed"
                        + "\nhttp.repeat=unchanged\nhttp.renameMove=stable-identities\npackageAndViewMembership=independent"
                        + "\nwithout.plugin=ready,profileUnavailable\nhttp.removal=retained-architecture-identities-history-revision"
                        + "\nhttp.absentPreview=PROFILE_VERSION_CHANGED\nhttp.absentCreate=UNKNOWN_CONNECTOR\n");
    }

    @Test void externalSparxQeaV2JarExportsReviewedFilesAndRetainsDownloadsAcrossPluginChanges() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path report = root.resolve("taxonomy-build/target/sparx-file-export-evidence.json");
        Files.deleteIfExists(report);
        Path host = PackagedPluginSupport.application(root);
        String hostSha = PackagedPluginSupport.sha256(host);
        Path plugin = PackagedPluginSupport.buildIndependentPlugin(root, temporary,
                "taxonomy-sparx-plugin", 25, "sparx-writer-sdk-verification.txt");
        String pluginSha = PackagedPluginSupport.sha256(plugin), pluginVersion;
        try (var jar = new JarFile(plugin.toFile())) {
            var manifest = jar.getManifest().getMainAttributes();
            assertThat(manifest.getValue("Plugin-Id")).isEqualTo("taxonomy.sparx");
            assertThat(manifest.getValue("Taxonomy-Plugin-Mode")).isEqualTo("STARTUP");
            assertThat(manifest.getValue("Plugin-Requires")).contains("1.5.0");
            pluginVersion = manifest.getValue("Plugin-Version");
            assertThat(pluginVersion).isNotBlank();
        }
        Path fixtures = plugin.getParent().resolve("packaged-fixtures");
        Path base = fixtures.resolve("base.qea"), renamed = fixtures.resolve("renamed.qea");
        String operatorTemplate = System.getProperty("taxonomy.sparx.template");
        boolean supplied = operatorTemplate != null && !operatorTemplate.isBlank();
        Path template = supplied ? Path.of(operatorTemplate).toAbsolutePath() : fixtures.resolve("empty-template.qea");
        for (Path file : List.of(base, renamed, template)) assertThat(file).isRegularFile();
        String baseSha = PackagedPluginSupport.sha256(base), templateSha = PackagedPluginSupport.sha256(template);
        Path installed = Files.createDirectory(temporary.resolve("sparx-writer-installed"));
        Files.copy(plugin, installed.resolve("sparx.jar"));
        Path state = temporary.resolve("sparx-writer-retained-state");
        var arguments = List.of("--taxonomy.features.multi-repository-api.enabled=true",
                "--spring.datasource.url=jdbc:hsqldb:file:" + state.resolve("database") + ";shutdown=true",
                "--spring.jpa.hibernate.ddl-auto=update");
        SparxQeaFilePackagedChecks.RetainedFiles retained;
        try (var app = PackagedPluginSupport.start(host, installed, host.getParent().resolve("features"), state, arguments)) {
            retained = SparxQeaFilePackagedChecks.reviewedExports(app, base, renamed, template,
                    temporary.resolve("writer-downloads"), pluginVersion, pluginSha);
        }
        Files.copy(state.resolve("application.log"), state.resolve("application-export.log"));
        try (var app = PackagedPluginSupport.start(host, installed, host.getParent().resolve("features"), state, arguments)) {
            retained = SparxQeaFilePackagedChecks.resumeAfterRestart(app, retained);
        }
        Files.copy(state.resolve("application.log"), state.resolve("application-restart.log"));

        Path replaced = Files.createDirectory(temporary.resolve("sparx-writer-replaced"));
        Path replacement = replaced.resolve("sparx.jar");
        copyWithArtifactMarker(plugin, replacement);
        assertThat(PackagedPluginSupport.sha256(replacement)).isNotEqualTo(pluginSha);
        try (var app = PackagedPluginSupport.start(host, replaced, host.getParent().resolve("features"), state, arguments)) {
            SparxQeaFilePackagedChecks.replacedPluginRejectsRetainedReview(app, retained);
        }
        Files.copy(state.resolve("application.log"), state.resolve("application-replacement.log"));
        Path absent = Files.createDirectory(temporary.resolve("sparx-writer-absent"));
        try (var app = PackagedPluginSupport.start(host, absent, host.getParent().resolve("features"), state, arguments)) {
            SparxQeaFilePackagedChecks.absentPluginRetainsFiles(app, retained);
        }
        assertThat(PackagedPluginSupport.sha256(template)).as("The supplied template remains untouched").isEqualTo(templateSha);
        assertThat(PackagedPluginSupport.sha256(base)).as("The imported snapshot remains untouched").isEqualTo(baseSha);
        assertThat(PackagedPluginSupport.sha256(plugin)).isEqualTo(pluginSha);
        assertThat(PackagedPluginSupport.sha256(host)).isEqualTo(hostSha);
        var evidence = SparxQeaFilePackagedChecks.evidence(retained, hostSha, pluginSha, template, supplied);
        String operatorOutput = System.getProperty("taxonomy.sparx.qa.output");
        if (operatorOutput != null && !operatorOutput.isBlank())
            SparxQeaFilePackagedChecks.retainOperatorOutputs(Path.of(operatorOutput).toAbsolutePath(), retained, evidence);
        Files.writeString(report,
                tools.jackson.databind.json.JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(evidence) + "\n");
    }

    /** Changes only a test copy, retaining the same plugin ID/version and every original class/resource. */
    private static void copyWithArtifactMarker(Path source, Path target) throws Exception {
        try (var input = new JarFile(source.toFile());
             var output = new java.util.jar.JarOutputStream(Files.newOutputStream(target))) {
            for (var entry : input.stream().toList()) {
                var copy = new java.util.jar.JarEntry(entry.getName()); copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                if (!entry.isDirectory()) try (var content = input.getInputStream(entry)) { content.transferTo(output); }
                output.closeEntry();
            }
            output.putNextEntry(new java.util.jar.JarEntry("META-INF/packaged-artifact-change.txt"));
            output.write("Packaged test replacement with unchanged plugin identity\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.closeEntry();
        }
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
            assertThat(app.request("GET", "/api/extensions", null).body()).doesNotContain("mermaid", "leanix", "alfabet");
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
