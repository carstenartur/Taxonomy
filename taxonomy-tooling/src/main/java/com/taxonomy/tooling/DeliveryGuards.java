package com.taxonomy.tooling;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Preserved release/delivery repository predicates, executed by the existing JUnit owner. */
final class DeliveryGuards {
  static int run(String command, Path root, PrintStream out, PrintStream err) {
    try {
      root = root.toAbsolutePath().normalize();
      while (root != null && !Files.isRegularFile(root.resolve("taxonomy-tooling/pom.xml")))
        root = root.getParent();
      if (root == null) throw new IllegalArgumentException("Cannot find repository root");
      var failures = new ArrayList<String>();
      if (command.equals("check-release-delivery-contract")) release(root, failures);
      else hardening(root, failures);
      if (!failures.isEmpty()) {
        err.println("Delivery contract failed:");
        failures.forEach(f -> err.println("- " + f));
        return 1;
      }
      out.println(
          "Delivery contract passed: source-bound reports, immutable artifacts, exact gates and"
              + " final-only publication verified.");
      return 0;
    } catch (Exception e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  static void require(
      Path root, List<String> failures, String path, String needle, boolean required)
      throws IOException {
    String text = Files.readString(root.resolve(path));
    if (text.contains(needle) != required)
      failures.add(path + (required ? " is missing " : " still contains ") + needle);
  }

  static void release(Path root, List<String> failures) throws Exception {
    require(root, failures, "pom.xml", "<module>taxonomy-tooling</module>", true);
    require(root, failures, "pom.xml", "<id>release-check</id>", true);
    require(
        root, failures, "pom.xml", "<taxonomy.release.check>true</taxonomy.release.check>", true);
    require(root, failures, "pom.xml", ".github/scripts/check-release-plan.py", false);
    require(root, failures, "pom.xml", "<executable>python3</executable>", false);
    require(
        root,
        failures,
        "taxonomy-tooling/pom.xml",
        "<artifactId>taxonomy-tooling</artifactId>",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/pom.xml",
        "<mainClass>com.taxonomy.tooling.TaxonomyTooling</mainClass>",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "release verification requires a clean checkout",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "maven-release-plugin would create a second SCM release authority",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "reactorPomPaths",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "modelsByCoordinate",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "effectiveProperties",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "unresolved version property",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "declares module",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "external ",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleasePlanValidator.java",
        "requireNewer",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleaseParametersResolver.java",
        "validateReleaseRequestAnchor",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleaseParametersResolver.java",
        "request_revision must advance from",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleaseParametersResolver.java",
        "release request commit must change only",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleaseParametersResolver.java",
        "validateStagedReleaseAncestry",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleaseParametersResolver.java",
        "next_version_increment must be patch, minor or major",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/ReleaseParametersResolver.java",
        "appendOutputs",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/VersionStateVerifier.java",
        "CITATION.cff",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/VersionStateVerifier.java",
        "codemeta.json",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/VersionStateVerifier.java",
        "deploy/helm/taxonomy/Chart.yaml",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/VersionStateVerifier.java",
        "rootProjectVersion",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java",
        "case \"resolve-release-parameters\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java",
        "case \"check-version-state\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java",
        "case \"check-release-plan\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java",
        "case \"compare-versions\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/TaxonomyTooling.java",
        "case \"read-pom-version\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "DEFER_RELEASE_PUBLICATION=${DEFER_RELEASE_PUBLICATION:-false}",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "\"${TOOLING_JAR:?TOOLING_JAR is required}\"",
        true);
    require(root, failures, ".github/scripts/release.sh", "run_maven_release_check()", true);
    require(root, failures, ".github/scripts/release.sh", "run_release_plan_check()", true);
    require(root, failures, ".github/scripts/release.sh", "check_version_state()", true);
    require(root, failures, ".github/scripts/release.sh", "stage_version_metadata()", true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "git ls-files -z -- 'pom.xml' ':(glob)**/pom.xml'",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "java -jar \"$TOOLING_JAR\" compare-versions",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "java -jar \"$TOOLING_JAR\" check-release-plan",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "java -jar \"$TOOLING_JAR\" check-version-state",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "java -jar \"$TOOLING_JAR\" read-pom-version --stdin",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "run_release_plan_check \"$RELEASE_CHECK_STATE\" true",
        true);
    require(
        root, failures, ".github/scripts/release.sh", "run_release_plan_check release false", true);
    require(
        root, failures, ".github/scripts/release.sh", "run_release_plan_check release true", true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "run_maven_release_check release release-check,ci clean verify",
        true);
    require(root, failures, ".github/scripts/release.sh", "! -name 'taxonomy-tooling-*.jar'", true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "if [[ \"$DEFER_RELEASE_PUBLICATION\" == \"true\" ]]; then",
        true);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "remains a draft until downstream artifacts and final CI succeed",
        true);
    require(
        root, failures, ".github/scripts/release.sh", "test \"$RELEASE_IS_DRAFT\" = true", true);
    require(root, failures, ".github/scripts/release.sh", "VERSION_STATE_HELPER", false);
    require(root, failures, ".github/scripts/release.sh", "resolve-release-parameters.py", false);
    require(root, failures, ".github/scripts/release.sh", "check-version-state.py", false);
    require(root, failures, ".github/scripts/release.sh", "check-release-plan.py", false);
    require(
        root,
        failures,
        ".github/scripts/release.sh",
        "python3 - <<'PY'\nimport os\nrelease =",
        false);
    require(
        root, failures, ".github/workflows/deploy-release.yml", "next_development_version:", true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "Exact next development version",
        true);
    require(
        root, failures, ".github/workflows/deploy-release.yml", "next_version_increment:", true);
    require(root, failures, ".github/workflows/deploy-release.yml", "type: choice", true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "INPUT_NEXT_DEVELOPMENT_VERSION:",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "INPUT_NEXT_VERSION_INCREMENT:",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Build Java release tooling",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "./mvnw -B -pl taxonomy-tooling -am package -DskipTests",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "run: java -jar \"$RUNNER_TEMP/taxonomy-tooling.jar\" resolve-release-parameters --root .",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "./mvnw -B -pl taxonomy-tooling -am test",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "TOOLING_JAR: ${{ runner.temp }}/taxonomy-tooling.jar",
        true);
    require(root, failures, ".github/workflows/deploy-release.yml", "group: release-main", true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "ref: ${{ github.event_name == 'push' && github.sha || 'main' }}",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "SOURCE_BRANCH: ${{ github.ref_name }}",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "if: steps.release_parameters.outputs.resume_staged_release != 'true'",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Validate staged release for resume",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "if: steps.release_parameters.outputs.resume_staged_release == 'true'",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "--release-commit \"$tag\" --main-commit origin/main",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "Release $tag must still be a draft before publication resumes",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "java -jar \"$RUNNER_TEMP/taxonomy-tooling.jar\" check-version-state",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "DEFER_RELEASE_PUBLICATION: 'true'",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "cp .github/scripts/verify-exact-release-gates.sh",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "bash -n .github/scripts/verify-exact-release-gates.sh",
        true);
    require(root, failures, ".github/workflows/deploy-release.yml", "timeout-minutes: 300", true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Record exact final main snapshot",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Checkout immutable release source",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Package and stage immutable Helm artifacts",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Build and publish immutable release image",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Scan immutable release image digest",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Bind release evidence and Helm deployment to immutable image digest",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Archive immutable release image evidence",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Verify exact final main release gate matrix",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "- name: Publish complete release and trigger deployment",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "run: bash \"$RUNNER_TEMP/verify-exact-release-gates.sh\"",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "docker buildx imagetools inspect \"$image\"",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "Draft release is missing required Helm asset",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "Render deployment triggered after complete release publication.",
        true);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "run: python3 .github/scripts/resolve-release-parameters.py",
        false);
    require(
        root,
        failures,
        ".github/workflows/deploy-release.yml",
        "python3 .github/scripts/test-resolve-release-parameters.py",
        false);
    require(
        root, failures, ".github/workflows/deploy-release.yml", "check-version-state.py", false);
    require(root, failures, ".github/workflows/deploy-release.yml", "VERSION_STATE_HELPER:", false);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "EXPECTED_MAIN_SHA=${EXPECTED_MAIN_SHA:-}",
        true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "readonly -a REQUIRED_WORKFLOWS=(",
        true);
    require(root, failures, ".github/scripts/verify-exact-release-gates.sh", "ci-cd.yml", true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "database-compatibility.yml",
        true);
    require(root, failures, ".github/scripts/verify-exact-release-gates.sh", "codeql.yml", true);
    require(
        root, failures, ".github/scripts/verify-exact-release-gates.sh", "security-scan.yml", true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "assert_main_unchanged",
        true);
    require(root, failures, ".github/scripts/verify-exact-release-gates.sh", "--branch main", true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "--commit \"$EXPECTED_MAIN_SHA\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "gh workflow run \"$workflow\" --ref main",
        true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "run_sha=$(gh run view \"$run_id\" --json headSha --jq '.headSha')",
        true);
    require(root, failures, ".github/scripts/verify-exact-release-gates.sh", "watch_exit=0", true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "gh run watch \"$run_id\" --exit-status || watch_exit=$?",
        true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "if [[ \"$status\" != \"completed\" || \"$conclusion\" != \"success\" ]]",
        true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "if (( watch_exit != 0 ))",
        true);
    require(
        root,
        failures,
        ".github/scripts/verify-exact-release-gates.sh",
        "echo \"All exact release gates passed",
        true);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "./mvnw -B -pl taxonomy-tooling -am package -DskipTests",
        true);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "java -jar \"$tooling_jar\" check-release-plan",
        true);
    require(root, failures, ".github/workflows/ci-cd.yml", "--state development", true);
    require(root, failures, ".github/workflows/ci-cd.yml", "--require-clean true", true);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "python3 .github/scripts/test-resolve-release-parameters.py",
        false);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "python3 .github/scripts/test-check-release-plan.py",
        false);
    require(
        root, failures, ".github/workflows/ci-cd.yml", "./mvnw -B -Prelease-check validate", false);
    releaseAdditional(root, failures);
  }

  static void hardening(Path root, List<String> failures) throws Exception {
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "core_contract=.github/scripts/ci-core-contracts.sh",
        true);
    require(root, failures, ".github/workflows/ci-cd.yml", "bash \"$core_contract\"", true);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "run: bash .github/scripts/stage-core-quality-reports.sh",
        true);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "run: bash .github/scripts/finalize-quality-evidence.sh",
        true);
    require(root, failures, ".github/workflows/ci-cd.yml", "Restore pinned embedding model", true);
    require(
        root,
        failures,
        ".github/workflows/ci-cd.yml",
        "actions/cache@55cc8345863c7cc4c66a329aec7e433d2d1c52a9",
        true);
    require(root, failures, ".github/scripts/ci-core-contracts.sh", "set -euo pipefail", true);
    require(root, failures, ".github/scripts/ci-core-contracts.sh", "QualitySiteTest", true);
    require(root, failures, ".github/scripts/ci-core-contracts.sh", "JavascriptContractTest", true);
    require(
        root, failures, ".github/scripts/ci-core-contracts.sh", "CoreDeliveryContractsTest", true);
    require(
        root, failures, ".github/scripts/stage-core-quality-reports.sh", "set -euo pipefail", true);
    require(
        root,
        failures,
        ".github/scripts/stage-core-quality-reports.sh",
        "taxonomy-coverage/target/site/jacoco-aggregate",
        true);
    require(
        root,
        failures,
        ".github/scripts/stage-core-quality-reports.sh",
        "Aggregate JaCoCo XML is missing",
        true);
    require(
        root,
        failures,
        ".github/scripts/stage-core-quality-reports.sh",
        "source_tree=$(git rev-parse \"${GITHUB_SHA}^{tree}\")",
        true);
    require(
        root,
        failures,
        ".github/scripts/stage-core-quality-reports.sh",
        "build_id=\"${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}.core\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/stage-core-quality-reports.sh",
        "Staged core evidence",
        true);
    require(root, failures, ".github/scripts/stage-core-quality-reports.sh", "Core build ID", true);
    require(
        root, failures, ".github/scripts/finalize-quality-evidence.sh", "set -euo pipefail", true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "target/observability-performance",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "target/ui-verification/summary.json",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "source_tree=$(git rev-parse \"${GITHUB_SHA}^{tree}\")",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "build_id=\"${GITHUB_RUN_ID}.${GITHUB_RUN_ATTEMPT}\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "taxonomy-tooling.sh\" generate-quality-site",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "taxonomy-tooling.sh\" verify-quality-publication",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "--root target/quality-reports --commit \"$GITHUB_SHA\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "--source-tree \"$source_tree\" --build-id \"$build_id\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "--tool \"java=$(java -version 2>&1 | sed -n '1p')\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "--tool \"maven=$(./mvnw -version 2>&1 | sed -n '1p')\"",
        true);
    require(
        root,
        failures,
        ".github/scripts/finalize-quality-evidence.sh",
        "--root target/quality-reports --expected-commit \"$GITHUB_SHA\"",
        true);
    require(
        root, failures, ".github/scripts/finalize-quality-evidence.sh", "Verified commit:", true);
    require(root, failures, ".github/scripts/finalize-quality-evidence.sh", "Build ID:", true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "Checkout verified quality tooling",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "Verify report provenance and badge consistency",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "Verify published GitHub Pages evidence",
        true);
    require(root, failures, ".github/workflows/delivery.yml", "QUALITY_REPORT_BASE_URL", true);
    require(root, failures, ".github/workflows/delivery.yml", "verify-quality-publication", true);
    require(root, failures, ".github/workflows/delivery.yml", "--base-url \"$base_url\"", true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "EXPECTED_SHA: ${{ github.event.workflow_run.head_sha }}",
        true);
    require(root, failures, ".github/workflows/delivery.yml", "keep_files: false", true);
    require(root, failures, ".github/workflows/delivery.yml", "force_orphan: true", true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "query.put(\"ref\", expected)",
        true);
    require(root, failures, ".github/workflows/delivery.yml", "render-deploy-hook.json", true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "render-delivery-state --state hook-failure",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "if ! curl --fail --silent --show-error --retry 3",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "--connect-timeout 15 --max-time 90",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "Render deploy hook request failed after bounded retries.",
        true);
    require(root, failures, ".github/workflows/delivery.yml", "render-verification.json", true);
    require(root, failures, ".github/workflows/delivery.yml", "RENDER_API_KEY", true);
    require(root, failures, ".github/workflows/delivery.yml", "RENDER_SERVICE_ID", true);
    require(root, failures, ".github/workflows/delivery.yml", "RENDER_DEPLOY_ENABLED", true);
    require(
        root, failures, ".github/workflows/delivery.yml", "name: Render deployment disabled", true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "vars.RENDER_DEPLOY_ENABLED != 'true'",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "vars.RENDER_DEPLOY_ENABLED == 'true'",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "render-delivery-state --state disabled",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "render-delivery-state --state triggered",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "RENDER_DEPLOY_ENABLED is true but RENDER_DEPLOY_HOOK_URL is missing",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "BASE_URL: ${{ vars.RENDER_BASE_URL || 'https://taxonomy-analyzer.onrender.com' }}",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "bash .github/scripts/taxonomy-tooling.sh verify-deployment",
        true);
    require(
        root,
        failures,
        ".github/workflows/delivery.yml",
        "render-deployment-evidence-${{ github.event.workflow_run.head_sha }}",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "\"sourceTree\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "\"buildId\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "\"tools\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "passed + \" passed\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "percent",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "verifyRemote",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/QualitySite.java",
        "\"Cache-Control\", \"no-cache\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "RENDER_FAILURE_STATES",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "fetchRenderDeploy",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "renderDeployId",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "renderDeployStatus",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "\"verifying\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "\"succeeded\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "\"failed\"",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "root smoke test",
        true);
    require(
        root,
        failures,
        "taxonomy-tooling/src/main/java/com/taxonomy/tooling/DeploymentVerifier.java",
        "writeEvidence",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css",
        ".card-body[style*=\"max-height\"]:not(:has(> #taxonomyTree))",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css",
        ".card-body:has(> #taxonomyTree)",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/css/taxonomy-ergonomics.css",
        "max-height: min(65vh, 42rem) !important;",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js",
        "detectApplicationBasePath",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js",
        "resolveApplicationUrl",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js",
        "installBasePathAwareFetch",
        true);
    require(
        root,
        failures,
        "taxonomy-app/src/main/resources/static/js/taxonomy-i18n.js",
        "window.fetch = wrappedFetch",
        true);
    require(
        root,
        failures,
        ".github/scripts/ui-role-state-evidence.mjs",
        "measureTaxonomyTreeViewport",
        true);
    require(root, failures, ".github/scripts/ui-role-state-evidence.mjs", "maxAllowedHeight", true);
    require(
        root,
        failures,
        ".github/scripts/ui-role-state-evidence.mjs",
        "Taxonomy tree viewport is unbounded",
        true);
    require(root, failures, "deploy/helm/taxonomy/values-small.yaml", "cpu: 100m", true);
    require(root, failures, "deploy/helm/taxonomy/values-small.yaml", "cpu: \"500m\"", true);
    require(root, failures, "deploy/helm/taxonomy/values-small.yaml", "memory: 768Mi", true);
    require(root, failures, "deploy/helm/taxonomy/values-small.yaml", "memory: 1536Mi", true);
    require(
        root,
        failures,
        "deploy/helm/taxonomy/values-small.yaml",
        "TAXONOMY_EMBEDDING_ENABLED: \"false\"",
        true);
    require(
        root,
        failures,
        "deploy/helm/taxonomy/values-small.yaml",
        "TAXONOMY_EMBEDDING_ALLOW_DOWNLOAD: \"false\"",
        true);
    require(
        root,
        failures,
        "deploy/helm/taxonomy/values-small.yaml",
        "TAXONOMY_SEARCH_DIRECTORY_TYPE: local-heap",
        true);
    require(
        root, failures, "deploy/helm/taxonomy/values-small.yaml", "MaxRAMPercentage=65.0", true);
    require(
        root,
        failures,
        "deploy/helm/taxonomy/values-rancher-rke2.yaml",
        "nginx.ingress.kubernetes.io/rewrite-target: \"/$2\"",
        true);
    require(
        root,
        failures,
        "deploy/helm/taxonomy/values-rancher-rke2.yaml",
        "nginx.ingress.kubernetes.io/x-forwarded-prefix: \"/taxonomy\"",
        true);
    require(
        root,
        failures,
        "deploy/helm/taxonomy/values-rancher-rke2.yaml",
        "path: /taxonomy(/|$)(.*)",
        true);
    require(root, failures, "deploy/helm/taxonomy/values-rancher-rke2.yaml", "cpu: \"500m\"", true);
    require(root, failures, ".github/scripts/download-embedding-model.sh", "model_is_valid", true);
    require(root, failures, ".github/scripts/download-embedding-model.sh", "--retry 12", true);
    require(
        root,
        failures,
        ".github/scripts/download-embedding-model.sh",
        "--retry-max-time 600",
        true);
    require(root, failures, ".github/scripts/download-embedding-model.sh", "mktemp -d", true);
    require(
        root,
        failures,
        ".github/scripts/download-embedding-model.sh",
        "MODEL_PROVENANCE.txt",
        true);
    require(root, failures, "Dockerfile", "ARG VCS_REF=unknown", true);
    require(root, failures, "Dockerfile", "git.commit.id=%s", true);
    require(root, failures, "Dockerfile", "taxonomy-app/src/main/resources/git.properties", true);
    hardeningAdditional(root, failures);
  }

  static void releaseAdditional(Path root, List<String> f) throws Exception {
    String pom = read(root, "pom.xml"),
        script = read(root, ".github/scripts/release.sh"),
        workflow = read(root, ".github/workflows/deploy-release.yml"),
        gate = read(root, ".github/scripts/verify-exact-release-gates.sh");
    f.addAll(resourceDiagnosticFailures(workflow));
    if (pom.contains("<artifactId>maven-release-plugin</artifactId>"))
      f.add("pom.xml must not introduce a competing SCM authority");
    if (script.split("\n  stage_version_metadata\n", -1).length - 1 != 2)
      f.add("release.sh must stage all tracked reactor POMs twice");
    for (String forbidden :
        List.of(
            "git add pom.xml */pom.xml",
            "grep -R \"SNAPSHOT\" --include=\"pom.xml\"",
            "./mvnw -B clean verify -Pci"))
      if (script.contains(forbidden))
        f.add("release.sh bypasses declared reactor/release verification: " + forbidden);
    if (gate.split("assert_main_unchanged", -1).length - 1 < 4)
      f.add("exact release gates must bind unchanged main throughout");
    for (String forbidden :
        List.of(
            "      release_version:",
            "      resume_staged_release:",
            "INPUT_RELEASE_VERSION:",
            "INPUT_RESUME_STAGED_RELEASE:",
            "group: release-${{ github.ref_name }}",
            "\n          ref: main\n",
            "SOURCE_BRANCH: main",
            "main_sha=$(git rev-parse origin/main)"))
      if (workflow.contains(forbidden)) f.add("unsafe release configuration: " + forbidden);
    List<String> order =
        List.of(
            "Checkout authoritative release source",
            "Set up JDK 25",
            "Build Java release tooling",
            "Resolve release parameters",
            "Build and stage release, then prepare next development version",
            "Validate staged release for resume",
            "Record exact final main snapshot",
            "Verify exact final main release gate matrix",
            "Checkout immutable release source",
            "Package and stage immutable Helm artifacts",
            "Build and publish immutable release image",
            "Scan immutable release image digest",
            "Bind release evidence and Helm deployment to immutable image digest",
            "Archive immutable release image evidence",
            "Publish complete release and trigger deployment");
    int previous = -1;
    for (String step : order) {
      int pos = workflow.indexOf("- name: " + step);
      if (pos <= previous) {
        f.add("Release step order differs at " + step);
        return;
      }
      previous = pos;
    }
    String checkout = between(workflow, "Checkout authoritative release source", "Set up JDK 25"),
        stage =
            between(
                workflow,
                "Build and stage release, then prepare next development version",
                "Validate staged release for resume"),
        resume =
            between(
                workflow, "Validate staged release for resume", "Record exact final main snapshot"),
        immutable =
            between(
                workflow,
                "Checkout immutable release source",
                "Package and stage immutable Helm artifacts"),
        finalGate =
            between(
                workflow,
                "Verify exact final main release gate matrix",
                "Checkout immutable release source"),
        publish =
            workflow.substring(
                workflow.indexOf("- name: Publish complete release and trigger deployment"));
    needs(
        f,
        checkout,
        "exact checkout",
        "ref: ${{ github.event_name == 'push' && github.sha || 'main' }}");
    needs(
        f,
        stage,
        "release staging",
        "resume_staged_release != 'true'",
        "SOURCE_BRANCH: ${{ github.ref_name }}");
    if (stage.contains("RENDER_DEPLOY_HOOK_URL") || resume.contains("RENDER_DEPLOY_HOOK_URL"))
      f.add("Only final publication may receive deployment hook");
    needs(
        f,
        resume,
        "resume validation",
        "resume_staged_release == 'true'",
        "git fetch origin \"refs/heads/main:refs/remotes/origin/main\" --force",
        "git fetch origin \"refs/tags/${tag}:refs/tags/${tag}\"",
        "--release-commit \"$tag\" --main-commit origin/main",
        "--mode release",
        "--expected-version \"$RELEASE_VERSION\" --tag \"$tag\"",
        "--mode development",
        "--expected-version \"$NEXT_VERSION_INPUT\"",
        "if [[ \"$is_draft\" != \"true\" ]]; then");
    String fetch = "git fetch origin \"refs/tags/${tag}:refs/tags/${tag}\"",
        detach = "git checkout --detach \"$tag\"";
    needs(f, immutable, "immutable checkout", fetch, detach);
    if (immutable.indexOf(fetch) > immutable.indexOf(detach))
      f.add("Fetch immutable tag before checkout");
    needs(
        f,
        finalGate,
        "exact gate",
        "EXPECTED_MAIN_SHA: ${{ steps.final_main.outputs.sha }}",
        "run: bash \"$RUNNER_TEMP/verify-exact-release-gates.sh\"");
    needs(
        f,
        publish,
        "final publication",
        "gh release edit \"$tag\"",
        "--notes-file release_notes.md",
        "--draft=false",
        "--latest",
        "RENDER_DEPLOY_HOOK_URL");
    // Same immutable image policy used by the former adapter, without a second implementation.
    var output = new ByteArrayOutputStream();
    int code =
        ReleaseImageGate.run(
            root.resolve(".github/workflows/deploy-release.yml"),
            new PrintStream(output),
            new PrintStream(output));
    if (code != 0) f.add("Immutable OCI image verification contract failed: " + output);
  }

  static List<String> resourceDiagnosticFailures(String workflow) {
    var f = new ArrayList<String>();
    String[] steps = workflow.split("(?m)^      - ");
    List<String> names =
        List.of(
            "Prepare numeric release diagnostics",
            "Observe release resources",
            "Build and stage release, then prepare next development version",
            "Stop resource observation",
            "Finalize numeric release diagnostics",
            "Archive numeric release diagnostics");
    List<String> blocks = new ArrayList<>();
    int previous = -1;
    for (String name : names) {
      List<Integer> found = new ArrayList<>();
      for (int i = 1; i < steps.length; i++)
        if (steps[i].startsWith("name: " + name + "\n")) found.add(i);
      if (found.size() != 1) {
        f.add("Release resource diagnostics require exactly one step: " + name);
        return f;
      }
      int index = found.getFirst();
      if (index <= previous)
        f.add("Release resource diagnostics must surround only canonical build");
      previous = index;
      blocks.add(steps[index]);
    }
    for (int i : List.of(0, 1, 4, 5))
      if (!blocks.get(i).contains("        continue-on-error: true\n"))
        f.add("Diagnostic failure must not replace release result");
    for (int i : List.of(4, 5))
      if (!blocks.get(i).contains("        if: always()\n"))
        f.add("Numeric diagnostics must run on failure/dry run");
    needs(
        f,
        blocks.get(1),
        "native background sampler",
        "        background: true\n",
        "        id: release_resources\n");
    String cancel = blocks.get(3);
    if (!cancel.contains("        cancel: release_resources\n") || cancel.contains("        if:"))
      f.add("Resource cancel must be unconditional");
    String build = blocks.get(2);
    if (!build.contains("        run: \"$RUNNER_TEMP/release.sh\"\n")
        || List.of("continue-on-error:", "background:", "tee ", "trap ").stream()
            .anyMatch(build::contains)) f.add("Canonical release execution must remain direct");
    for (int i : List.of(0, 1, 4))
      needs(
          f,
          blocks.get(i),
          "bounded diagnostic command",
          "release-resources --mode " + (i == 0 ? "init" : i == 1 ? "sample" : "finalize"),
          "-Xms16m -Xmx64m");
    String archive = blocks.get(5),
        prefix =
            "${{ runner.temp }}/release-resources-${{ github.run_id }}-${{ github.run_attempt }}/";
    var paths =
        archive.lines().filter(l -> l.startsWith("            ")).map(String::strip).toList();
    if (!paths.equals(
        List.of(prefix + "identity.json", prefix + "metrics.jsonl", prefix + "manifest.json")))
      f.add("Release resource archive must contain only three fixed numeric files");
    needs(f, archive, "bounded diagnostic retention", "          retention-days: 7\n");
    return f;
  }

  static void hardeningAdditional(Path root, List<String> f) throws Exception {
    String delivery = read(root, ".github/workflows/delivery.yml"),
        small = read(root, "deploy/helm/taxonomy/values-small.yaml"),
        stage = read(root, ".github/scripts/stage-core-quality-reports.sh");
    if (delivery.contains("keep_files: true")) f.add("Reports must replace stale tree atomically");
    if (delivery.contains(
        "RENDER_DEPLOY_HOOK_URL is not configured; Render deployment is disabled."))
      f.add("Enabled deployment must fail closed on missing hook");
    String render = delivery.substring(Math.max(0, delivery.indexOf("  deploy-render:")));
    if (render.contains("if-no-files-found: warn"))
      f.add("Render delivery evidence must never be optional");
    for (String command : List.of("generate-quality-site", "verify-quality-publication"))
      if (stage.contains(command))
        f.add("Core staging must leave " + command + " to final evidence gate");
    if (small.contains("cpu: \"2\"")) f.add("Small profile must not use universal two-CPU limit");
    needs(
        f,
        read(root, ".mvn/javascript-junit-sources.txt"),
        "base-path owner",
        ".github/scripts/test-taxonomy-base-path.mjs");
    needs(
        f,
        read(
            root,
            "taxonomy-tooling/src/test/java/com/taxonomy/tooling/CoreDeliveryContractsTest.java"),
        "Maven delivery guard ownership",
        "check-release-delivery-contract",
        "check-delivery-hardening");
  }

  static String read(Path root, String p) throws IOException {
    return Files.readString(root.resolve(p));
  }

  static void needs(List<String> f, String text, String label, String... needles) {
    for (String needle : needles)
      if (!text.contains(needle)) f.add(label + " is missing " + needle);
  }

  static String between(String text, String a, String b) {
    return text.substring(text.indexOf("- name: " + a), text.indexOf("- name: " + b));
  }
}
