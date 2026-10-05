package com.taxonomy.build;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real Helm renders under the normal Maven selector, including #638 network isolation. */
class HelmArtemisContractTest {
    @TempDir Path temporary;
    private Path root;

    @BeforeEach
    void helmPrerequisite() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("deploy/helm/taxonomy/Chart.yaml"))) {
            root = root.getParent();
        }
        assertThat(root).as("repository root").isNotNull();
        boolean available;
        try {
            available = run(List.of("helm", "version", "--short")).exitCode() == 0;
        } catch (IOException missing) {
            available = false;
        }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            assertThat(available).as("Canonical CI must install Helm before Maven verification").isTrue();
        }
        assumeTrue(available, "Helm unavailable locally; canonical CI requires real chart renders");
    }

    @Test
    void localCompatibilityKeepsOneWebWorkloadAndNoBroker() throws Exception {
        List<Map<String, Object>> docs = render(false);
        assertThat(kind(docs, "Deployment")).hasSize(1);
        assertThat(kind(docs, "Service")).hasSize(1);
        Map<String, Object> environment = environment(kind(docs, "Deployment").getFirst());
        assertThat(environment).containsEntry("TAXONOMY_ANALYSIS_TRANSPORT_MODE", "local")
                .containsEntry("TAXONOMY_ANALYSIS_RUNTIME_ROLE", "all")
                .doesNotContainKey("TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL");
    }

    @Test
    void independentWorkerSetsNeverReceiveWebIngressAndShareOnlyReviewedBrokerEgress() throws Exception {
        List<Map<String, Object>> docs = render(true);
        List<Map<String, Object>> deployments = kind(docs, "Deployment");
        assertThat(deployments).hasSize(3);
        Map<String, Object> web = named(deployments, "taxonomy-taxonomy");
        Map<String, Object> cp = named(deployments, "taxonomy-taxonomy-worker-cp");
        Map<String, Object> general = named(deployments, "taxonomy-taxonomy-worker-general");
        assertThat(environment(web)).containsEntry("TAXONOMY_ANALYSIS_RUNTIME_ROLE", "coordinator")
                .containsEntry("TAXONOMY_ANALYSIS_WORKER_ENABLED", "false")
                .containsEntry("MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE", "readinessState,taxonomy");
        assertThat(environment(cp)).containsEntry("TAXONOMY_ANALYSIS_RUNTIME_ROLE", "worker")
                .containsEntry("TAXONOMY_ANALYSIS_WORKER_SHARDS", "CP")
                .containsEntry("MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE", "readinessState,analysisBroker");
        assertThat(environment(general)).containsEntry("TAXONOMY_ANALYSIS_WORKER_SHARDS", "BP,BR,CI,CO,CR,IP,UA");
        assertThat(map(cp, "spec").get("replicas")).isEqualTo(2);
        assertThat(map(general, "spec").get("replicas")).isEqualTo(1);
        assertThat(kind(docs, "Service")).hasSize(1);
        Map<String, Object> serviceSelector = map(kind(docs, "Service").getFirst(), "spec", "selector");
        assertThat(matches(serviceSelector, labels(web))).isTrue();
        assertThat(matches(serviceSelector, labels(cp))).isFalse();
        assertThat(matches(map(web, "spec", "selector", "matchLabels"), labels(cp))).isFalse();
        for (Map<String, Object> deployment : deployments) {
            List<Map<String, Object>> policies = kind(docs, "NetworkPolicy").stream()
                    .filter(policy -> matches(map(policy, "spec", "podSelector", "matchLabels"), labels(deployment)))
                    .toList();
            assertThat(policies).as("policy covers %s", map(deployment, "metadata").get("name")).hasSize(1);
            List<Map<String, Object>> egress = list(map(policies.getFirst(), "spec").get("egress"));
            assertThat(egress).hasSize(2); // DNS and the explicit broker peer, no blanket egress.
            Map<String, Object> broker = egress.stream().filter(rule -> list(rule.get("ports")).stream()
                    .anyMatch(port -> Integer.valueOf(61617).equals(port.get("port")))).findFirst().orElseThrow();
            assertThat(map(list(broker.get("to")).getFirst(), "namespaceSelector", "matchLabels"))
                    .containsEntry("kubernetes.io/metadata.name", "messaging");
            assertThat(map(list(broker.get("to")).getFirst(), "podSelector", "matchLabels"))
                    .containsEntry("app.kubernetes.io/name", "artemis");
            if (deployment != web) assertThat(list(map(policies.getFirst(), "spec").get("ingress"))).isEmpty();
            assertThat(environment(deployment).get("TAXONOMY_ANALYSIS_ARTEMIS_PASSWORD"))
                    .isEqualTo(Map.of("secretKeyRef", Map.of("name", "taxonomy-artemis", "key", "ARTEMIS_PASSWORD", "optional", false)));
        }
        assertThat(kind(docs, "Ingress")).isEmpty();
        assertThat(kind(docs, "Secret")).isEmpty();
        assertThat(kind(docs, "StatefulSet")).isEmpty(); // Chart does not operate a broker.
        List<Map<String, Object>> scaled = render(true, "--set", "analysis.workerSets[0].replicas=5");
        assertThat(map(named(kind(scaled, "Deployment"), "taxonomy-taxonomy-worker-cp"), "spec").get("replicas")).isEqualTo(5);
        assertThat(named(kind(scaled, "Deployment"), "taxonomy-taxonomy-worker-general")).isEqualTo(general);
    }

    @Test
    void tlsSecretAndMetricsStayInternalWhileIngressAlwaysSelectsWeb() throws Exception {
        List<Map<String, Object>> docs = render(true,
                "--set", "analysis.artemis.brokerUrl=", "--set", "analysis.artemis.brokerUrlSecretKey=ARTEMIS_URL",
                "--set", "analysis.artemis.tlsSecret=taxonomy-broker-tls",
                "--set", "serviceMonitor.enabled=true", "--set", "ingress.enabled=true",
                "--set-json", "networkPolicy.ingressFrom=[{\"namespaceSelector\":{\"matchLabels\":{\"kubernetes.io/metadata.name\":\"ingress\"}}}]",
                "--set-json", "analysis.workerMetricsIngressFrom=[{\"namespaceSelector\":{\"matchLabels\":{\"kubernetes.io/metadata.name\":\"monitoring\"}}}]");
        assertThat(kind(docs, "Service")).hasSize(3).allSatisfy(service ->
                assertThat(map(service, "spec").get("type")).isEqualTo("ClusterIP"));
        for (Map<String, Object> deployment : kind(docs, "Deployment")) {
            assertThat(environment(deployment).get("TAXONOMY_ANALYSIS_ARTEMIS_BROKER_URL"))
                    .isEqualTo(Map.of("secretKeyRef", Map.of("name", "taxonomy-artemis", "key", "ARTEMIS_URL", "optional", false)));
            assertThat(list(container(deployment).get("volumeMounts"))).anySatisfy(mount -> {
                assertThat(mount).containsEntry("name", "artemis-tls").containsEntry("readOnly", true);
            });
        }
        Map<String, Object> monitorSelector = map(kind(docs, "ServiceMonitor").getFirst(), "spec", "selector", "matchLabels");
        assertThat(kind(docs, "Service")).allSatisfy(service ->
                assertThat(matches(monitorSelector, map(service, "metadata", "labels"))).isTrue());
        Map<String, Object> ingressRule = list(map(kind(docs, "Ingress").getFirst(), "spec").get("rules")).getFirst();
        for (Map<String, Object> path : list(map(ingressRule, "http").get("paths"))) {
            assertThat(map(path, "backend", "service")).containsEntry("name", "taxonomy-taxonomy");
        }
    }

    @Test
    void constrainedProfilesApplyResourcesSecurityAndEgressToEveryWorker() throws Exception {
        List<Map<String, Object>> docs = render(true,
                "--values", root.resolve("deploy/helm/taxonomy/values-small.yaml").toString(),
                "--set", "networkPolicy.allowSameNamespaceEgress=false");
        for (Map<String, Object> deployment : kind(docs, "Deployment")) {
            Map<String, Object> pod = map(deployment, "spec", "template", "spec");
            assertThat(pod).containsEntry("automountServiceAccountToken", false);
            assertThat(map(pod, "securityContext")).containsEntry("runAsNonRoot", true);
            assertThat(map(container(deployment), "securityContext")).containsEntry("readOnlyRootFilesystem", true)
                    .containsEntry("allowPrivilegeEscalation", false);
            assertThat(map(container(deployment), "resources", "limits"))
                    .containsEntry("cpu", "500m").containsEntry("memory", "1536Mi");
            assertThat(environment(deployment)).containsEntry("TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD", "false");
        }
        assertThat(kind(docs, "PersistentVolumeClaim")).isEmpty();
        assertThat(kind(docs, "NetworkPolicy")).hasSize(3).allSatisfy(policy ->
                assertThat(list(map(policy, "spec").get("egress"))).allSatisfy(rule ->
                        assertThat(rule).containsKeys("to", "ports")));
    }

    @Test
    void acceptanceRenderIsClusterFreeAndCannotRetainAnOldLiveSuccess() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("evidence.json"), "{\"result\":\"passed\"}");
        Files.writeString(Files.createDirectory(evidence.resolve("startup")).resolve("old-pod-heap.json"), "old source sample");
        Path called = temporary.resolve("cluster-command-called");
        Path stubs = Files.createDirectory(temporary.resolve("render-stubs"));
        for (String command : List.of("kubectl", "docker", "kind")) {
            executable(stubs.resolve(command), "#!/bin/sh\nprintf '%s\\n' called > '" + called + "'\nexit 97\n");
        }
        Result result = run(List.of("bash", root.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh").toString(),
                "--render-only"), Map.of("EVIDENCE_DIR", evidence.toString(),
                "SOURCE_SHA", "0123456789abcdef0123456789abcdef01234567",
                "PATH", stubs + ":" + System.getenv("PATH")));
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("no live cluster assertions executed");
        assertThat(called).doesNotExist();
        assertThat(evidence.resolve("evidence.json")).doesNotExist();
        assertThat(evidence.resolve("startup")).doesNotExist();
        List<Map<String, Object>> docs = yaml(Files.readString(evidence.resolve("rendered.yaml")));
        List<Map<String, Object>> deployments = kind(docs, "Deployment");
        assertThat(deployments).hasSize(3);
        assertThat(environment(named(deployments, "taxonomy-artemis")))
                .containsEntry("TAXONOMY_ANALYSIS_RUNTIME_ROLE", "coordinator");
        for (String rootCode : List.of("CP", "IP")) {
            Map<String, Object> worker = named(deployments, "taxonomy-artemis-worker-" + rootCode.toLowerCase(java.util.Locale.ROOT));
            assertThat(environment(worker)).containsEntry("TAXONOMY_ANALYSIS_RUNTIME_ROLE", "worker")
                    .containsEntry("TAXONOMY_ANALYSIS_WORKER_SHARDS", rootCode)
                    .containsEntry("TAXONOMY_ANALYSIS_ARTEMIS_REQUIRE_TLS", "true");
        }
        assertThat(kind(docs, "Service")).hasSize(1);
        assertThat(kind(docs, "Ingress")).isEmpty();
        assertThat(kind(docs, "Secret")).isEmpty();
        assertThat(kind(docs, "NetworkPolicy")).hasSize(3).allSatisfy(policy -> {
            List<Map<String, Object>> egress = list(map(policy, "spec").get("egress"));
            assertThat(egress).hasSize(3).allSatisfy(rule -> assertThat(rule).containsKeys("to", "ports"));
            assertThat(egress.stream().flatMap(rule -> list(rule.get("ports")).stream()).map(port -> port.get("port")))
                    .containsExactlyInAnyOrder(53, 53, 5432, 61617);
            for (var peer : Map.of(5432, "taxonomy-smoke-postgres", 61617, "taxonomy-smoke-broker").entrySet()) {
                var rule = egress.stream().filter(e -> list(e.get("ports")).stream()
                        .anyMatch(p -> peer.getKey().equals(p.get("port")))).findFirst().orElseThrow();
                assertThat(map(list(rule.get("to")).getFirst(), "podSelector", "matchLabels"))
                        .containsEntry("app.kubernetes.io/name", peer.getValue());
            }
        });
        List<Map<String, Object>> fixtures = yaml(Files.readString(evidence.resolve("fixtures.yaml")));
        assertThat(kind(fixtures, "Secret")).isEmpty();
        assertThat(kind(fixtures, "Ingress")).isEmpty();
        assertThat(kind(fixtures, "PersistentVolumeClaim")).isEmpty();
        assertThat(kind(fixtures, "Service")).hasSize(2).allSatisfy(service ->
                assertThat(map(service, "spec")).containsEntry("type", "ClusterIP"));
        assertThat(kind(fixtures, "Deployment")).hasSize(2).allSatisfy(deployment ->
                assertThat((String) container(deployment).get("image")).matches(".+@sha256:[0-9a-f]{64}"));
        List<Map<String, Object>> finalPods = new ArrayList<>(deployments);
        finalPods.add(named(deployments, "taxonomy-artemis-worker-cp")); // Independently scaled to two.
        finalPods.addAll(kind(fixtures, "Deployment"));
        Map<String, Object> quota = map(kind(fixtures, "ResourceQuota").getFirst(), "spec", "hard");
        for (String boundary : List.of("requests", "limits")) {
            for (String resource : List.of("cpu", "memory")) {
                long declared = finalPods.stream().mapToLong(p -> quantity(map(container(p), "resources", boundary).get(resource), resource)).sum();
                assertThat(declared).as("final fixture %s.%s fits quota", boundary, resource)
                        .isLessThanOrEqualTo(quantity(quota.get(boundary + "." + resource), resource));
            }
        }
        assertThat(finalPods.size()).isLessThanOrEqualTo(Integer.parseInt(quota.get("pods").toString()));
    }

    @Test
    void invalidSourcePreflightCannotLeaveAnOldPassingEvidenceMarker() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("invalid-source-evidence"));
        Files.writeString(evidence.resolve("evidence.json"), "{\"result\":\"passed\"}");
        Result result = run(List.of("bash", root.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh").toString()),
                Map.of("EVIDENCE_DIR", evidence.toString(), "SOURCE_SHA", "not-a-commit"));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("SOURCE_SHA must be a full Git commit");
        assertThat(evidence.resolve("evidence.json")).doesNotExist();
    }

    @Test
    void missingHelmPreflightAlsoClearsOldSuccessBeforeFailing() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("missing-helm-evidence"));
        Files.writeString(evidence.resolve("evidence.json"), "{\"result\":\"passed\"}");
        Path utilities = Files.createDirectory(temporary.resolve("no-helm-path"));
        for (String command : List.of("dirname", "mkdir", "rm")) {
            Files.createSymbolicLink(utilities.resolve(command), Path.of("/usr/bin", command));
        }
        Result result = run(List.of("/bin/bash", root.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh").toString()),
                Map.of("EVIDENCE_DIR", evidence.toString(), "SOURCE_SHA", "0123456789abcdef0123456789abcdef01234567",
                        "PATH", utilities.toString()));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("helm is required for Artemis constrained smoke");
        assertThat(evidence.resolve("evidence.json")).doesNotExist();
    }

    @Test
    void livePreflightRefusesExistingFixtureWithoutMutatingItOrTheCurrentContext() throws Exception {
        Path evidence = Files.createDirectory(temporary.resolve("failed-evidence"));
        Files.writeString(evidence.resolve("evidence.json"), "{\"result\":\"passed\"}");
        Path calls = temporary.resolve("kubectl-calls");
        Path stubs = Files.createDirectory(temporary.resolve("live-stubs"));
        executable(stubs.resolve("kubectl"), "#!/bin/sh\nprintf '%s\\n' \"$*\" >> '" + calls
                + "'\nprintf '%s\\n' namespace/taxonomy-artemis-smoke\n");
        for (String command : List.of("docker", "kind", "keytool")) executable(stubs.resolve(command), "#!/bin/sh\nexit 97\n");
        Result result = run(List.of("bash", root.resolve("deploy/helm/taxonomy/artemis-constrained-smoke.sh").toString()),
                Map.of("EVIDENCE_DIR", evidence.toString(), "SOURCE_SHA", "0123456789abcdef0123456789abcdef01234567",
                        "KIND_CLUSTER_NAME", "review-fixture", "KEEP_RESOURCES", "false",
                        "PATH", stubs + ":" + System.getenv("PATH")));
        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("already exists in kind-review-fixture");
        assertThat(Files.readAllLines(calls)).containsExactly(
                "--context kind-review-fixture get namespace taxonomy-artemis-smoke --ignore-not-found -o name");
        assertThat(evidence.resolve("evidence.json")).doesNotExist();
    }

    @Test
    void unsafeOrAmbiguousClusterConfigurationsFailClosed() throws Exception {
        List<List<String>> invalid = List.of(
                List.of("--set", "analysis.transportMode=local"),
                List.of("--set", "analysis.runtimeRole=all"),
                List.of("--set", "analysis.artemis.existingSecret="),
                List.of("--set", "analysis.artemis.brokerUrlSecretKey=BROKER_URL"),
                List.of("--set", "analysis.artemis.brokerUrl=tcp://broker:61616"),
                List.of("--set", "analysis.artemis.brokerUrl=tcp://broker:61617?sslEnabled=true&verifyHost=false"),
                List.of("--set", "analysis.artemis.brokerUrl=tcp://broker:61617?sslEnabled=true&trustStorePassword=unsafe"),
                List.of("--set-json", "analysis.artemis.egress=[]"),
                List.of("--set-json", "analysis.artemis.egress=[{\"to\":[{}],\"ports\":[{\"port\":61617}]}]"),
                List.of("--set", "analysis.workerSets[0].shards[0]=INVALID"),
                List.of("--set", "analysis.workerSets[0].consumersPerShard=0"),
                List.of("--set", "analysis.workerSets[1].name=cp"),
                List.of("--set", "config.TAXONOMY_ANALYSIS_RUNTIME_ROLE=all"),
                List.of("--set-json", "extraEnv=[{\"name\":\"TAXONOMY_ANALYSIS_WORKER_SHARDS\",\"value\":\"IP\"}]"),
                List.of("--set", "serviceMonitor.enabled=true"));
        for (List<String> arguments : invalid) {
            Result result = template(true, arguments);
            assertThat(result.exitCode()).as("must reject %s: %s", arguments, result.output()).isNotZero();
        }
    }

    private List<Map<String, Object>> render(boolean artemis, String... extra) throws Exception {
        Result result = template(artemis, List.of(extra));
        assertThat(result.exitCode()).as(result.output()).isZero();
        return yaml(result.output());
    }

    private List<Map<String, Object>> yaml(String content) {
        List<Map<String, Object>> documents = new ArrayList<>();
        new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(content).forEach(document -> {
            if (document != null) documents.add(cast(document));
        });
        return documents;
    }

    private Result template(boolean artemis, List<String> extra) throws Exception {
        List<String> command = new ArrayList<>(List.of("helm", "template", "taxonomy", root.resolve("deploy/helm/taxonomy").toString(),
                "--set", "image.tag=sha-0123456789abcdef0123456789abcdef01234567", "--set", "existingSecret=taxonomy-secrets"));
        if (artemis) command.addAll(List.of("--values", root.resolve("deploy/helm/taxonomy/values-artemis.yaml").toString()));
        command.addAll(extra);
        return run(command);
    }

    private Result run(List<String> command) throws Exception {
        return run(command, Map.of());
    }

    private Result run(List<String> command, Map<String, String> environment) throws Exception {
        Path output = Files.createTempFile(temporary, "helm-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("Helm command completed").isTrue();
            return new Result(process.exitValue(), Files.readString(output));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void executable(Path path, String script) throws IOException {
        Files.writeString(path, script);
        assertThat(path.toFile().setExecutable(true)).isTrue();
    }

    private static long quantity(Object value, String resource) {
        String text = value.toString();
        if (resource.equals("cpu")) return text.endsWith("m") ? Long.parseLong(text.substring(0, text.length() - 1)) : Long.parseLong(text) * 1000;
        if (text.endsWith("Gi")) return Long.parseLong(text.substring(0, text.length() - 2)) * 1024;
        if (text.endsWith("Mi")) return Long.parseLong(text.substring(0, text.length() - 2));
        throw new IllegalArgumentException("Unsupported fixture memory unit: " + text);
    }

    private static Map<String, Object> environment(Map<String, Object> deployment) {
        Map<String, Object> result = new java.util.HashMap<>();
        for (Map<String, Object> entry : list(container(deployment).get("env"))) {
            assertThat(result).doesNotContainKey((String) entry.get("name"));
            result.put((String) entry.get("name"), entry.containsKey("value") ? entry.get("value") : entry.get("valueFrom"));
        }
        return result;
    }

    private static Map<String, Object> container(Map<String, Object> deployment) {
        return list(map(deployment, "spec", "template", "spec").get("containers")).getFirst();
    }
    private static Map<String, Object> labels(Map<String, Object> deployment) { return map(deployment, "spec", "template", "metadata", "labels"); }
    private static List<Map<String, Object>> kind(List<Map<String, Object>> docs, String kind) { return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList(); }
    private static Map<String, Object> named(List<Map<String, Object>> docs, String name) { return docs.stream().filter(d -> name.equals(map(d, "metadata").get("name"))).findFirst().orElseThrow(); }
    private static boolean matches(Map<String, Object> selector, Map<String, Object> labels) { return labels.entrySet().containsAll(selector.entrySet()); }
    private static Map<String, Object> map(Map<String, Object> value, String... keys) {
        for (String key : keys) value = cast(value.get(key));
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> list(Object value) { return (List<Map<String, Object>>) value; }
    private record Result(int exitCode, String output) { }
}
