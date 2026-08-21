package com.taxonomy.build;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
                .contains("must not be an unrestricted empty rule in restricted mode");
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
                .contains("/actuator/health/readiness")
                .contains("localImageId")
                .contains("sourceSha")
                .contains("fixtureSecret: true")
                .contains("configuredAdminPassword: true")
                .contains("restrictedEgress: true")
                .doesNotContain("deployment/${RELEASE}")
                .doesNotContain("service/${RELEASE}")
                .doesNotContain("resourcequota,limitrange,secret,pod")
                .doesNotContain("taxonomy-smoke-admin")
                .doesNotContain("python")
                .doesNotContain("pip");
        assertThat(workflow)
                .contains("KIND_VERSION: v0.31.0")
                .contains("KUBECTL_VERSION: v1.35.0")
                .contains("sha256sum -c -")
                .contains("bash deploy/helm/taxonomy/constrained-smoke.sh")
                .contains("kubernetes-constrained-smoke")
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
