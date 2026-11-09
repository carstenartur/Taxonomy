package com.taxonomy.tooling;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Checks that release publication verifies the exact OCI digest. */
public final class ReleaseImageGate {
    private ReleaseImageGate() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("Usage: ReleaseImageGate <deploy-release.yml>");
            System.exit(2);
        }
        int exitCode = run(Path.of(arguments[0]), System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(Path workflow, PrintStream output, PrintStream error) {
        List<String> failures;
        try {
            failures = failures(Files.readString(workflow));
        } catch (IOException failure) {
            failures = List.of("Could not read deploy-release.yml: " + failure.getMessage());
        }
        if (!failures.isEmpty()) {
            error.println("Immutable release image gate failed:");
            failures.forEach(failure -> error.println("- " + failure));
            return 1;
        }
        output.println("Immutable release image gate passed: a container Buildx builder is selected, "
                + "provenance/SBOM attestations are enabled, "
                + "the pushed digest is vulnerability-scanned, release evidence is digest-bound, "
                + "and publication follows a fresh comparison with the previously verified main.");
        return 0;
    }

    static List<String> failures(String source) {
        String workflow = source.replace("\r\n", "\n").replace('\r', '\n');
        List<String> failures = new ArrayList<>();
        require(workflow, failures,
                "id: release_image",
                "provenance: mode=max",
                "sbom: true",
                "- name: Scan immutable release image digest",
                "aquasecurity/trivy-action@ed142fd0673e97e23eac54620cfb913e5ce36c25",
                "image-ref: ghcr.io/${{ github.repository_owner }}/taxonomy@${{ steps.release_image.outputs.digest }}",
                "severity: HIGH,CRITICAL",
                "exit-code: '1'",
                "- name: Bind release evidence and Helm deployment to immutable image digest",
                "IMAGE_DIGEST: ${{ steps.release_image.outputs.digest }}",
                "^sha256:[0-9a-f]{64}$",
                "immutable_image=\"${repository}@${IMAGE_DIGEST}\"",
                "\"attestations\"",
                "\"provenance\": True",
                "\"sbom\": True",
                "\"result\": \"passed\"",
                "--set-string \"image.digest=${IMAGE_DIGEST}\"",
                "taxonomy-${RELEASE_VERSION}-kubernetes-digest.yaml",
                "taxonomy-${RELEASE_VERSION}-image-evidence.json",
                "taxonomy-${RELEASE_VERSION}-image-trivy.sarif",
                "taxonomy-${RELEASE_VERSION}-release.sha256",
                "retention-days: 90",
                "docker buildx imagetools inspect \"$image\"",
                "if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then");

        int builder = workflow.indexOf("- name: Set up release image builder");
        int build = workflow.indexOf("- name: Build and publish immutable release image");
        int scan = workflow.indexOf("- name: Scan immutable release image digest");
        int bind = workflow.indexOf("- name: Bind release evidence and Helm deployment to immutable image digest");
        int archive = workflow.indexOf("- name: Archive immutable release image evidence");
        int finalGates = workflow.indexOf("- name: Verify exact final main release gate matrix");
        int publish = workflow.indexOf("- name: Publish complete release and trigger deployment");
        if (builder < 0 || build < 0 || scan < 0 || bind < 0 || archive < 0 || finalGates < 0 || publish < 0) {
            failures.add("Could not determine immutable image gate order: substring not found");
            return List.copyOf(failures);
        }
        if (!(finalGates < builder && builder < build && build < scan && scan < bind
                && bind < archive && archive < publish)) {
            failures.add("the exact final main gate matrix must pass before the release builder "
                    + "is configured and the image is built, digest-scanned, evidence-bound, archived and published");
        }

        require(stepBlock(workflow, builder), failures,
                "id: release_builder",
                "if: steps.release_parameters.outputs.dry_run != 'true'",
                "uses: docker/setup-buildx-action@f87e5991a6d7451dcb8d9637bfbc97413f497069",
                "driver: docker-container",
                "buildkitd-config-inline: |",
                "[registry.\"docker.io\"]",
                "mirrors = [\"mirror.gcr.io\"]");
        require(stepBlock(workflow, build), failures, "builder: ${{ steps.release_builder.outputs.name }}");

        String scanBlock = workflow.substring(scan, Math.max(scan, bind));
        if (!scanBlock.contains("exit-code: '1'")) {
            failures.add("immutable digest scan must fail the release on blocking findings");
        }
        if (!scanBlock.contains("steps.release_image.outputs.digest")) {
            failures.add("image scan must consume the build-push digest output");
        }

        String bindBlock = workflow.substring(bind, Math.max(bind, archive));
        if (!bindBlock.contains("gh release upload")) {
            failures.add("digest evidence must be attached to the draft release");
        }
        if (!bindBlock.contains("image.digest=${IMAGE_DIGEST}")) {
            failures.add("Helm release evidence must include a digest-bound manifest");
        }

        String publishBlock = stepBlock(workflow, publish);
        int evidenceCheck = publishBlock.indexOf("evidence_digest");
        int publishRelease = publishBlock.indexOf("gh release edit");
        if (evidenceCheck < 0 || publishRelease < 0 || evidenceCheck > publishRelease) {
            failures.add("release image evidence must be verified before the draft becomes public");
        }
        if (!publishBlock.contains("image=\"${repository}@${IMAGE_DIGEST}\"")) {
            failures.add("final publication must inspect the immutable digest, not only a tag");
        }
        require(publishBlock, failures, "EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}");
        int previous = -1;
        for (String needle : List.of(
                "if [[ \"$evidence_digest\" != \"$IMAGE_DIGEST\" ]]; then",
                "is_draft=$(gh release view \"$tag\" --json isDraft",
                "git fetch origin \"refs/heads/main:refs/remotes/origin/main\" --force",
                "publication_main=$(git rev-parse --verify \"refs/remotes/origin/main^{commit}\")",
                "if [[ \"$publication_main\" != \"$EXPECTED_MAIN_SHA\" ]]; then",
                "if [[ \"$is_draft\" == \"true\" ]]; then",
                "gh release edit",
                "curl -fSL --retry 3 \"$RENDER_DEPLOY_HOOK_URL\"")) {
            int position = publishBlock.indexOf(needle);
            if (position <= previous) {
                failures.add("final main must be freshly fetched, resolved and compared before "
                        + "either the draft branch or publication and deployment");
                break;
            }
            previous = position;
        }
        return List.copyOf(failures);
    }

    static String stepBlock(String workflow, int start) {
        int end = workflow.indexOf("\n      - name:", start);
        return end < 0 ? workflow.substring(start) : workflow.substring(start, end);
    }

    private static void require(String text, List<String> failures, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) {
                String quote = needle.contains("'") && !needle.contains("\"") ? "\"" : "'";
                String literal = needle.replace("\\", "\\\\").replace(quote, "\\" + quote);
                failures.add("deploy-release.yml is missing " + quote + literal + quote);
            }
        }
    }
}
