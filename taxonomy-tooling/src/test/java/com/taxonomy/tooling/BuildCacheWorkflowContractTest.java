package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class BuildCacheWorkflowContractTest {
    @Test void exportedDockerDependenciesPrecedeAllSourcesAndCommitArguments() throws Exception {
        String docker = Files.readString(root().resolve("Dockerfile"));
        int seed = docker.indexOf("org.apache.maven.plugins:maven-dependency-plugin:3.11.0:go-offline");
        assertThat(seed).as("external dependencies live in an exportable image layer").isPositive();
        assertThat(seed).isLessThan(docker.indexOf("COPY taxonomy-tooling/src"));
        assertThat(seed).isLessThan(docker.indexOf("ARG VCS_REF"));
        assertThat(docker).contains("-DexcludeReactor=true", "-DexcludeGroupIds=com.taxonomy");
        assertThat(docker).doesNotContain("--mount=type=cache,target=/root/.m2/repository");
        assertThat(docker).contains("./mvnw -q -DskipTests package");
    }
    @Test void everyDependencyCacheKeysStandalonePomAndWrapperInputs() throws Exception {
        try (var files = Files.list(root().resolve(".github/workflows"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String workflow = Files.readString(file);
                for (String step : workflow.split("(?m)^      - ")) {
                    if (step.contains("actions/setup-java@") && step.matches("(?s).*cache: ['\"]?maven.*")) {
                        assertThat(step).as("dependency inputs in %s", file.getFileName()).contains(
                                "cache-dependency-path:", ".github/ui-verification-pom.xml", "**/*-pom.xml",
                                "**/pom.xml", "**/.mvn/wrapper/maven-wrapper.properties", "**/.mvn/extensions.xml");
                    }
                }
            }
        }
    }
    @Test void browserFreeContractsDoNotRestoreBrowserBinariesAndAppWritesAreDailyMainOnly() throws Exception {
        String ci = Files.readString(root().resolve(".github/workflows/ci-cd.yml"));
        String contracts = ci.substring(ci.indexOf("\n  ui-contracts:"), ci.indexOf("\n  ui-shards:"));
        assertThat(contracts).doesNotContain("~/.cache/ms-playwright");
        String application = ci.substring(ci.indexOf("\n  application:"), ci.indexOf("\n  production-image:"));
        assertThat(application).contains("actions/cache/restore@", "actions/cache/save@", "date -u +%F",
                "github.ref == 'refs/heads/main'", "github.event_name == 'push'", "Cache entries found:", "Rejected artifact restorations:");
        assertThat(application).doesNotContain("maven-build-cache-app-v2-", "maven-build-cache-app-v3-${{ github.sha }}");
    }
    @Test void deliveryAndReleaseExportBothGraphsWithoutNewPrivileges() throws Exception {
        for (String name : List.of("delivery", "deploy-release")) {
            String workflow = Files.readString(root().resolve(".github/workflows/" + name + ".yml"));
            assertThat(workflow).contains("docker/setup-buildx-action@f87e5991a6d7451dcb8d9637bfbc97413f497069",
                    "driver: docker-container");
            for (String variant : List.of("standard", "onnx")) {
                assertThat(workflow).contains("cache-from: type=gha,scope=taxonomy-production-" + variant + "-v1,version=2",
                        "cache-to: type=gha,scope=taxonomy-production-" + variant + "-v1,version=2,mode=max,ignore-error=true");
            }
        }
    }
    @Test void everyCatalogueShardMustDisableRootAndApplicationResultCaching(@TempDir Path fixture) throws Exception {
        for (String path : List.of(".github/scripts/check-maven-build-cache.mjs", ".mvn/extensions.xml",
                ".mvn/maven-build-cache-config.xml", ".mvn/verification-suites.json", "taxonomy-coverage/pom.xml",
                "taxonomy-build/pom.xml", ".github/ui-verification-pom.xml", "taxonomy-app/pom.xml", "pom.xml",
                ".github/workflows/ci-cd.yml")) {
            Path destination = fixture.resolve(path); Files.createDirectories(destination.getParent());
            Files.copy(root().resolve(path), destination);
        }
        Files.writeString(fixture.resolve(".mvn/verification-suites.json"), """
                {"applicationTestShards":{"groups":{
                  "cache-fixture-a":{"profile":"ci-app-test-shard-cache-fixture-a"},
                  "cache-fixture-b":{"profile":"ci-app-test-shard-cache-fixture-b"}
                }}}
                """);
        assertThat(runCacheContract(fixture)).as("new shard must be rejected without cache guards").isNotZero();
        addFixtureProfiles(fixture.resolve("pom.xml"));
        assertThat(runCacheContract(fixture)).as("application guard is independently required").isNotZero();
        addFixtureProfiles(fixture.resolve("taxonomy-app/pom.xml"));
        assertThat(runCacheContract(fixture)).as("both guards permit the fresh test profiles").isZero();
    }
    private void addFixtureProfiles(Path pom) throws Exception {
        String profiles = """
                <profile><id>ci-app-test-shard-cache-fixture-a</id><properties><maven.build.cache.enabled>false</maven.build.cache.enabled></properties></profile>
                <profile><id>ci-app-test-shard-cache-fixture-b</id><properties><maven.build.cache.enabled>false</maven.build.cache.enabled></properties></profile>
                """;
        Files.writeString(pom, Files.readString(pom).replace("</profiles>", profiles + "</profiles>"));
    }
    private int runCacheContract(Path fixture) throws Exception {
        Path log = fixture.resolve("output");
        Process process = new ProcessBuilder(JavascriptJUnitBridge.nodeExecutable(),
                fixture.resolve(".github/scripts/check-maven-build-cache.mjs").toString())
                .directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }
    private Path root() { return ReleaseOrchestrationChecks.repositoryRoot(); }
}
