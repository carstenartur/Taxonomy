package com.taxonomy.tooling;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the real staging execution through a clean Maven lifecycle with a tiny distribution. */
class ReactorApplicationStagingTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stagesAfterCleanAndPackagingBeforeProductVerification(boolean skip,
            @TempDir Path fixture) throws Exception {
        Path root = ReleaseOrchestrationChecks.repositoryRoot();
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
        Element build = XmlSupport.child(XmlSupport.parse(root.resolve("taxonomy-build/pom.xml"))
                .getDocumentElement(), "build");
        Element exec = XmlSupport.children(XmlSupport.child(build, "plugins"), "plugin").stream()
                .filter(plugin -> "exec-maven-plugin".equals(XmlSupport.childText(plugin, "artifactId")))
                .findFirst().orElseThrow();
        Element staging = XmlSupport.children(XmlSupport.child(exec, "executions"), "execution").stream()
                .filter(execution -> "stage-reactor-interoperability-application".equals(
                        XmlSupport.childText(execution, "id"))).findFirst().orElse(null);
        assertThat(staging).as("The reactor must provide the manifest before product tests start").isNotNull();
        StringWriter executionXml = new StringWriter();
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty("omit-xml-declaration", "yes");
        transformer.transform(new DOMSource(staging), new StreamResult(executionXml));
        String execVersion = XmlSupport.childText(XmlSupport.child(XmlSupport.parse(root.resolve("pom.xml"))
                .getDocumentElement(), "properties"), "exec-maven-plugin.version");
        for (String relative : List.of("mvnw", "mvnw.cmd", ".mvn/wrapper/maven-wrapper.properties",
                ".github/scripts/stage-ui-application.sh")) {
            Path target = fixture.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(root.resolve(relative), target);
        }
        if (!windows) assertThat(fixture.resolve("mvnw").toFile().setExecutable(true)).isTrue();
        Files.writeString(fixture.resolve("package-fixture.sh"), """
                set -euo pipefail
                mkdir -p taxonomy-app/target/{plugins,features}
                printf host > taxonomy-app/target/taxonomy-app-1.5.1.jar
                printf plugin > taxonomy-app/target/plugins/taxonomy-mermaid-plugin-1.5.1.jar
                for id in analysis architecture interop portfolio reporting templates; do
                  printf '%s' "$id" > "taxonomy-app/target/features/taxonomy-${id}-1.5.1.jar"
                done
                """);
        Files.writeString(fixture.resolve("consume-fixture.sh"), """
                set -euo pipefail
                if [[ "$EXPECT_STAGING" == true ]]; then
                  test "$(jq -r .sourceCommit target/ui-application/manifest.json)" = "$GITHUB_SHA"
                  test "$(jq -r .sourceTree target/ui-application/manifest.json)" = "$(git rev-parse HEAD^{tree})"
                  (cd target/ui-application && sha256sum -c SHA256SUMS)
                else
                  test ! -e target/ui-application/manifest.json
                fi
                """);
        Files.writeString(fixture.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>
                  <artifactId>reactor-staging</artifactId><version>1</version><packaging>pom</packaging>
                  <build><plugins><plugin>
                    <groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId>
                    <version>%s</version><executions>
                      <execution><id>package-fixture</id><phase>package</phase><goals><goal>exec</goal></goals>
                        <configuration><executable>bash</executable><arguments><argument>package-fixture.sh</argument></arguments></configuration>
                      </execution>
                      %s
                      <execution><id>consume-fixture</id><phase>integration-test</phase><goals><goal>exec</goal></goals>
                        <configuration><executable>bash</executable><arguments><argument>consume-fixture.sh</argument></arguments></configuration>
                      </execution>
                    </executions>
                  </plugin></plugins></build>
                </project>
                """.formatted(execVersion, executionXml.toString()
                        .replace(" xmlns=\"http://maven.apache.org/POM/4.0.0\"", "")));
        Files.writeString(fixture.resolve(".gitignore"), "target/\n*.log\n");
        run(fixture, "git", "init", "-q");
        run(fixture, "git", "add", ".");
        run(fixture, "git", "-c", "user.name=Staging Test", "-c", "user.email=test@example.invalid",
                "commit", "-qm", "release fixture");
        String commit = run(fixture, "git", "rev-parse", "HEAD").strip();
        Files.createDirectories(fixture.resolve("target/ui-application"));
        Files.writeString(fixture.resolve("target/ui-application/manifest.json"), "stale before clean");
        Path log = fixture.resolve("maven.log");
        List<String> command = new ArrayList<>(windows
                ? List.of("cmd.exe", "/d", "/c", "mvnw.cmd")
                : List.of(fixture.resolve("mvnw").toString()));
        command.addAll(List.of("-B", "-ntp", "clean", "verify", "-Dtaxonomy.interoperability.skip=" + skip));
        ProcessBuilder builder = new ProcessBuilder(command).directory(fixture.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.environment().put("GITHUB_SHA", commit);
        builder.environment().put("EXPECT_STAGING", Boolean.toString(!skip));
        Process process = builder.start();
        try {
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("Fixture Maven lifecycle timed out").isTrue();
            assertThat(process.exitValue()).as(Files.readString(log)).isZero();
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String run(Path root, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        return output;
    }
}
