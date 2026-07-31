package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the production image and chart to portable non-root container semantics. */
class ContainerPlatformContractTest {

    @Test
    void imageProvidesASecureDefaultAndSupportsOpenShiftArbitraryUids()
            throws IOException {
        String dockerfile = readRepositoryFile("Dockerfile");

        assertThat(dockerfile)
                .contains("ARG TAXONOMY_UID=10001")
                .contains("ARG TAXONOMY_GID=10001")
                .contains("USER 10001:10001")
                .contains("ENV HOME=/tmp")
                .contains("-Djava.io.tmpdir=/tmp")
                .contains("RUN chgrp -R 0 /app /opt/opentelemetry")
                .contains("&& chmod -R g=u /app/data")
                .doesNotContain("chmod -R g=u /app /opt/opentelemetry")
                .contains("STOPSIGNAL SIGTERM")
                .doesNotContain("USER root");
    }

    @Test
    void openShiftValuesDelegateTheNumericIdentityToTheCluster()
            throws IOException {
        String values = readRepositoryFile(
                "deploy/helm/taxonomy/values-openshift.yaml");

        assertThat(values)
                .contains("runAsNonRoot: true")
                .contains("runAsUser: null")
                .contains("runAsGroup: null")
                .contains("fsGroup: null")
                .contains("fsGroupChangePolicy: null")
                .contains("readOnlyRootFilesystem: true")
                .contains("allowPrivilegeEscalation: false")
                .contains("drop: [\"ALL\"]");
    }

    @Test
    void defaultValuesRetainThePortableFixedIdentity()
            throws IOException {
        String values = readRepositoryFile("deploy/helm/taxonomy/values.yaml");

        assertThat(values)
                .contains("runAsNonRoot: true")
                .contains("runAsUser: 10001")
                .contains("runAsGroup: 10001")
                .contains("fsGroup: 10001")
                .contains("readOnlyRootFilesystem: true");
    }

    private static String readRepositoryFile(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(current.resolve("taxonomy-app/pom.xml"))) {
            return current;
        }
        if ("taxonomy-app".equals(current.getFileName().toString())
                && Files.isRegularFile(current.resolve("pom.xml"))) {
            return current.getParent();
        }
        throw new IllegalStateException(
                "Cannot locate Taxonomy repository root from " + current);
    }
}
