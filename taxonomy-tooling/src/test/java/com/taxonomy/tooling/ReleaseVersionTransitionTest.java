package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseVersionTransitionTest {

    @Test
    void transitionsStandalonePluginsWithTheReactorForReleaseAndNextDevelopment(
            @TempDir Path fixture) throws Exception {
        prepareFixture(fixture);
        ReleaseMetadataUpdater.update(fixture, "1.5.1-SNAPSHOT", false, null);
        assertVersionState(fixture, "1.5.1-SNAPSHOT", false);

        transition(fixture, "1.5.1", true);
        transition(fixture, "1.5.2-SNAPSHOT", false);
    }

    private static void transition(Path fixture, String version, boolean release)
            throws Exception {
        Path log = fixture.resolve("versions-" + version + ".log");
        ProcessBuilder builder = new ProcessBuilder(
                "bash",
                fixture.resolve(".github/scripts/set-maven-versions.sh").toString(),
                version)
                .directory(fixture.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // Preserve the invoking build's Maven cache, proxy and settings environment.
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            String output = Files.readString(log);
            assertThat(finished)
                    .as("Maven version transition to %s timed out:%n%s", version, output)
                    .isTrue();
            assertThat(process.exitValue())
                    .as("Maven version transition to %s:%n%s", version, output)
                    .isZero();
        } finally {
            process.descendants().toList().reversed()
                    .forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }

        ReleaseMetadataUpdater.update(
                fixture, version, release, release ? LocalDate.of(2026, 10, 25) : null);
        assertVersionState(fixture, version, release);
    }

    private static void assertVersionState(
            Path fixture, String version, boolean release) throws Exception {
        assertThat(VersionStateVerifier.verify(
                fixture,
                release ? "release" : "development",
                version,
                release ? "v" + version : null).failures())
                .as("Coherent repository version state after transition to %s", version)
                .isEmpty();

        assertThat(XmlSupport.rootProjectVersion(fixture.resolve("pom.xml")))
                .isEqualTo(version);
        Element child = XmlSupport.parse(fixture.resolve("child/pom.xml"))
                .getDocumentElement();
        assertThat(XmlSupport.childText(XmlSupport.child(child, "parent"), "version"))
                .isEqualTo(version);
        assertThat(XmlSupport.childText(child, "version")).isEmpty();

        for (String artifact : List.of("taxonomy-mermaid-plugin", "taxonomy-leanix-plugin")) {
            Element plugin = XmlSupport.parse(
                    fixture.resolve("plugins/" + artifact + "/pom.xml"))
                    .getDocumentElement();
            assertThat(XmlSupport.childText(plugin, "version"))
                    .as("%s project version", artifact)
                    .isEqualTo(version);
            assertThat(XmlSupport.child(plugin, "parent"))
                    .as("%s remains independent of the host parent", artifact)
                    .isNull();
            assertThat(XmlSupport.childText(
                    XmlSupport.child(plugin, "properties"), "taxonomy.sdk.version"))
                    .isEqualTo("${project.version}");
            Element sdk = XmlSupport.children(
                    XmlSupport.child(plugin, "dependencies"), "dependency")
                    .stream()
                    .filter(dependency -> "taxonomy-extension-api".equals(
                            XmlSupport.childText(dependency, "artifactId")))
                    .findFirst().orElseThrow();
            assertThat(XmlSupport.childText(sdk, "version"))
                    .isEqualTo("${taxonomy.sdk.version}");
            assertThat(XmlSupport.childText(sdk, "scope")).isEqualTo("provided");
        }

        try (var files = Files.walk(fixture)) {
            assertThat(files.filter(path -> path.getFileName().toString()
                    .endsWith(".versionsBackup")).toList()).isEmpty();
        }
    }

    private static void prepareFixture(Path fixture) throws Exception {
        Path repository = repositoryRoot();
        for (String relative : List.of(
                "mvnw",
                ".mvn/wrapper/maven-wrapper.properties",
                ".github/scripts/set-maven-versions.sh",
                "CITATION.cff",
                "CITATION.md",
                ".zenodo.json",
                "codemeta.json",
                "deploy/helm/taxonomy/Chart.yaml")) {
            Path target = fixture.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(repository.resolve(relative), target);
        }
        assertThat(fixture.resolve("mvnw").toFile().setExecutable(true))
                .as("Fixture Maven wrapper is executable")
                .isTrue();

        write(fixture.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy</groupId>
                  <artifactId>taxonomy</artifactId>
                  <version>1.5.1-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>child</module>
                    <module>plugins/taxonomy-mermaid-plugin</module>
                  </modules>
                  <build><pluginManagement><plugins><plugin>
                    <groupId>org.codehaus.mojo</groupId>
                    <artifactId>versions-maven-plugin</artifactId>
                    <version>2.21.0</version>
                  </plugin></plugins></pluginManagement></build>
                </project>
                """);
        write(fixture.resolve("child/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.taxonomy</groupId>
                    <artifactId>taxonomy</artifactId>
                    <version>1.5.1-SNAPSHOT</version>
                  </parent>
                  <artifactId>taxonomy-extension-api</artifactId>
                </project>
                """);
        // Mermaid is aggregated without inheritance; LeanIX is outside the reactor.
        for (String artifact : List.of("taxonomy-mermaid-plugin", "taxonomy-leanix-plugin")) {
            write(fixture.resolve("plugins/" + artifact + "/pom.xml"), """
                    <project xmlns="http://maven.apache.org/POM/4.0.0">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>com.taxonomy</groupId>
                      <artifactId>%s</artifactId>
                      <version>1.5.1-SNAPSHOT</version>
                      <properties>
                        <taxonomy.sdk.version>${project.version}</taxonomy.sdk.version>
                      </properties>
                      <dependencies><dependency>
                        <groupId>com.taxonomy</groupId>
                        <artifactId>taxonomy-extension-api</artifactId>
                        <version>${taxonomy.sdk.version}</version>
                        <scope>provided</scope>
                      </dependency></dependencies>
                    </project>
                    """.formatted(artifact));
        }
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty(
                        "maven.multiModuleProjectDirectory", "."))
                .toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(".github/scripts/release.sh"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
