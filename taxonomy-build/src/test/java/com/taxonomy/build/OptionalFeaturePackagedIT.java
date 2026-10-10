package com.taxonomy.build;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

/** Physically absent feature JARs; every combination uses the same executable host. */
class OptionalFeaturePackagedIT {
    static final List<String> FEATURES = List.of("templates", "architecture", "reporting", "analysis", "portfolio", "interop");
    @TempDir Path temporary;
    static Stream<Set<String>> installations() {
        return Stream.of(Set.of(), Set.of("templates"), Set.of("architecture"), Set.of("interop"),
                Set.of("templates", "reporting"),
                Set.of("templates", "architecture", "reporting"),
                Set.of("templates", "architecture", "reporting", "analysis", "portfolio"), Set.copyOf(FEATURES));
    }
    @Test void invalidInstallationFailsBeforeOpeningTheDatabaseOrServing() throws Exception {
        Path root = PackagedPluginSupport.repository(), host = PackagedPluginSupport.application(root);
        Path features = Files.createDirectory(temporary.resolve("invalid features"));
        Path reporting = PackagedPluginSupport.artifact(root.resolve("taxonomy-reporting/target"), "taxonomy-reporting-");
        Files.copy(reporting, features.resolve(reporting.getFileName()));
        Path database = temporary.resolve("must-not-exist/database");
        Path log = temporary.resolve("preflight.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-Dloader.path=" + features, "-jar", host.toString(), "--server.port=0",
                "--spring.datasource.url=jdbc:hsqldb:file:" + database)
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isNotZero();
            assertThat(Files.readString(log)).contains("reporting requires templates")
                    .doesNotContain("NoClassDefFoundError", "Tomcat started", "HikariPool");
            assertThat(Files.exists(database.getParent())).isFalse();
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(10, TimeUnit.SECONDS); } }
    }
    @Test void removingAndReinstallingFeatureJarsRetainsDatabaseAndGitHistory() throws Exception {
        Path root=PackagedPluginSupport.repository(),host=PackagedPluginSupport.application(root);
        Path full=host.getParent().resolve("features"),core=Files.createDirectory(temporary.resolve("core"));
        Path plugins=Files.createDirectory(temporary.resolve("empty-plugins")),state=temporary.resolve("retained-state");
        var options=List.of("--spring.datasource.url=jdbc:hsqldb:file:"+state.resolve("database")+";shutdown=true",
                "--spring.jpa.hibernate.ddl-auto=update");
        String templates,history;
        try(var app=PackagedPluginSupport.start(host,plugins,full,state,options)) {
            var response=app.request("GET","/api/admin/document-templates",null);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);templates=response.body();
            var responseHistory=app.request("GET","/api/admin/document-templates/decision-rationale-report/history",null);
            assertThat(responseHistory.statusCode()).isEqualTo(200);history=responseHistory.body();
            assertThat(history).contains("commit");
        }
        try(var app=PackagedPluginSupport.start(host,plugins,core,state,options)) {
            assertThat(app.request("GET","/api/taxonomy",null).statusCode()).isEqualTo(200);
            assertThat(app.request("GET","/api/admin/document-templates",null).statusCode()).isEqualTo(404);
            var capabilities=new tools.jackson.databind.json.JsonMapper().readTree(app.request("GET","/api/capabilities",null).body());
            assertThat(capabilities.path("features").size()).isZero();
        }
        try(var app=PackagedPluginSupport.start(host,plugins,full,state,options)) {
            assertThat(app.request("GET","/api/admin/document-templates",null).body()).isEqualTo(templates);
            assertThat(app.request("GET","/api/admin/document-templates/decision-rationale-report/history",null).body()).isEqualTo(history);
        }
    }

    @ParameterizedTest(name = "installed={0}") @MethodSource("installations")
    void onlyInstalledFeaturesContributeRoutesAndDataAdapters(Set<String> selected) throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path host = PackagedPluginSupport.application(root);
        String original = PackagedPluginSupport.sha256(host);
        try (var jar = new JarFile(host.toFile())) {
            var entries = jar.stream().map(e -> e.getName()).toList();
            for (String id : FEATURES) assertThat(entries).as("Optional implementation %s must be outside the host", id)
                    .noneMatch(n -> n.matches("BOOT-INF/lib/taxonomy-" + id + "-[0-9].*\\.jar"));
        }
        Path features = Files.createDirectory(temporary.resolve("selected features"));
        for (String id : selected) {
            Path artifact = PackagedPluginSupport.artifact(root.resolve("taxonomy-" + id + "/target"), "taxonomy-" + id + "-");
            Files.copy(artifact, features.resolve(artifact.getFileName()));
        }
        try (var files = Files.list(features)) { assertThat(files.count()).isEqualTo(selected.size()); }
        Path plugins = Files.createDirectory(temporary.resolve("plugins"));
        try (var app = PackagedPluginSupport.start(host, plugins, features, temporary.resolve("state"))) {
            assertThat(app.request("GET", "/api/taxonomy", null).statusCode()).isEqualTo(200);
            assertThat(app.request("GET", "/api/status/startup", null).statusCode()).isEqualTo(200);
            assertThat(app.request("GET", "/api/admin/document-templates", null).statusCode()).isEqualTo(selected.contains("templates") ? 200 : 404);
            assertThat(app.request("GET", "/api/graph/apqc-hierarchy", null).statusCode()).isEqualTo(selected.contains("architecture") ? 200 : 404);
            var workspace = app.request("GET", "/api/workspace/current", null);
            assertThat(workspace.statusCode()).as(workspace.body() + "\n" + Files.readString(app.log())).isEqualTo(200);
            String workspaceId = new tools.jackson.databind.json.JsonMapper().readTree(workspace.body()).path("workspaceId").asText();
            assertThat(workspaceId).isNotBlank();
            var profiles = app.request("GET", "/api/integrations/profiles", null, Map.of("X-Taxonomy-Workspace-Id", workspaceId));
            assertThat(profiles.statusCode()).as(profiles.body()).isEqualTo(selected.contains("interop") ? 200 : 404);
            if (selected.contains("templates")) {
                var dav = app.request("OPTIONS", "/dav/templates/", null);
                assertThat(dav.statusCode()).as(dav.body()).isEqualTo(200);
                assertThat(dav.headers().firstValue("DAV")).hasValue("1, 2");
                var properties = app.request("PROPFIND", "/dav/templates/", null, Map.of("Depth", "1"));
                assertThat(properties.statusCode()).as(properties.body()).isEqualTo(207);
            }
            if (selected.contains("reporting")) {
                assertThat(app.request("GET", "/admin/document-templates/decision-rationale-report/test.docx", null).statusCode()).isEqualTo(200);
            }
        }
        assertThat(PackagedPluginSupport.sha256(host)).isEqualTo(original);
    }
}
