package com.taxonomy.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Fast repository contract for the constrained Kubernetes release floor. */
class HelmConstrainedSmokeContractTest {

    @TempDir Path temporary;

    @Test
    void supportedProfilesUseRestrictedExplicitEgress() throws Exception {
        Path root = findRepositoryRoot();
        String defaults = read(root.resolve("deploy/helm/taxonomy/values.yaml"));
        String small = read(root.resolve("deploy/helm/taxonomy/values-small.yaml"));
        String rancher = read(root.resolve(
                "deploy/helm/taxonomy/values-rancher-rke2.yaml"));
        String constrained = read(root.resolve(
                "deploy/helm/taxonomy/values-constrained-smoke.yaml"));
        String resources = read(root.resolve(
                "deploy/helm/taxonomy/templates/_network.tpl"));
        String validation = read(root.resolve(
                "deploy/helm/taxonomy/templates/validation.yaml"));

        assertThat(defaults)
                .contains("egressMode: restricted")
                .contains("allowSameNamespaceEgress: true")
                .contains("kubernetes.io/metadata.name: kube-system")
                .contains("values: [kube-dns, coredns]")
                .contains("TAXONOMY_ADMIN_PASSWORD:")
                .contains("key: ADMIN_PASSWORD")
                .doesNotContain("egress:\n    - {}")
                .doesNotContain("  ADMIN_PASSWORD:\n    key: ADMIN_PASSWORD");
        assertThat(small).contains("egressMode: restricted");
        assertThat(rancher)
                .contains("egressMode: restricted")
                .doesNotContain("egress:\n    - {}");
        assertThat(constrained)
                .contains("egressMode: restricted")
                .contains("SPRING_PROFILES_ACTIVE: hsqldb,kubernetes")
                .contains("existingSecret: taxonomy-smoke-credentials")
                .contains("pullPolicy: Never");
        assertThat(resources)
                .contains("if eq $egressMode \"open\"")
                .contains("networkPolicy.dns.namespaceSelector")
                .contains("networkPolicy.dns.podSelector")
                .contains("networkPolicy.dns.ports");
        assertThat(validation)
                .contains("networkPolicy.egressMode must be restricted or open")
                .contains("kindIs \"map\" $rule")
                .contains("must constrain destinations and/or ports in restricted mode");
    }

    @Test
    void restrictedModeRejectsEverySemanticallyUnrestrictedRuleWhenHelmIsAvailable()
            throws Exception {
        Path root = findRepositoryRoot();
        Path chart = root.resolve("deploy/helm/taxonomy");
        List<String> unrestrictedRules = List.of(
                "[{}]",
                "[{\"to\":[],\"ports\":[]}]");

        for (String egressRules : unrestrictedRules) {
            Process process;
            try {
                process = new ProcessBuilder(
                        "helm", "template", "taxonomy", chart.toString(),
                        "--set", "image.tag=sha-0123456789abcdef0123456789abcdef01234567",
                        "--set", "existingSecret=taxonomy-secrets",
                        "--set-json", "networkPolicy.egress=" + egressRules)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .start();
            } catch (IOException exception) {
                assumeTrue(false,
                        "Helm 3 is unavailable; executable chart validation is skipped");
                return;
            }

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            assertThat(finished)
                    .as("Helm validation completed for %s", egressRules)
                    .isTrue();
            String output = new String(
                    process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue())
                    .as("restricted egress rule %s must be rejected", egressRules)
                    .isNotZero();
            assertThat(output)
                    .contains("must constrain destinations and/or ports in restricted mode");
        }
    }

