package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class SupplyChainPinPolicyTest {

    private static final String ACTION_SHA = "a".repeat(40);
    private static final String IMAGE_DIGEST = "b".repeat(64);

    private final SupplyChainPinPolicy policy = new SupplyChainPinPolicy();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsImmutableExternalReferencesAndIgnoresLocalOrVariableInputs(
            @TempDir Path root) throws Exception {
        write(root, ".github/workflows/ci.yml", """
                jobs:
                  test:
                    steps:
                      - uses: actions/checkout@%s # immutable external action
                      - uses: './.github/actions/local'
                """.formatted(ACTION_SHA));
        write(root, ".github/workflows/security.yaml", """
                steps:
                  - uses: "aquasecurity/trivy-action@%s"
                """.formatted(ACTION_SHA));
        write(root, "Dockerfile", """
                FROM eclipse-temurin@sha256:%s AS runtime
                FROM ${OPTIONAL_BASE}
                FROM scratch
                """.formatted(IMAGE_DIGEST));
        write(root, "docker-compose.prod.yml", """
                services:
                  app:
                    image: ghcr.io/example/taxonomy@sha256:%s
                  configured:
                    image: ${TAXONOMY_IMAGE}
                """.formatted(IMAGE_DIGEST));

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.checkedExternalActions()).isEqualTo(2);
        assertThat(inspection.checkedProductionImages()).isEqualTo(2);
        assertThat(inspection.violations()).isEmpty();
        assertThat(inspection.summary())
                .contains("2 actions, 2 images");
    }

    @Test
    void acceptsReuseOfPreviouslyDefinedDockerBuildStages(@TempDir Path root)
            throws Exception {
        write(root, "Dockerfile", """
                FROM eclipse-temurin@sha256:%s AS runtime
                FROM runtime AS onnx
                FROM onnx AS standard
                """.formatted(IMAGE_DIGEST));

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.checkedProductionImages()).isEqualTo(1);
        assertThat(inspection.violations()).isEmpty();
    }

    @Test
    void requiresDigestsForUnknownAndNotYetDefinedDockerBases(@TempDir Path root)
            throws Exception {
        write(root, "Dockerfile", """
                FROM future AS early
                FROM eclipse-temurin@sha256:%s AS future
                FROM unknown AS unknown
                """.formatted(IMAGE_DIGEST));

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.checkedProductionImages()).isEqualTo(3);
        assertThat(inspection.violations()).containsExactly(
                "Dockerfile:1: production build image is not digest-pinned: future",
                "Dockerfile:3: production build image is not digest-pinned: unknown");
    }

    @Test
    void reportsEveryMutableActionAndProductionImageWithItsSourceLine(
            @TempDir Path root) throws Exception {
        write(root, ".github/workflows/ci.yml", """
                name: CI
                steps:
                  - uses: actions/checkout@v4
                  - uses: docker/setup-buildx-action@main
                """);
        write(root, "Dockerfile", """
                # production build
                FROM eclipse-temurin:21-jre
                """);
        write(root, "docker-compose.prod.yml", """
                services:
                  app:
                    image: ghcr.io/example/taxonomy:latest
                """);

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.checkedExternalActions()).isEqualTo(2);
        assertThat(inspection.checkedProductionImages()).isEqualTo(2);
        assertThat(inspection.violations()).containsExactly(
                ".github/workflows/ci.yml:3: mutable action reference actions/checkout@v4",
                ".github/workflows/ci.yml:4: mutable action reference docker/setup-buildx-action@main",
                "Dockerfile:2: production build image is not digest-pinned: eclipse-temurin:21-jre",
                "docker-compose.prod.yml:3: production image is not digest-pinned: ghcr.io/example/taxonomy:latest");
        assertThat(inspection.summary())
                .startsWith("Supply-chain pinning violations:")
                .contains("actions/checkout@v4")
                .contains("taxonomy:latest");
    }

    @Test
    void ignoresCommentsAndUnrelatedYamlWhileCheckingBothWorkflowExtensions(
            @TempDir Path root) throws Exception {
        write(root, ".github/workflows/a.yaml", """
                # uses: actions/checkout@v4
                description: "uses: actions/checkout@v4"
                - uses: owner/action@%s
                """.formatted(ACTION_SHA));
        write(root, ".github/workflows/b.yml", """
                - uses: owner/other@%s
                """.formatted(ACTION_SHA));
        write(root, ".github/workflows/notes.txt", "- uses: owner/action@v1\n");

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.checkedExternalActions()).isEqualTo(2);
    }

    @Test
    void writesStableMachineReadableEvidenceForPassAndFailure(@TempDir Path root)
            throws Exception {
        Path report = root.resolve("target/supply-chain-pins.json");
        SupplyChainPinPolicy.Inspection passing =
                new SupplyChainPinPolicy.Inspection(true, 7, 3, java.util.List.of());

        policy.writeReport(report, passing);

        JsonNode passJson = objectMapper.readTree(Files.readString(report));
        assertThat(passJson.path("status").asText()).isEqualTo("PASS");
        assertThat(passJson.path("checkedExternalActions").asInt()).isEqualTo(7);
        assertThat(passJson.path("checkedProductionImages").asInt()).isEqualTo(3);
        assertThat(passJson.path("violations").isArray()).isTrue();
        assertThat(Files.readString(report)).endsWith("\n");

        SupplyChainPinPolicy.Inspection failing =
                new SupplyChainPinPolicy.Inspection(
                        false, 1, 0, java.util.List.of("mutable reference"));
        policy.writeReport(report, failing);
        JsonNode failJson = objectMapper.readTree(Files.readString(report));
        assertThat(failJson.path("status").asText()).isEqualTo("FAIL");
        assertThat(failJson.path("violations").get(0).asText())
                .isEqualTo("mutable reference");
    }

    @Test
    void failsClosedOnUnreadableWorkflowContent(@TempDir Path root) throws Exception {
        Path workflow = root.resolve(".github/workflows/invalid.yml");
        Files.createDirectories(workflow.getParent());
        Files.write(workflow, new byte[] {(byte) 0xC3, 0x28});

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(root))
                .withMessageContaining("Cannot read")
                .withMessageContaining("invalid.yml");
    }

    @Test
    void emptyRepositoryHasNoFalsePositive(@TempDir Path root) {
        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.checkedExternalActions()).isZero();
        assertThat(inspection.checkedProductionImages()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"evilghcr.io", "unapproved.ghcr.io", "evildocker.io",
            "unapproved.docker.io", "registry.example", "localhost", "localhost:5000", "ghcr.io:443"})
    void rejectsUnapprovedRegistryHostsInDeploymentImages(String registry, @TempDir Path root)
            throws Exception {
        write(root, "deploy/kubernetes/workload.yaml", """
                metadata: {name: namespace}
                ---
                spec:
                  template:
                    spec:
                      containers:
                        - {name: app, image: "%s/example/app:1"}
                """.formatted(registry));

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations()).singleElement()
                .asString().contains("deploy/kubernetes/workload.yaml", registry);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evilghcr.io", "unapproved.ghcr.io", "evildocker.io",
            "unapproved.docker.io", "registry.example", "localhost", "localhost:5000", "docker.io:443"})
    void rejectsUnapprovedRegistryHostsInHelmValueOverlays(String registry, @TempDir Path root)
            throws Exception {
        write(root, "deploy/helm/example/values.yaml", "image: {repository: ghcr.io/example/app}\n");
        write(root, "deploy/helm/example/values-preview.yml", """
                analysis:
                  workerSets:
                    - image:
                        repository: >-
                          %s/example/worker
                """.formatted(registry));

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations()).singleElement()
                .asString().contains("deploy/helm/example/values-preview.yml", registry);
    }

    @Test
    void acceptsExactApprovedRegistriesAndImplicitDockerHubOrLocalImages(@TempDir Path root)
            throws Exception {
        write(root, "deploy/kubernetes/workload.yaml", """
                spec:
                  initContainers:
                    - image: docker.io/library/alpine:3.23
                  containers:
                    - image: ghcr.io/example/app@sha256:%s
                    - image: postgres:16-alpine
                    - image: apache/artemis:2.53.0
                    - image: fixture-image-placeholder:render-required
                    - image: taxonomy-constrained-smoke:local
                """.formatted(IMAGE_DIGEST));
        write(root, "deploy/helm/example/values.yaml", """
                image: {repository: ghcr.io/example/app}
                analysis:
                  workerSets:
                    - image: {repository: docker.io/example/worker}
                    - image: {repository: example/implicit-dockerhub}
                """);

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isTrue();
        assertThat(inspection.violations()).isEmpty();
    }

    @Test
    void skipsUnrenderedHelmTemplatesButInspectsStaticYamlInOtherTemplateDirectories(
            @TempDir Path root) throws Exception {
        write(root, "deploy/helm/example/templates/workload.yaml", """
                {{- if .Values.enabled }}
                image: {{ .Values.image.repository }}
                {{- end }}
                """);
        write(root, "deploy/kubernetes/templates/workload.yaml", """
                spec: {containers: [{image: evilghcr.io/example/app:1}]}
                """);

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations()).singleElement()
                .asString().contains("deploy/kubernetes/templates/workload.yaml", "evilghcr.io");
    }

    @ParameterizedTest
    @ValueSource(strings = {"spec: [", "image: first\nimage: second\n",
            "image: !!javax.script.ScriptEngineManager {}"})
    void failsClosedOnMalformedOrAmbiguousDeploymentYaml(String content, @TempDir Path root)
            throws Exception {
        write(root, "deploy/helm/example/values-broken.yaml", content);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy.inspect(root))
                .withMessageContaining("values-broken.yaml");
    }

    @Test
    void inspectsImageReferencesInRecursiveYamlAliasesWithoutLooping(@TempDir Path root)
            throws Exception {
        write(root, "deploy/kubernetes/aliased.yaml", """
                configuration: &configuration
                  same: *configuration
                  image: evilghcr.io/example/app:1
                """);

        SupplyChainPinPolicy.Inspection inspection = policy.inspect(root);

        assertThat(inspection.passed()).isFalse();
        assertThat(inspection.violations()).singleElement()
                .asString().contains("deploy/kubernetes/aliased.yaml", "evilghcr.io");
    }

    private static Path write(Path root, String relative, String content)
            throws Exception {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
