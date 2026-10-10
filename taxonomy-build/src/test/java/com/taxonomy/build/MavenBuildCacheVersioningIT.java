package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

class MavenBuildCacheVersioningIT {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    @Test
    void versionChangesRebuildMetadataWhileSameVersionStillUsesTheCache() throws Exception {
        Path root = PackagedPluginSupport.repository();
        Path project = Files.createDirectories(temporary.resolve("project"));
        Files.createDirectories(project.resolve(".mvn"));
        for (String config : List.of("extensions.xml", "maven-build-cache-config.xml")) {
            Files.copy(root.resolve(".mvn/" + config), project.resolve(".mvn/" + config));
        }
        Files.createDirectories(project.resolve("src/main/java/example"));
        Files.writeString(project.resolve("src/main/java/example/Feature.java"),
                "package example; public final class Feature {}\n");
        Files.createDirectories(project.resolve("src/main/resources"));
        Files.writeString(project.resolve("src/main/resources/version.txt"), "${project.version}\n");

        build(project, "1.4.1-SNAPSHOT", "old-version");
        assertVersion(project, "1.4.1-SNAPSHOT");

        for (String version : List.of("1.5.0-SNAPSHOT", "1.5.0", "1.5.1-SNAPSHOT")) {
            String changed = build(project, version, version);
            assertVersion(project, version);
            assertThat(changed).as("Version %s must not restore a previous version's build", version)
                    .doesNotContain("Found cached build, restoring");
        }

        String repeated = build(project, "1.5.1-SNAPSHOT", "same-version");
        assertThat(repeated).as("Unchanged versions must still benefit from the real Maven cache")
                .contains("Found cached build, restoring");
        assertVersion(project, "1.5.1-SNAPSHOT");
    }

    private String build(Path project, String version, String stage) throws Exception {
        // Remove outputs, not the cache: every restored artifact must come from Maven.
        Path target = project.resolve("target");
        if (Files.exists(target)) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.taxonomy.cache.fixture</groupId>
                  <artifactId>feature</artifactId>
                  <version>%s</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                  <build>
                    <finalName>feature</finalName>
                    <resources><resource><directory>src/main/resources</directory><filtering>true</filtering></resource></resources>
                    <plugins>
                      <plugin><artifactId>maven-resources-plugin</artifactId><version>3.5.0</version></plugin>
                      <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.15.0</version></plugin>
                      <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.6</version></plugin>
                      <plugin>
                        <artifactId>maven-jar-plugin</artifactId><version>3.5.1</version>
                        <configuration><archive><manifest>
                          <addDefaultImplementationEntries>true</addDefaultImplementationEntries>
                        </manifest></archive></configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """.formatted(version));
        String executable = System.getProperty("taxonomy.maven.executable");
        assertThat(executable).as("Failsafe supplies the running Maven distribution").isNotBlank();
        var command = new ArrayList<>(List.of(executable, "-B", "-ntp", "package",
                "-Dmaven.build.cache.location=" + temporary.resolve("cache"),
                "-Dmaven.build.cache.restoreOnDiskArtifacts=true",
                "-Dmaven.build.cache.remote.enabled=false"));
        String settings = System.getProperty("taxonomy.maven.user-settings");
        if (settings != null && Files.isRegularFile(Path.of(settings))) command.addAll(List.of("-s", settings));
        Path log = temporary.resolve(stage + ".log");
        var builder = new ProcessBuilder(command).directory(project.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().remove("MAVEN_PROJECTBASEDIR");
        builder.environment().remove("MAVEN_MULTI_MODULE_PROJECT_DIRECTORY");
        var process = builder.start();
        boolean finished = process.waitFor(2, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        String output = Files.readString(log);
        assertThat(finished).as("Maven fixture %s timed out:%n%s", stage, output).isTrue();
        assertThat(process.exitValue()).as("Maven fixture %s:%n%s", stage, output).isZero();
        return output;
    }

    private void assertVersion(Path project, String version) throws Exception {
        try (var jar = new JarFile(project.resolve("target/feature.jar").toFile())) {
            var manifest = jar.getManifest().getMainAttributes();
            var properties = new Properties();
            try (var input = jar.getInputStream(jar.getJarEntry(
                    "META-INF/maven/com.taxonomy.cache.fixture/feature/pom.properties"))) {
                properties.load(input);
            }
            String filtered;
            try (var input = jar.getInputStream(jar.getJarEntry("version.txt"))) {
                filtered = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            }
            assertSoftly(check -> {
                check.assertThat(manifest.getValue("Implementation-Version")).as("host/feature identity").isEqualTo(version);
                check.assertThat(properties.getProperty("version")).as("Maven artifact identity").isEqualTo(version);
                check.assertThat(filtered).as("filtered version resource").isEqualTo(version);
            });
            assertThat(jar.getJarEntry("example/Feature.class")).isNotNull();
        }
        assertThat(Files.readString(project.resolve("target/classes/version.txt")).strip())
                .as("physical classes restored for downstream reactor consumers").isEqualTo(version);
    }
}
