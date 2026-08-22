package com.taxonomy.build;

import org.junit.jupiter.api.Test;

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
                "deploy/helm/taxonomy/templates/resources.yaml"));
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

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
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
