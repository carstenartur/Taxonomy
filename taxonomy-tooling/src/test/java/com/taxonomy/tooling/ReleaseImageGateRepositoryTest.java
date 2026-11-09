package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.taxonomy.tooling.ReleaseImageGate.stepBlock;
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

    @ParameterizedTest
    @ValueSource(strings = {"\"attestations\"", "\"provenance\": True", "\"sbom\": True",
            "\"result\": \"passed\"", "--set-string \"image.digest=${IMAGE_DIGEST}\""})
    void legacyImageEvidenceRequirementsFailClosedInMaven(String requirement) throws Exception {
        String workflow = releaseWorkflow();
        assertThat(workflow).contains(requirement);
        String replacement = requirement.startsWith("--set-string") ? "image.digest=${IMAGE_DIGEST}" : "";

        assertThat(failures(workflow.replace(requirement, replacement)))
                .anyMatch(message -> message.contains(requirement));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "invalid-utf8"})
    void imageGateRejectsUnreadableWorkflow(String input, @TempDir Path fixture) throws Exception {
        Path workflow = fixture.resolve("deploy-release.yml");
        if (input.equals("invalid-utf8")) {
            Files.write(workflow, new byte[]{(byte) 0xC3, 0x28});
        }
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();

        int status = ReleaseImageGate.run(workflow,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(status).isEqualTo(1);
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(error.toString(StandardCharsets.UTF_8))
                .contains("Immutable release image gate failed:", "Could not read deploy-release.yml");
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
                        "image scan must consume the build-push digest output"))
                .anyMatch(message -> message.contains(
                        "immutable digest scan must fail the release on blocking findings"));
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
                        "if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then"));
    }

    @Test
    void digestEvidenceIsReadBeforeTheComparison() throws Exception {
        String workflow = releaseWorkflow();
        String read = "\n          evidence_digest=";
        assertThat(workflow).contains(read);

        assertThat(failures(workflow.replace(read, "\n          other_digest=")))
                .contains("evidence digest must be read before its comparison");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "missing", "docker-driver", "unselected", "late", "disabled", "missing-mirror"
    })
    void incompatibleOrUnusedReleaseBuildersFailClosed(String mutation) throws Exception {
        String workflow = releaseWorkflow();
        int builder = workflow.indexOf("- name: Set up release image builder");
        assertThat(builder).as("release builder fixture").isGreaterThanOrEqualTo(0);
        String builderBlock = stepBlock(workflow, builder);

        String invalid = switch (mutation) {
            case "missing" -> workflow.replace(builderBlock, "");
            case "docker-driver" -> workflow.replace(
                    builderBlock, builderBlock.replace("driver: docker-container", "driver: docker"));
            case "unselected" -> workflow.replace(
                    "builder: ${{ steps.release_builder.outputs.name }}", "builder: default");
            case "late" -> workflow.replace(builderBlock, "") + "\n      " + builderBlock;
            case "disabled" -> workflow.replace(
                    builderBlock,
                    builderBlock.replace(
                            "if: steps.release_parameters.outputs.dry_run != 'true'", "if: false"));
            case "missing-mirror" -> workflow.replace(
                    builderBlock, builderBlock.replace("mirrors = [\"mirror.gcr.io\"]", "mirrors = []"));
            default -> throw new IllegalArgumentException(mutation);
        };

        assertThat(failures(invalid))
                .as("release builder regression: %s", mutation)
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-binding", "missing-fetch", "late"})
    void finalMainPublicationGuardCannotBeRemovedOrDelayed(String mutation) throws Exception {
        String workflow = releaseWorkflow();
        int publish = workflow.indexOf("- name: Publish complete release and trigger deployment");
        assertThat(publish).as("final publication fixture").isGreaterThanOrEqualTo(0);
        String publishBlock = stepBlock(workflow, publish);
        String fetch = "          git fetch origin \"refs/heads/main:refs/remotes/origin/main\" --force\n";
        int guardStart = publishBlock.indexOf(fetch);
        int draftBranch = publishBlock.indexOf("          if [[ \"$is_draft\" == \"true\" ]]; then");
        assertThat(guardStart).as("final main guard fixture").isGreaterThanOrEqualTo(0);
        assertThat(draftBranch).isGreaterThan(guardStart);
        String mainGuard = publishBlock.substring(guardStart, draftBranch);
        String publishedStateCheck = "          test \"$(gh release view";
        assertThat(publishBlock).contains(publishedStateCheck);

        String invalidPublish = switch (mutation) {
            case "missing-binding" -> publishBlock.replace(
                    "          EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}\n", "");
            case "missing-fetch" -> publishBlock.replace(fetch, "");
            case "late" -> publishBlock.replace(mainGuard, "")
                    .replace(publishedStateCheck, mainGuard + publishedStateCheck);
            default -> throw new IllegalArgumentException(mutation);
        };

        String diagnostic = mutation.equals("missing-binding") ? "EXPECTED_MAIN_SHA" : "final main";
        assertThat(failures(workflow.replace(publishBlock, invalidPublish)))
                .as("final main publication regression: %s", mutation)
                .anyMatch(message -> message.contains(diagnostic));
    }

    @Test
    void deliveryPublishesBothVariantsWithoutChangingTheStandardChannels() throws Exception {
        String delivery = Files.readString(findRepositoryRoot().resolve(".github/workflows/delivery.yml"));
        for (String tag : List.of("main", "latest", "${{ github.event.workflow_run.head_branch }}",
                "sha-${{ github.event.workflow_run.head_sha }}")) {
            assertThat(delivery).contains("type=raw,value=" + tag);
            assertThat(delivery).contains("type=raw,value=" + tag + "-onnx");
        }
        int onnx = delivery.indexOf("- name: Build and push embedded ONNX Docker image");
        assertThat(onnx).isPositive();
        String onnxBuild = stepBlock(delivery, onnx);
        assertThat(onnxBuild).contains("target: onnx", "tags: ${{ steps.meta_onnx.outputs.tags }}",
                "VERSION=${{ steps.meta.outputs.version }}",
                "BUILD_DATE=${{ fromJSON(steps.meta.outputs.json).labels['org.opencontainers.image.created'] }}",
                "VCS_REF=${{ github.event.workflow_run.head_sha }}");
        assertThat(onnxBuild).contains("labels: |\n            ${{ steps.meta.outputs.labels }}",
                "org.opencontainers.image.description=Taxonomy Architecture Analyzer with bundled multilingual ONNX embeddings for offline semantic search and similarity scoring.",
                "org.opencontainers.image.licenses=MIT AND Apache-2.0",
                "com.taxonomy.image.variant=onnx");
        assertThat(onnxBuild.indexOf("${{ steps.meta.outputs.labels }}"))
                .isLessThan(onnxBuild.indexOf("org.opencontainers.image.licenses=MIT AND Apache-2.0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"digest", "nonblocking", "disabled", "continue", "attestation", "target", "source"})
    void onnxVariantCannotReuseStandardEvidenceOrBypassItsGate(String mutation) throws Exception {
        String workflow = releaseWorkflow();
        int scan = workflow.indexOf("- name: Scan immutable ONNX release image digest");
        String scanBlock = stepBlock(workflow, scan);
        int build = workflow.indexOf("- name: Build and publish immutable ONNX release image");
        String buildBlock = stepBlock(workflow, build);
        String invalid = switch (mutation) {
            case "digest" -> workflow.replace(scanBlock, scanBlock.replace(
                    "steps.release_onnx_image.outputs.digest", "steps.release_image.outputs.digest"));
            case "nonblocking" -> workflow.replace(scanBlock, scanBlock.replace("exit-code: '1'", "exit-code: '0'"));
            case "disabled" -> workflow.replace(scanBlock, scanBlock.replace(
                    "if: steps.release_parameters.outputs.dry_run != 'true'", "if: false"));
            case "continue" -> workflow.replace(scanBlock, scanBlock + "\n        continue-on-error: true");
            case "attestation" -> workflow.replace(buildBlock, buildBlock.replace("sbom: true", "sbom: false"));
            case "target" -> workflow.replace(buildBlock, buildBlock.replace("target: onnx", "target: standard"));
            case "source" -> workflow.replace(buildBlock, buildBlock.replace(
                    "VCS_REF=${{ steps.release_source.outputs.sha }}", "VCS_REF=main"));
            default -> throw new IllegalArgumentException(mutation);
        };
        assertThat(failures(invalid)).as(mutation).anyMatch(message -> message.contains("ONNX"));
    }

    private static List<String> failures(String workflow) {
        List<String> failures = new ArrayList<>(ReleaseImageGate.failures(workflow));
        int build = workflow.indexOf("- name: Build and publish immutable release image");
        int bind = workflow.indexOf("- name: Bind release evidence and Helm deployment to immutable image digest");
        int publish = workflow.indexOf("- name: Publish complete release and trigger deployment");
        if (publish >= 0) {
            String block = stepBlock(workflow, publish);
            int digestComparison = block.indexOf("if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then");
            if (digestComparison >= 0 && digestComparison < block.indexOf("evidence_digest")) {
                failures.add("evidence digest must be read before its comparison");
            }
        }
        int onnxBuild = indexOf(workflow, "- name: Build and publish immutable ONNX release image",
                "ONNX image build", failures);
        int onnxScan = indexOf(workflow, "- name: Scan immutable ONNX release image digest",
                "ONNX image scan", failures);
        if (build >= 0 && onnxBuild >= 0 && onnxScan >= 0 && bind >= 0
                && !(build < onnxBuild && onnxBuild < onnxScan && onnxScan < bind)) {
            failures.add("ONNX build and blocking scan must precede evidence binding");
        }
        if (onnxBuild >= 0) {
            String block = stepBlock(workflow, onnxBuild);
            for (String required : List.of("id: release_onnx_image", "target: onnx", "context: .",
                    "if: steps.release_parameters.outputs.dry_run != 'true'",
                    "builder: ${{ steps.release_builder.outputs.name }}", "provenance: mode=max", "sbom: true",
                    "taxonomy:v${{ steps.release_parameters.outputs.release_version }}-onnx",
                    "BUILD_DATE=${{ steps.release_source.outputs.created }}",
                    "VCS_REF=${{ steps.release_source.outputs.sha }}",
                    "VERSION=${{ steps.release_parameters.outputs.release_version }}")) {
                require(block, required, "ONNX build must preserve " + required, failures);
            }
            if (build >= 0 && workflow.substring(build, onnxBuild).contains("git checkout")) {
                failures.add("ONNX and standard must build from the same immutable checkout");
            }
        }
        if (onnxScan >= 0) {
            String block = stepBlock(workflow, onnxScan);
            for (String required : List.of("if: steps.release_parameters.outputs.dry_run != 'true'",
                    "image-ref: ghcr.io/${{ github.repository_owner }}/taxonomy@${{ steps.release_onnx_image.outputs.digest }}",
                    "severity: HIGH,CRITICAL", "ignore-unfixed: false", "exit-code: '1'",
                    "output: release-onnx-image-trivy.sarif")) {
                require(block, required, "ONNX scan must preserve " + required, failures);
            }
            if (block.contains("continue-on-error:")) failures.add("ONNX scan must remain blocking");
        }
        if (bind >= 0) {
            String block = stepBlock(workflow, bind);
            require(block, "ONNX_IMAGE_DIGEST: ${{ steps.release_onnx_image.outputs.digest }}",
                    "ONNX evidence must consume its own image digest", failures);
            require(block, "imageVariant=\"onnx\"", "ONNX evidence must identify its variant", failures);
            require(block, "taxonomy-${RELEASE_VERSION}-onnx-release.sha256",
                    "ONNX evidence must have its own checksums", failures);
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