    @Test
    void liveSmokeIsQuotaBoundDigestAwareAndKeepsCredentialsEphemeral()
            throws Exception {
        Path root = findRepositoryRoot();
        String prerequisites = read(root.resolve(
                "deploy/helm/taxonomy/constrained-smoke-prerequisites.yaml"));
        String script = read(root.resolve(
                "deploy/helm/taxonomy/constrained-smoke.sh"));
        String workflow = read(root.resolve(
                ".github/workflows/kubernetes-constrained-smoke.yml"));
        String verificationSuites = read(root.resolve(
                ".mvn/verification-suites.json"));

        assertThat(prerequisites)
                .contains("kind: ResourceQuota")
                .contains("kind: LimitRange")
                .contains("requests.cpu")
                .contains("limits.memory")
                .doesNotContain("kind: Secret")
                .doesNotContain("ADMIN_PASSWORD");
        assertThat(script)
                .contains("NAMESPACE=taxonomy-smoke")
                .doesNotContain("TAXONOMY_SMOKE_NAMESPACE")
                .contains("docker build")
                .contains("kind load docker-image")
                .contains("kubectl create secret generic")
                .contains("od -An -N24 -tx1 /dev/urandom")
                .contains("unset SMOKE_ADMIN_PASSWORD")
                .contains("helm upgrade --install")
                .contains("RESOURCE_SELECTOR=")
                .contains("--selector \"${RESOURCE_SELECTOR}\"")
                .contains("Expected exactly one Taxonomy Deployment")
                .contains("Expected exactly one Taxonomy Service")
                .contains("name: TAXONOMY_ADMIN_PASSWORD")
                .contains("Created configured administrator account")
                .contains("curl --fail --location --silent --show-error")
                .contains("--header 'Accept: text/html'")
                .contains("\"service/${SERVICE}\" :80", "Fixture port-forward exited before readiness")
                .doesNotContain("127.0.0.1:18080")
                .contains("/actuator/health/readiness")
                .contains("DEPLOYMENT_START_EPOCH")
                .contains("READINESS_SECONDS=$((READY_EPOCH - DEPLOYMENT_START_EPOCH))")
                .contains("totalSmokeSeconds")
                .contains("localImageId")
                .contains("sourceSha")
                .contains("fixtureSecret: true")
                .contains("configuredAdminPassword: true")
                .contains("restrictedEgress: true")
                .doesNotContain("deployment/${RELEASE}")
                .doesNotContain("service/${RELEASE}")
                .doesNotContain("resourcequota,limitrange,secret,pod")
                .doesNotContain("taxonomy-smoke-admin")
                .doesNotContainPattern(
                        "(?<![A-Za-z0-9_-])python(?:3)?(?![A-Za-z0-9_-])")
                .doesNotContainPattern(
                        "(?<![A-Za-z0-9_-])pip(?:3)?(?![A-Za-z0-9_-])");
        assertThat(workflow)
                .contains("KIND_VERSION: v0.31.0")
                .contains("KUBECTL_VERSION: v1.35.0")
                .contains("sha256sum -c -")
                .contains("bash deploy/helm/taxonomy/constrained-smoke.sh")
                .contains("Collect secret-free cluster diagnostics")
                .contains("kubectl get pods,services,networkpolicies,resourcequotas,limitranges")
                .contains("kubernetes-constrained-smoke")
                .doesNotContain("cluster-info dump")
                .doesNotContain("cluster-dump")
                .doesNotContain("setup-python");
        assertThat(verificationSuites)
                .contains("\"kubernetes-constrained-smoke.yml\"")
                .contains("constrained-cluster release-floor verification");
    }

