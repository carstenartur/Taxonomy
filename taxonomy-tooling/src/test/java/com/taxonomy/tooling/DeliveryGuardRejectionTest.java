package com.taxonomy.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Keep the migrated release and delivery guards fail closed on the same real-file mutations. */
class DeliveryGuardRejectionTest {
  @TempDir Path directory;

  @ParameterizedTest(name = "{0}")
  @MethodSource("mutations")
  void rejectsUnsafeRepositoryMutation(Mutation mutation) throws Exception {
    copyFixture(root(), directory);
    var output = new ByteArrayOutputStream();
    assertThat(
            DeliveryGuards.run(
                mutation.command(), directory, new PrintStream(output), new PrintStream(output)))
        .as("Unchanged real-source fixture: %s", output)
        .isZero();
    Path file = directory.resolve(mutation.path());
    String original = Files.readString(file);
    assertThat(original).contains(mutation.needle());
    Files.writeString(file, original.replace(mutation.needle(), mutation.replacement()));
    output.reset();
    assertThat(
            DeliveryGuards.run(
                mutation.command(), directory, new PrintStream(output), new PrintStream(output)))
        .as("Guard must reject %s: %s", mutation.label(), output)
        .isEqualTo(1);
  }

  static Stream<Mutation> mutations() {
    String hardening = "check-delivery-hardening", release = "check-release-delivery-contract";
    String delivery = ".github/workflows/delivery.yml",
        workflow = ".github/workflows/deploy-release.yml";
    String verifier = "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java";
    return Stream.of(
        new Mutation(
            "atomic report replacement",
            hardening,
            delivery,
            "keep_files: false",
            "keep_files: true"),
        new Mutation(
            "mandatory Render evidence",
            hardening,
            delivery,
            "if-no-files-found: error",
            "if-no-files-found: warn"),
        new Mutation(
            "bounded deploy hook",
            hardening,
            delivery,
            "--connect-timeout 15 --max-time 90",
            "--connect-timeout 15 --max-time 0"),
        new Mutation(
            "enabled hook configuration",
            hardening,
            delivery,
            "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing",
            "configuration ignored"),
        new Mutation(
            "small deployment memory",
            hardening,
            "deploy/helm/taxonomy/values-small.yaml",
            "memory: 768Mi",
            "memory: 8192Mi"),
        new Mutation(
            "context path fetch",
            hardening,
            "taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js",
            "installBasePathAwareFetch",
            "installUnscopedFetch"),
        new Mutation(
            "tree viewport bound",
            hardening,
            "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css",
            "max-height: min(65vh, 42rem) !important;",
            "max-height: none !important;"),
        new Mutation(
            "final source verification",
            hardening,
            ".github/scripts/finalize-quality-evidence.sh",
            "--root target/quality-reports --expected-commit \"$GITHUB_SHA\"",
            "--root target/quality-reports --expected-commit unchecked"),
        new Mutation(
            "exact deploy commit",
            hardening,
            verifier,
            "query.put(\"ref\", expected)",
            "query.put(\"ref\", \"unverified\")"),
        new Mutation(
            "application root smoke", hardening, verifier, "root smoke test", "removed root check"),
        new Mutation(
            "Maven callback owner",
            hardening,
            ".github/scripts/ci-core-contracts.sh",
            "QualitySiteTest",
            "RemovedOwner"),
        new Mutation(
            "quality source provenance",
            hardening,
            "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
            "\"sourceTree\"",
            "\"unboundTree\""),
        new Mutation(
            "vulnerability severity gate", release, workflow, "exit-code: '1'", "exit-code: '0'"),
        new Mutation(
            "exact security workflow",
            release,
            ".github/scripts/verify-exact-release-gates.sh",
            "security-scan.yml",
            "nonsecurity.yml"),
        new Mutation(
            "reactor metadata staging",
            release,
            ".github/scripts/release.sh",
            "\n  stage_version_metadata\n",
            "\n  skip_version_metadata\n"),
        new Mutation(
            "bounded numeric archive retention",
            release,
            workflow,
            "retention-days: 7",
            "retention-days: 30"));
  }

  private static void copyFixture(Path root, Path destination) throws IOException {
    for (String path :
        List.of(
            ".github",
            "pom.xml",
            "taxonomy-tooling/pom.xml",
            "taxonomy-tooling/src",
            "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css",
            "taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js",
            "deploy/helm/taxonomy/values-small.yaml",
            "deploy/helm/taxonomy/values-rancher-rke2.yaml",
            "Dockerfile",
            ".mvn/javascript-junit-sources.txt")) {
      Path source = root.resolve(path), target = destination.resolve(path);
      if (Files.isDirectory(source)) {
        Files.walkFileTree(
            source,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult preVisitDirectory(
                  Path directory, BasicFileAttributes attributes) throws IOException {
                if (List.of("node_modules", "target", "__pycache__")
                    .contains(directory.getFileName().toString()))
                  return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                  throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
              }
            });
      } else {
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
      }
    }
  }

  private static Path root() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !Files.isRegularFile(root.resolve("taxonomy-tooling/pom.xml")))
      root = root.getParent();
    if (root == null) throw new IllegalStateException("Repository root not found");
    return root;
  }

  record Mutation(String label, String command, String path, String needle, String replacement) {
    @Override
    public String toString() {
      return label;
    }
  }
}
