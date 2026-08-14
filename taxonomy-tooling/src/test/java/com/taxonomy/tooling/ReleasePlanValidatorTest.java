package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleasePlanValidatorTest {

    @Test
    void developmentPlanAllowsTheInternalSnapshotReactor(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");

        var result = validate(root);

        assertThat(result.pomCount()).isEqualTo(2);
    }

    @Test
    void discoversNestedDeclaredModulesAndInheritedProperties(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        Path aggregator = root.resolve("module-a/pom.xml");
        Files.writeString(aggregator,
                Files.readString(aggregator).replace(
                        "</project>",
                        "<properties><nested.external.version>9.0.0"
                                + "</nested.external.version></properties>"
                                + "<modules><module>nested</module></modules></project>"),
                StandardCharsets.UTF_8);
        writeModule(
                root,
                "module-a/nested",
                "com.taxonomy",
                "module-a",
                "1.3.0-SNAPSHOT",
                "nested",
                "",
                "",
                """
                        <dependency>
                          <groupId>example</groupId>
                          <artifactId>nested-stable</artifactId>
                          <version>${nested.external.version}</version>
                        </dependency>
                        """,
                "");

        assertThat(validate(root).pomCount()).isEqualTo(3);
    }

    @Test
    void rejectsNestedExternalSnapshotAndUnresolvedProperty(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        Path aggregator = root.resolve("module-a/pom.xml");
        Files.writeString(aggregator,
                Files.readString(aggregator).replace(
                        "</project>",
                        "<properties><nested.external.version>9.0.0-SNAPSHOT"
                                + "</nested.external.version></properties>"
                                + "<modules><module>nested</module></modules></project>"),
                StandardCharsets.UTF_8);
        writeModule(
                root,
                "module-a/nested",
                "com.taxonomy",
                "module-a",
                "1.3.0-SNAPSHOT",
                "nested",
                "",
                "",
                """
                        <dependency>
                          <groupId>example</groupId>
                          <artifactId>nested-unstable</artifactId>
                          <version>${nested.external.version}</version>
                        </dependency>
                        """,
                "");

        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("external dependency example:nested-unstable");

        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"),
                "", """
                        <dependency>
                          <groupId>example</groupId>
                          <artifactId>unknown</artifactId>
                          <version>${missing.version}</version>
                        </dependency>
                        """);
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("unresolved version property");
    }

    @Test
    void rejectsDuplicateCoordinatesAndMissingOrEscapingModules(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0",
                List.of("module-a", "module-b"), "", "");
        writeModule(root, "module-b", "com.taxonomy", "taxonomy",
                "1.3.0-SNAPSHOT", "module-a", "", "", "", "");
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("duplicate Maven reactor coordinate");

        Path missing = root.resolve("missing-case");
        Files.createDirectories(missing);
        writeProject(missing, "1.3.0-SNAPSHOT", "1.0.0",
                List.of("missing"), "", "");
        assertThatThrownBy(() -> validate(missing))
                .hasMessageContaining("does not exist");

        Path escaped = root.resolve("escaped-case");
        Files.createDirectories(escaped);
        writeProject(escaped, "1.3.0-SNAPSHOT", "1.0.0",
                List.of("../outside"), "", "");
        Files.createDirectories(root.resolve("outside"));
        Files.writeString(root.resolve("outside/pom.xml"), "<project/>");
        assertThatThrownBy(() -> validate(escaped))
                .hasMessageContaining("outside the repository");
    }

    @Test
    void ignoresUnrelatedPomOutsideTheDeclaredReactor(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        Files.createDirectories(root.resolve("examples"));
        Files.writeString(root.resolve("examples/pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>unrelated</artifactId>
                  <version>9.0.0-SNAPSHOT</version>
                </project>
                """);

        assertThat(validate(root).pomCount()).isEqualTo(2);
    }

    @Test
    void supportsMajorAdvanceAndReleaseAndAdvancedStates(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        assertThat(ReleasePlanValidator.validate(
                root,
                "1.3.0-SNAPSHOT",
                "1.3.0",
                "2.0.0-SNAPSHOT",
                "development",
                false).nextDevelopmentVersion()).isEqualTo("2.0.0-SNAPSHOT");

        writeProject(root, "1.3.0", "1.0.0", List.of("module-a"), "", "");
        assertThat(ReleasePlanValidator.validate(
                root, "1.3.0", "1.3.0", "1.3.1-SNAPSHOT",
                "release", false).state()).isEqualTo("release");

        writeProject(root, "1.3.1-SNAPSHOT", "1.0.0",
                List.of("module-a"), "", "");
        assertThat(ReleasePlanValidator.validate(
                root, "1.3.1-SNAPSHOT", "1.3.0", "1.3.1-SNAPSHOT",
                "advanced", false).state()).isEqualTo("advanced");
    }

    @Test
    void rejectsNonAdvancingVersionAndReactorVersionMismatch(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        assertThatThrownBy(() -> ReleasePlanValidator.validate(
                root, "1.3.0-SNAPSHOT", "1.3.0", "1.3.0-SNAPSHOT",
                "development", false))
                .hasMessageContaining("must be newer");

        writeModule(root, "module-a", "com.taxonomy", "taxonomy",
                "1.3.0-SNAPSHOT", "module-a", "org.other",
                "1.2.9-SNAPSHOT", "", "");
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("reactor version");
    }

    @Test
    void rejectsExternalSnapshotParentDependencyPluginAndExtension(
            @TempDir Path root) throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "9.0.0-SNAPSHOT",
                List.of("module-a"), "", "");
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("external parent org.example:external-parent");

        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"),
                "<external.version>9.0.0-SNAPSHOT</external.version>",
                """
                        <dependency><groupId>example</groupId>
                          <artifactId>unstable</artifactId>
                          <version>${external.version}</version></dependency>
                        """);
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("external dependency example:unstable");

        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        appendRoot(root, """
                <build><plugins><plugin><groupId>example</groupId>
                  <artifactId>unstable-plugin</artifactId>
                  <version>9.0.0-SNAPSHOT</version>
                </plugin></plugins></build>
                """);
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("external plugin example:unstable-plugin");

        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        appendRoot(root, """
                <build><extensions><extension><groupId>example</groupId>
                  <artifactId>unstable-extension</artifactId>
                  <version>9.0.0-SNAPSHOT</version>
                </extension></extensions></build>
                """);
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining(
                        "external build extension example:unstable-extension");
    }

    @Test
    void rejectsMavenReleasePluginAndStaleState(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        appendRoot(root, """
                <build><plugins><plugin>
                  <groupId>org.apache.maven.plugins</groupId>
                  <artifactId>maven-release-plugin</artifactId>
                  <version>3.1.1</version>
                </plugin></plugins></build>
                """);
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("second SCM release authority");

        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        Files.writeString(root.resolve("release.properties"), "stale");
        Files.writeString(root.resolve("module-a/pom.xml.releaseBackup"), "stale");
        assertThatThrownBy(() -> validate(root))
                .hasMessageContaining("stale Maven Release Plugin");
    }

    @Test
    void enforcesCleanOrdinaryAndLinkedWorktreeCheckouts(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        commitProject(root);
        assertThat(ReleasePlanValidator.validate(
                root, "1.3.0-SNAPSHOT", "1.3.0", "1.3.1-SNAPSHOT",
                "development", true).pomCount()).isEqualTo(2);

        Files.writeString(root.resolve("untracked.txt"), "dirty");
        assertThatThrownBy(() -> ReleasePlanValidator.validate(
                root, "1.3.0-SNAPSHOT", "1.3.0", "1.3.1-SNAPSHOT",
                "development", true))
                .hasMessageContaining("clean checkout");

        Files.delete(root.resolve("untracked.txt"));
        Path worktree = root.getParent().resolve("linked-worktree");
        TestGit.run(root, "worktree", "add", "-q", "-b", "qa-worktree",
                worktree.toString());
        Files.writeString(worktree.resolve("untracked.txt"), "dirty");
        assertThat(Files.isRegularFile(worktree.resolve(".git"))).isTrue();
        assertThatThrownBy(() -> ReleasePlanValidator.validate(
                worktree, "1.3.0-SNAPSHOT", "1.3.0", "1.3.1-SNAPSHOT",
                "development", true))
                .hasMessageContaining("clean checkout");
    }

    @Test
    void unresolvedReleaseArgumentIsReportedClearly(@TempDir Path root)
            throws Exception {
        writeProject(root, "1.3.0-SNAPSHOT", "1.0.0", List.of("module-a"), "", "");
        assertThatThrownBy(() -> ReleasePlanValidator.validate(
                root,
                "1.3.0-SNAPSHOT",
                "${releaseVersion}",
                "1.3.1-SNAPSHOT",
                "development",
                false))
                .hasMessageContaining("was not supplied");
    }

    private static ReleasePlanValidator.Result validate(Path root)
            throws Exception {
        return ReleasePlanValidator.validate(
                root,
                "1.3.0-SNAPSHOT",
                "1.3.0",
                "1.3.1-SNAPSHOT",
                "development",
                false);
    }

    private static void writeProject(
            Path root,
            String version,
            String externalParentVersion,
            List<String> modules,
            String properties,
            String dependency) throws Exception {
        Files.createDirectories(root);
        String moduleText = modules.stream()
                .map(module -> "<module>" + module + "</module>")
                .reduce("", String::concat);
        Files.writeString(root.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.example</groupId>
                    <artifactId>external-parent</artifactId>
                    <version>%s</version><relativePath/></parent>
                  <groupId>com.taxonomy</groupId><artifactId>taxonomy</artifactId>
                  <version>%s</version><packaging>pom</packaging>
                  <properties>%s</properties>
                  <modules>%s</modules>
                </project>
                """.formatted(externalParentVersion, version, properties, moduleText),
                StandardCharsets.UTF_8);
        for (String module : modules) {
            writeModule(root, module, "com.taxonomy", "taxonomy", version,
                    Path.of(module).getFileName().toString(), "", "",
                    dependency, "");
        }
    }

    private static void writeModule(
            Path root,
            String path,
            String parentGroup,
            String parentArtifact,
            String parentVersion,
            String artifact,
            String ownGroup,
            String ownVersion,
            String dependency,
            String extra) throws Exception {
        Path directory = root.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <parent><groupId>%s</groupId><artifactId>%s</artifactId>
                    <version>%s</version></parent>
                  %s<artifactId>%s</artifactId>%s
                  <dependencies>
                    <dependency><groupId>com.taxonomy</groupId>
                      <artifactId>taxonomy</artifactId>
                      <version>${project.version}</version></dependency>
                    %s
                  </dependencies>%s
                </project>
                """.formatted(
                        parentGroup,
                        parentArtifact,
                        parentVersion,
                        ownGroup.isBlank() ? "" : "<groupId>" + ownGroup + "</groupId>",
                        artifact,
                        ownVersion.isBlank() ? "" : "<version>" + ownVersion + "</version>",
                        dependency,
                        extra), StandardCharsets.UTF_8);
    }

    private static void appendRoot(Path root, String content) throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace(
                "</project>", content + "</project>"), StandardCharsets.UTF_8);
    }

    private static void commitProject(Path root) throws Exception {
        TestGit.run(root, "init", "-q");
        TestGit.run(root, "add", ".");
        TestGit.run(root, "-c", "user.name=Release QA",
                "-c", "user.email=qa@example.invalid",
                "commit", "-qm", "initial");
    }
}