    @Test
    void smallProfileSamplesRequireMonotonicCountersAndAnUninterruptedPod() throws Exception {
        Path filter = findRepositoryRoot().resolve("deploy/helm/taxonomy/small-search-budget.jq");
        String initial = "[1000,100000,4,40,0,200,100,0]";
        String idle = "[2000,200000,5,60,0,210,102,0]";
        String loaded = "[3000,500000,7,90,0,230,112,0]";
        for (String last : List.of(loaded, "[2000,500000,7,90,0,230,112,0]",
                "[3000,190000,7,90,0,230,112,0]", "[3000,500000,7,90,1,230,112,0]",
                "[3000,500000,7,90,0,230,112,1]", "[3000,500000,7,90,0,null,112,0]")) {
            var process = new ProcessBuilder("jq", "-ce", "-f", filter.toString())
                    .redirectErrorStream(true).start();
            // The producer serializes measurements as named fields, never positional arrays.
            String rows = "[%s,%s,%s]".formatted(initial, idle, last);
            var named = new ProcessBuilder("jq", "-c",
                    "map({ms:.[0],cpuUsec:.[1],throttledPeriods:.[2],throttledUsec:.[3],"
                            + "oomKills:.[4],memoryBytes:.[5],ephemeralBytes:.[6],restarts:.[7]})")
                    .start();
            try (var input = named.getOutputStream()) { input.write(rows.getBytes(StandardCharsets.UTF_8)); }
            try (var input = process.getOutputStream()) { named.getInputStream().transferTo(input); }
            assertThat(named.waitFor()).isZero();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (last.equals(loaded)) {
                assertThat(process.waitFor()).as(output).isZero();
                assertThat(output).contains("\"steadyCpuMillicores\":100", "\"peakIntervalCpuMillicores\":300",
                        "\"peakSampledMemoryBytes\":230", "\"peakSampledEphemeralBytes\":112");
            } else {
                assertThat(process.waitFor()).as(last).isNotZero();
            }
        }
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void invalidSourceClearsOldSuccessWithoutMutatingAnyCluster() throws Exception {
        Path calls = runRejectedSmoke("invalid", false, "SOURCE_SHA must be a full");
        assertThat(calls).doesNotExist();
    }

    @Test
    void existingNamespaceIsNeverReusedOrDeleted() throws Exception {
        Path calls = runRejectedSmoke("0123456789abcdef0123456789abcdef01234567", true,
                "namespace taxonomy-smoke already exists in kind-review-fixture");
        List<String> commands = Files.readAllLines(calls);
        assertThat(commands).hasSize(2);
        assertThat(commands.getFirst()).isEqualTo("kind get kubeconfig --name review-fixture");
        assertThat(commands.getLast()).startsWith("kubectl --kubeconfig ")
                .endsWith(" --context kind-review-fixture get namespace taxonomy-smoke --ignore-not-found --request-timeout=10s -o name");
    }

    @Test
    void failureAfterCreatingNamespaceCleansUpOnlyTheRecordedUid() throws Exception {
        Path calls = runRejectedSmoke("0123456789abcdef0123456789abcdef01234567", false,
                "fixture Git failure");
        assertThat(Files.readAllLines(calls)).hasSize(5).last().asString()
                .endsWith(" --context kind-review-fixture delete --request-timeout=10s --raw /api/v1/namespaces/taxonomy-smoke -f -");
        assertThat(Files.readString(temporary.resolve("delete-options.json")))
                .contains("\"preconditions\":{\"uid\":\"fixture-uid\"}");
    }

    private Path runRejectedSmoke(String source, boolean existingNamespace, String diagnostic) throws Exception {
        Path stubs = Files.createDirectory(temporary.resolve("bin"));
        Path calls = temporary.resolve("calls.txt");
        for (String command : List.of("docker", "kind", "kubectl", "helm", "curl", "jq", "od", "git")) {
            String result = switch (command) {
                case "kind" -> "printf '%s\\n' review-fixture";
                case "kubectl" -> """
                        case "$*" in
                          *' get namespace '*) %s ;;
                          *' create namespace '*) printf 'fixture-uid' ;;
                          *' --raw /api/v1/namespaces/taxonomy-smoke '*) cat > "$SMOKE_TEST_DELETE" ;;
                          *) exit 97 ;;
                        esac
                        """.formatted(existingNamespace ? "printf '%s\\n' namespace/taxonomy-smoke" : ":");
                case "git" -> "echo 'fixture Git failure' >&2; exit 97";
                default -> "exit 97";
            };
            Path stub = stubs.resolve(command);
            Files.writeString(stub, "#!/bin/sh\nprintf '%s %s\\n' '" + command
                    + "' \"$*\" >> \"$SMOKE_TEST_CALLS\"\n" + result + "\n");
            assertThat(stub.toFile().setExecutable(true)).isTrue();
        }
        Path evidence = Files.createDirectory(temporary.resolve("evidence"));
        Files.writeString(evidence.resolve("evidence.json"), "{\"result\":\"passed\"}");
        var builder = new ProcessBuilder("bash", findRepositoryRoot()
                .resolve("deploy/helm/taxonomy/constrained-smoke.sh").toString()).redirectErrorStream(true);
        var environment = builder.environment();
        environment.put("PATH", stubs + ":" + environment.get("PATH"));
        environment.put("SMOKE_TEST_CALLS", calls.toString());
        environment.put("SMOKE_TEST_DELETE", temporary.resolve("delete-options.json").toString());
        environment.put("EVIDENCE_DIR", evidence.toString());
        environment.put("SOURCE_SHA", source);
        environment.put("IMAGE_TAG", "sha-0123456789abcdef0123456789abcdef01234567");
        environment.put("KIND_CLUSTER_NAME", "review-fixture");
        environment.put("KEEP_RESOURCES", "false");
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Constrained smoke preflight did not finish");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isNotZero();
        assertThat(output).contains(diagnostic);
        assertThat(evidence.resolve("evidence.json")).doesNotExist();
        return calls;
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "deploy/helm/taxonomy/values.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
