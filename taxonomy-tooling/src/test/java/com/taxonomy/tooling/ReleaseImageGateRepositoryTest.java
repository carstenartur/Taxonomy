package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReleaseImageGateRepositoryTest {

    @Test
    void releasePublicationIsBoundToTheScannedImmutableImageDigest()
            throws Exception {
        String workflow = releaseWorkflow();

        assertThat(failures(workflow))
                .as("immutable release-image publication contract")
                .isEmpty();
    }

    @Test
    void tagOnlyOrNonBlockingImageScansFailClosed() throws Exception {
        String workflow = releaseWorkflow()
                .replace(
                        "image-ref: ghcr.io/${{ github.repository_owner }}/taxonomy@${{ steps.release_image.outputs.digest }}",
                        "image-ref: ghcr.io/${{ github.repository_owner }}/taxonomy:v${{ steps.release_parameters.outputs.release_version }}")
                .replace("exit-code: '1'", "exit-code: '0'");

        assertThat(failures(workflow))
                .anyMatch(message -> message.contains(
                        "scan must consume the immutable build-push digest"))
                .anyMatch(message -> message.contains(
                        "scan must fail publication on blocking findings"));
    }

    @Test
    void evidenceMustBeUploadedAndVerifiedBeforePublication() throws Exception {
        String workflow = releaseWorkflow()
                .replace("gh release upload \"$tag\" \"$artifact_dir\"/* --clobber", "echo skipped-upload")
                .replace(
                        "if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then",
                        "if false; then");

        assertThat(failures(workflow))
                .anyMatch(message -> message.contains(
                        "digest evidence must be attached to the draft release"))
                .anyMatch(message -> message.contains(
                        "evidence digest must be compared before publication"));
    }

    static List<String> failures(String workflow) {
        List<String> failures = new ArrayList<>();
        require(workflow, "id: release_image",
                "release image build must expose a digest output", failures);
        require(workflow, "provenance: mode=max",
                "release image build must enable maximum provenance", failures);
        require(workflow, "sbom: true",
                "release image build must enable SBOM attestation", failures);
        require(workflow, "- name: Scan immutable release image digest",
                "immutable digest scan step is missing", failures);
        require(workflow,
                "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25",
                "release image scanner must stay immutable and reviewed", failures);
        require(workflow,
                "image-ref: ghcr.io/${{ github.repository_owner }}/taxonomy@${{ steps.release_image.outputs.digest }}",
                "scan must consume the immutable build-push digest", failures);
        require(workflow, "severity: HIGH,CRITICAL",
                "release image scan must cover HIGH and CRITICAL findings", failures);
        require(workflow, "exit-code: '1'",
                "release image scan must fail publication on blocking findings", failures);
        require(workflow,
                "- name: Bind release evidence and Helm deployment to immutable image digest",
                "digest evidence binding step is missing", failures);
        require(workflow, "IMAGE_DIGEST: ${{ steps.release_image.outputs.digest }}",
                "evidence binding must consume the exact build digest", failures);
        require(workflow, "^sha256:[0-9a-f]{64}$",
                "release digest must be validated as a full SHA-256", failures);
        require(workflow, "immutable_image=\"${repository}@${IMAGE_DIGEST}\"",
                "release evidence must use an immutable image reference", failures);
        require(workflow, "image.digest=${IMAGE_DIGEST}",
                "Helm evidence must render the immutable digest", failures);
        require(workflow, "taxonomy-${RELEASE_VERSION}-kubernetes-digest.yaml",
                "digest-bound Kubernetes evidence is missing", failures);
        require(workflow, "taxonomy-${RELEASE_VERSION}-image-evidence.json",
                "release image evidence asset is missing", failures);
        require(workflow, "taxonomy-${RELEASE_VERSION}-image-trivy.sarif",
                "release image SARIF asset is missing", failures);
        require(workflow, "taxonomy-${RELEASE_VERSION}-release.sha256",
                "release evidence checksum asset is missing", failures);
        require(workflow, "retention-days: 90",
                "release image security evidence retention is missing", failures);
        require(workflow, "docker buildx imagetools inspect \"$image\"",
                "publication must inspect the immutable image digest", failures);
        require(workflow,
                "if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then",
                "evidence digest must be compared before publication", failures);

        int finalGates = indexOf(
                workflow,
                "- name: Verify exact final main release gate matrix",
                "exact final-main gate step",
                failures);
        int build = indexOf(
                workflow,
                "- name: Build and publish immutable release image",
                "immutable image build step",
                failures);
        int scan = indexOf(
                workflow,
                "- name: Scan immutable release image digest",
                "immutable image scan step",
                failures);
        int bind = indexOf(
                workflow,
                "- name: Bind release evidence and Helm deployment to immutable image digest",
                "digest evidence binding step",
                failures);
        int archive = indexOf(
                workflow,
                "- name: Archive immutable release image evidence",
                "image evidence archive step",
                failures);
        int publish = indexOf(
                workflow,
                "- name: Publish complete release and trigger deployment",
                "final release publication step",
                failures);
        if (allPresent(finalGates, build, scan, bind, archive, publish)
                && !(finalGates < build
                && build < scan
                && scan < bind
                && bind < archive
                && archive < publish)) {
            failures.add(
                    "exact final-main gates must precede image build, scan, evidence binding, archive and publication");
        }

        if (scan >= 0 && bind > scan) {
            String scanBlock = workflow.substring(scan, bind);
            require(scanBlock, "exit-code: '1'",
                    "release image scan must fail publication on blocking findings", failures);
            require(scanBlock, "steps.release_image.outputs.digest",
                    "scan must consume the immutable build-push digest", failures);
        }
        if (bind >= 0 && archive > bind) {
            String bindBlock = workflow.substring(bind, archive);
            require(bindBlock, "gh release upload",
                    "digest evidence must be attached to the draft release", failures);
            require(bindBlock, "image.digest=${IMAGE_DIGEST}",
                    "Helm release evidence must include a digest-bound manifest", failures);
        }
        if (publish >= 0) {
            String publishBlock = workflow.substring(publish);
            int evidenceCheck = publishBlock.indexOf("evidence_digest");
            int digestComparison = publishBlock.indexOf(
                    "if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then");
            int publishRelease = publishBlock.indexOf("gh release edit");
            if (evidenceCheck < 0
                    || digestComparison < evidenceCheck
                    || publishRelease < 0
                    || digestComparison > publishRelease) {
                failures.add(
                        "evidence digest must be compared before publication");
            }
            require(publishBlock, "image=\"${repository}@${IMAGE_DIGEST}\"",
                    "final publication must inspect an immutable digest, not only a tag", failures);
        }
        return List.copyOf(failures);
    }

    private static void require(
            String source,
            String needle,
            String failure,
            List<String> failures) {
        if (!source.contains(needle)) {
            failures.add(failure);
        }
    }

    private static int indexOf(
            String source,
            String needle,
            String description,
            List<String> failures) {
        int index = source.indexOf(needle);
        if (index < 0) {
            failures.add(description + " is missing");
        }
        return index;
    }

    private static boolean allPresent(int... indices) {
        for (int index : indices) {
            if (index < 0) {
                return false;
            }
        }
        return true;
    }

    private static String releaseWorkflow() throws Exception {
        return Files.readString(
                findRepositoryRoot().resolve(".github/workflows/deploy-release.yml"),
                StandardCharsets.UTF_8);
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("pom.xml"))
                    && Files.isRegularFile(current.resolve(
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
