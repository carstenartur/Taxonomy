package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryHardeningRepositoryTest {

    private static final String CI = ".github/workflows/ci-cd.yml";
    private static final String DELIVERY = ".github/workflows/delivery.yml";
    private static final String QUALITY_VERIFY =
            ".github/scripts/verify-quality-publication.py";
    private static final String DEPLOY_VERIFY =
            ".github/scripts/verify-deployment.py";
    private static final String CSS = "taxonomy-app/src/main/resources/static/css/"
            + "taxonomy-ergonomics.css";
    private static final String I18N = "taxonomy-app/src/main/resources/static/js/"
            + "taxonomy-i18n.js";
    private static final String UI_EVIDENCE =
            ".github/scripts/ui-role-state-evidence.mjs";
    private static final String RANCHER =
            "deploy/helm/taxonomy/values-rancher-rke2.yaml";
    private static final String SMALL = "deploy/helm/taxonomy/values-small.yaml";
    private static final String MODEL_DOWNLOAD =
            ".github/scripts/download-embedding-model.sh";
    private static final String DOCKERFILE = "Dockerfile";

    @Test
    void repositoryPreservesCommitBoundQualityAndDeploymentHardening()
            throws Exception {
        Path root = findRepositoryRoot();
        Sources sources = Sources.read(root);

        assertThat(inspect(sources))
                .as("delivery hardening contract")
                .isEmpty();
        assertThat(root.resolve(".github/scripts/check-delivery-hardening.py"))
                .doesNotExist();
        assertThat(sources.ci())
                .doesNotContain("check-delivery-hardening.py");
    }

    @Test
    void missingProvenanceDeploymentAndErgonomicEvidenceFailsClosed()
            throws Exception {
        Sources source = Sources.read(findRepositoryRoot());
        Sources damaged = new Sources(
                source.ci().replace("--source-tree \"$source_tree\"", ""),
                source.delivery().replace(
                        "render-deployment-evidence-${{ github.event.workflow_run.head_sha }}",
                        ""),
                source.qualityVerify(),
                source.deployVerify(),
                source.css().replace(
                        "max-height: min(65vh, 42rem) !important;", ""),
                source.i18n(),
                source.uiEvidence(),
                source.rancher(),
                source.small(),
                source.modelDownload(),
                source.dockerfile());

        assertThat(inspect(damaged)).containsExactly(
                CI + " is missing '--source-tree \"$source_tree\"'",
                DELIVERY + " is missing "
                        + "'render-deployment-evidence-${{ "
                        + "github.event.workflow_run.head_sha }}'",
                CSS + " is missing 'max-height: min(65vh, 42rem) !important;'");
    }

    private static List<String> inspect(Sources source) {
        List<String> failures = new ArrayList<>();

        requireAll(source.ci(), CI, failures,
                "python3 .github/scripts/test-generate-quality-site.py",
                "python3 .github/scripts/test-verify-quality-publication.py",
                "python3 .github/scripts/test-verify-deployment.py",
                "node .github/scripts/test-taxonomy-base-path.mjs",
                "python3 .github/scripts/generate-quality-site.py",
                "python3 .github/scripts/verify-quality-publication.py",
                "--commit \"$GITHUB_SHA\"",
                "--source-tree \"$source_tree\"",
                "--build-id \"$build_id\"",
                "--tool \"java=$java_version\"",
                "--tool \"maven=$maven_version\"",
                "taxonomy-coverage/target/site/jacoco-aggregate/jacoco.xml",
                "Restore pinned embedding model",
                "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9");

        requireAll(source.delivery(), DELIVERY, failures,
                "Checkout verified quality tooling",
                "Verify report provenance and badge consistency",
                "Verify published GitHub Pages evidence",
                "QUALITY_REPORT_BASE_URL",
                "verify-quality-publication.py",
                "--base-url \"$base_url\"",
                "EXPECTED_SHA: ${{ github.event.workflow_run.head_sha }}",
                "keep_files: false",
                "force_orphan: true",
                "query[\"ref\"] = expected",
                "render-deploy-hook.json",
                "render-hook-failure.json",
                "if ! curl --fail --silent --show-error --retry 3",
                "--connect-timeout 15 --max-time 90",
                "Render deploy hook request failed after bounded retries.",
                "render-verification.json",
                "RENDER_API_KEY",
                "RENDER_SERVICE_ID",
                "RENDER_DEPLOY_ENABLED",
                "name: Render deployment disabled",
                "vars.RENDER_DEPLOY_ENABLED != 'true'",
                "vars.RENDER_DEPLOY_ENABLED == 'true'",
                "\"deploymentState\": \"disabled\"",
                "\"deploymentState\": \"triggered\"",
                "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing",
                "BASE_URL: ${{ vars.RENDER_BASE_URL || "
                        + "'https://taxonomy-analyzer.onrender.com' }}",
                "python3 .github/scripts/verify-deployment.py",
                "render-deployment-evidence-${{ github.event.workflow_run.head_sha }}");
        forbid(source.delivery(), DELIVERY, failures,
                "keep_files: true",
                "must replace the report tree atomically, not retain stale files");
        forbid(source.delivery(), DELIVERY, failures,
                "RENDER_DEPLOY_HOOK_URL is not configured; "
                        + "Render deployment is disabled.",
                "enabled Render delivery must fail closed when its hook secret "
                        + "is missing");
        String deployBlock = after(source.delivery(), "  deploy-render:");
        forbid(deployBlock, DELIVERY, failures,
                "if-no-files-found: warn",
                "Render delivery evidence must never be optional");

        requireAll(source.qualityVerify(), QUALITY_VERIFY, failures,
                "\"sourceTree\"",
                "\"buildId\"",
                "\"tools\"",
                "expected_test_message",
                "expected_coverage_message",
                "verify_remote_once",
                "\"Cache-Control\": \"no-cache\"");

        requireAll(source.deployVerify(), DEPLOY_VERIFY, failures,
                "RENDER_FAILURE_STATES",
                "fetch_render_deploy",
                "renderDeployId",
                "renderDeployStatus",
                "\"deploymentState\": \"verifying\"",
                "\"deploymentState\": \"succeeded\"",
                "\"deploymentState\": \"failed\"",
                "root smoke test",
                "write_evidence(args.evidence_file, evidence)");

        requireAll(source.css(), CSS, failures,
                ".card-body[style*=\"max-height\"]:not(:has(> #taxonomyTree))",
                ".card-body:has(> #taxonomyTree)",
                "max-height: min(65vh, 42rem) !important;");
        requireAll(source.i18n(), I18N, failures,
                "detectApplicationBasePath",
                "resolveApplicationUrl",
                "installBasePathAwareFetch",
                "window.fetch = wrappedFetch");
        requireAll(source.uiEvidence(), UI_EVIDENCE, failures,
                "measureTaxonomyTreeViewport",
                "maxAllowedHeight",
                "Taxonomy tree viewport is unbounded");

        requireAll(source.small(), SMALL, failures,
                "cpu: 100m",
                "cpu: \"500m\"",
                "memory: 768Mi",
                "memory: 1536Mi",
                "TAXONOMY_EMBEDDING_ENABLED: \"false\"",
                "TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: \"false\"",
                "TAXONOMY_SEARCH_DIRECTORY_TYPE: local-heap",
                "MaxRAMPercentage=65.0");
        forbid(source.small(), SMALL, failures,
                "cpu: \"2\"",
                "must not use the universal two-CPU limit");

        requireAll(source.rancher(), RANCHER, failures,
                "nginx.ingress.kubernetes.io/rewrite-target: \"/$2\"",
                "nginx.ingress.kubernetes.io/x-forwarded-prefix: \"/taxonomy\"",
                "path: /taxonomy(/|$)(.*)",
                "cpu: \"500m\"");
        requireAll(source.modelDownload(), MODEL_DOWNLOAD, failures,
                "model_is_valid",
                "--retry 12",
                "--retry-max-time 600",
                "mktemp -d",
                "MODEL_PROVENANCE.txt");
        requireAll(source.dockerfile(), DOCKERFILE, failures,
                "ARG VCS_REF=unknown",
                "git.commit.id=%s",
                "taxonomy-app/src/main/resources/git.properties");
        return List.copyOf(failures);
    }

    private static void requireAll(
            String source,
            String path,
            List<String> failures,
            String... needles) {
        for (String needle : needles) {
            if (!source.contains(needle)) {
                failures.add(path + " is missing '" + needle + "'");
            }
        }
    }

    private static void forbid(
            String source,
            String path,
            List<String> failures,
            String needle,
            String message) {
        if (source.contains(needle)) {
            failures.add(path + " " + message);
        }
    }

    private static String after(String source, String delimiter) {
        int index = source.indexOf(delimiter);
        return index < 0 ? "" : source.substring(index + delimiter.length());
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

    private record Sources(
            String ci,
            String delivery,
            String qualityVerify,
            String deployVerify,
            String css,
            String i18n,
            String uiEvidence,
            String rancher,
            String small,
            String modelDownload,
            String dockerfile) {

        static Sources read(Path root) throws IOException {
            return new Sources(
                    read(root, CI),
                    read(root, DELIVERY),
                    read(root, QUALITY_VERIFY),
                    read(root, DEPLOY_VERIFY),
                    read(root, CSS),
                    read(root, I18N),
                    read(root, UI_EVIDENCE),
                    read(root, RANCHER),
                    read(root, SMALL),
                    read(root, MODEL_DOWNLOAD),
                    read(root, DOCKERFILE));
        }

        private static String read(Path root, String path) throws IOException {
            return Files.readString(root.resolve(path), StandardCharsets.UTF_8);
        }
    }
}
