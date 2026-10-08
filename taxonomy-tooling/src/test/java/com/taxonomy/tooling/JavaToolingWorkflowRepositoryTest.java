package com.taxonomy.tooling;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JavaToolingWorkflowRepositoryTest {

    @Test
    void everyProductiveWorkflowBuildsAndValidatesTheToolingJar()
            throws Exception {
        Path root = findRepositoryRoot();

        assertWorkflowBuild(
                root.resolve(".github/workflows/ci-cd.yml"),
                "Java release tooling jar was not produced",
                false);
        assertWorkflowBuild(
                root.resolve(".github/workflows/deploy-release.yml"),
                "Java release tooling jar was not produced",
                true);
        assertWorkflowBuild(
                root.resolve(".github/workflows/protected-release-main-advance.yml"),
                "Java release tooling jar was not produced",
                true);
        assertWorkflowBuild(
                root.resolve(".github/workflows/prepare-development-version.yml"),
                "Java version tooling jar was not produced",
                true);

        String releaseScript = read(root.resolve(".github/scripts/release.sh"));
        assertThat(releaseScript)
                .contains("${TOOLING_JAR:?TOOLING_JAR is required}")
                .contains("! -name 'taxonomy-tooling-*.jar'");
    }

    @Test
    void workflowPermissionsAndOutputsMatchTheirConsumers() throws Exception {
        Path root = findRepositoryRoot();
        String protectedAdvance = read(root.resolve(
                ".github/workflows/protected-release-main-advance.yml"));
        String releaseWorkflow = read(root.resolve(
                ".github/workflows/deploy-release.yml"));

        assertThat(protectedAdvance).contains(
                "permissions:\n"
                        + "  actions: write\n"
                        + "  checks: read\n"
                        + "  statuses: read\n"
                        + "  contents: write\n"
                        + "  pull-requests: write");
        assertThat(releaseWorkflow)
                .doesNotContain("id: release_tooling")
                .doesNotContain("jar=$RUNNER_TEMP/taxonomy-tooling.jar");
    }

    @Test
    void releaseScriptRevalidatesTheCommittedReleaseSourceAsClean()
            throws Exception {
        Path root = findRepositoryRoot();
        String script = read(root.resolve(".github/scripts/release.sh"));

        String dirtyTransition = "run_release_plan_check release false";
        String releaseCommit =
                "git commit -m \"Release version $RELEASE_VERSION\"";
        String cleanImmutableSource = "run_release_plan_check release true";
        String canonicalVerification =
                "run_maven_release_check release release-check,ci clean verify";

        assertThat(script)
                .contains(dirtyTransition)
                .contains(releaseCommit)
                .contains(cleanImmutableSource)
                .contains(canonicalVerification);
        assertThat(script.indexOf(dirtyTransition))
                .isLessThan(script.indexOf(releaseCommit));
        assertThat(script.indexOf(releaseCommit))
                .isLessThan(script.indexOf(cleanImmutableSource));
        assertThat(script.indexOf(cleanImmutableSource))
                .isLessThan(script.indexOf(canonicalVerification));
    }

    @Test
    void stagedResumeValidatesBothImmutableReleaseAndAdvancedMainPlans()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = read(root.resolve(
                ".github/workflows/deploy-release.yml"));

        int resumeStart = workflow.indexOf(
                "- name: Validate staged release for resume");
        int resumeEnd = workflow.indexOf(
                "- name: Record exact final main snapshot");
        assertThat(resumeStart).isGreaterThanOrEqualTo(0);
        assertThat(resumeEnd).isGreaterThan(resumeStart);
        String resume = workflow.substring(resumeStart, resumeEnd);

        String releaseCheckout = "git checkout --detach \"$tag\"";
        String releasePlan = "--state release";
        String advancedCheckout = "git checkout --detach origin/main";
        String advancedPlan = "--state advanced";

        assertThat(resume)
                .contains("read-pom-version")
                .contains("check-release-plan")
                .contains(releasePlan)
                .contains(advancedPlan)
                .contains("--require-clean true");
        assertThat(count(resume, "check-release-plan")).isEqualTo(2);
        assertThat(resume.indexOf(releaseCheckout))
                .isLessThan(resume.indexOf(releasePlan));
        assertThat(resume.indexOf(releasePlan))
                .isLessThan(resume.indexOf(advancedCheckout));
        assertThat(resume.indexOf(advancedCheckout))
                .isLessThan(resume.indexOf(advancedPlan));
    }

    @Test
    void protectedAdvanceValidatesTheExactAdvancedPlanBeforeOpeningAPr()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = read(root.resolve(
                ".github/workflows/protected-release-main-advance.yml"));

        int handoffStart = workflow.indexOf("- name: Validate immutable handoff");
        int createPrStart = workflow.indexOf(
                "- name: Create protected-main pull request");
        assertThat(handoffStart).isGreaterThanOrEqualTo(0);
        assertThat(createPrStart).isGreaterThan(handoffStart);
        String handoff = workflow.substring(handoffStart, createPrStart);

        String checkout = "git checkout --detach \"$EXPECTED_SHA\"";
        String versionState = "check-version-state";
        String pomVersion = "read-pom-version";
        String releasePlan = "check-release-plan";

        assertThat(handoff)
                .contains(checkout)
                .contains(versionState)
                .contains(pomVersion)
                .contains(releasePlan)
                .contains("--state advanced")
                .contains("--require-clean true");
        assertThat(handoff.indexOf(checkout))
                .isLessThan(handoff.indexOf(versionState));
        assertThat(handoff.indexOf(versionState))
                .isLessThan(handoff.indexOf(pomVersion));
        assertThat(handoff.indexOf(pomVersion))
                .isLessThan(handoff.indexOf(releasePlan));
    }

    @Test
    void manualDevelopmentAdvanceValidatesSourceDirtyAndCommittedPlans()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = read(root.resolve(
                ".github/workflows/prepare-development-version.yml"));

        int validationStart = workflow.indexOf(
                "- name: Validate and prepare exact development version");
        int transitionStart = workflow.indexOf(
                "- name: Create coherent version transition");
        int openPrStart = workflow.indexOf(
                "- name: Open protected main pull request");
        assertThat(validationStart).isGreaterThanOrEqualTo(0);
        assertThat(transitionStart).isGreaterThan(validationStart);
        assertThat(openPrStart).isGreaterThan(transitionStart);

        String validation = workflow.substring(validationStart, transitionStart);
        String transition = workflow.substring(transitionStart, openPrStart);
        assertThat(validation)
                .contains("compare-versions")
                .contains("check-release-plan")
                .contains("--state development")
                .contains("--require-clean true");

        String dirtyPlan = "--require-clean false";
        String commit = "git commit -m \"Prepare development version $NEXT_VERSION\"";
        String cleanPlan = "--require-clean true";
        String push = "git push origin \"$BRANCH\"";
        assertThat(transition)
                .contains("--state advanced")
                .contains(dirtyPlan)
                .contains(commit)
                .contains(cleanPlan)
                .contains(push);
        assertThat(count(transition, "check-release-plan")).isEqualTo(2);
        assertThat(count(transition, "--state advanced")).isEqualTo(2);
        assertThat(transition.indexOf(dirtyPlan))
                .isLessThan(transition.indexOf(commit));
        assertThat(transition.indexOf(commit))
                .isLessThan(transition.indexOf(cleanPlan));
        assertThat(transition.indexOf(cleanPlan))
                .isLessThan(transition.indexOf(push));
    }

    @Test
    void protectedMainAdvanceWaitsForEveryRequiredCheckOnTheExactHead()
            throws Exception {
        Path root = findRepositoryRoot();
        String workflow = read(root.resolve(
                ".github/workflows/protected-release-main-advance.yml"));

        String helper = read(root.resolve(".github/scripts/check-release-pr-gates.sh"));
        String admission = "- name: Check workflow admission before expensive verification";
        String canonical = "- name: Run canonical verification on exact snapshot";
        String completeGates =
                "- name: Wait for complete protected-main pull-request gates";
        String registration = "bash \"$RUNNER_TEMP/check-release-pr-gates.sh\" preflight";
        String watch =
                "gh pr checks \"$PR_NUMBER\" --required --watch --fail-fast";
        String verify = "bash \"$RUNNER_TEMP/check-release-pr-gates.sh\" verify";
        String merge = "- name: Merge through protected main";
        String copy = "cp .github/scripts/check-release-pr-gates.sh "
                + "\"$RUNNER_TEMP/check-release-pr-gates.sh\"";
        String checkout = "git checkout --detach \"$EXPECTED_SHA\"";

        assertThat(workflow)
                .contains(copy)
                .contains(checkout)
                .contains(admission)
                .contains(canonical)
                .contains(completeGates)
                .contains(registration)
                .contains(watch)
                .contains(verify)
                .contains(merge)
                .contains("gh pr merge \"$PR_NUMBER\" --rebase --match-head-commit \"$EXPECTED_SHA\"");
        assertThat(workflow.indexOf(copy)).isLessThan(workflow.indexOf(checkout));
        assertThat(workflow.indexOf(admission)).isLessThan(workflow.indexOf(canonical));
        assertThat(workflow.indexOf(registration)).isLessThan(workflow.indexOf(canonical));
        assertThat(workflow.indexOf(canonical)).isLessThan(workflow.indexOf(completeGates));
        assertThat(workflow.indexOf(completeGates)).isLessThan(workflow.indexOf(merge));

        // The registration loop moved into the preserved helper. Verify both
        // call sites and the fail-closed contract there, not obsolete inline code.
        String gates = workflow.substring(workflow.indexOf(completeGates), workflow.indexOf(merge));
        String before = "head_before=$(gh pr view \"$PR_NUMBER\" --json headRefOid";
        String after = "head_after=$(gh pr view \"$PR_NUMBER\" --json headRefOid";
        assertThat(gates)
                .contains(before)
                .contains(registration)
                .contains(watch)
                .contains(verify)
                .contains(after)
                .contains("test \"$head_before\" = \"$EXPECTED_SHA\"")
                .contains("test \"$head_after\" = \"$EXPECTED_SHA\"");
        assertThat(gates.indexOf(before)).isLessThan(gates.indexOf(registration));
        assertThat(gates.indexOf(registration)).isLessThan(gates.indexOf(watch));
        assertThat(gates.indexOf(watch)).isLessThan(gates.indexOf(verify));
        assertThat(gates.indexOf(verify)).isLessThan(gates.indexOf(after));

        assertThat(helper)
                .contains("--required --json name,bucket,state,link")
                .contains("status=$?")
                .contains("\"$status\" == 8")
                .contains("\"$(jq 'length' <<< \"$latest\")\" -gt 0")
                .contains("\"$(jq 'length' <<< \"$checks\")\" -gt 0")
                .contains("Workflow runs or required checks have not registered")
                .contains(".conclusion == \"action_required\"")
                .contains("Approve workflows to run")
                .contains(".state == \"OPEN\" and .baseRefName == \"main\" and .headRefOid == $sha")
                .contains("all(.[]; .bucket == \"pass\")")
                .contains("required checks are still pending")
                .doesNotContain("2>/dev/null");
    }

    private static int count(String text, String needle) {
        return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    private static void assertWorkflowBuild(
            Path workflow,
            String diagnostic,
            boolean requiresPreservedCopy) throws Exception {
        String text = read(workflow);
        assertThat(text)
                .contains("./mvnw -B -pl taxonomy-tooling -am package -DskipTests")
                .contains("-name 'taxonomy-tooling-*.jar'")
                .contains("if [[ -z \"$tooling_jar\" || ! -f \"$tooling_jar\" ]]")
                .contains("::error::" + diagnostic);
        if (requiresPreservedCopy) {
            assertThat(text).contains(
                    "cp \"$tooling_jar\" \"$RUNNER_TEMP/taxonomy-tooling.jar\"");
        }
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
                            "taxonomy-tooling/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate Taxonomy repository root");
    }
}
