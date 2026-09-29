package com.taxonomy.tooling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReleaseLinearHistoryTest {
    @TempDir
    Path repository;
    String base;
    String release;
    String next;

    @BeforeEach
    void prepareTwoCommitRelease() throws Exception {
        git("init", "-b", "main");
        git("config", "user.name", "Release Test");
        git("config", "user.email", "release@taxonomy.local");
        base = commitVersion("1.4.0-SNAPSHOT");
        git("checkout", "-b", "release-temp");
        release = commitVersion("1.4.0");
        git("tag", "v1.4.0");
        next = commitVersion("1.4.1-SNAPSHOT");
        git("checkout", "main");
        // GitHub's rebase merge rewrites committer metadata even without a
        // conflicting base. Real commit-tree objects reproduce that boundary.
        String mappedRelease = copyCommit(release, base);
        String mappedNext = copyCommit(next, mappedRelease);
        git("reset", "--hard", mappedNext);
        assertThat(mappedRelease).isNotEqualTo(release);
        assertThat(mappedNext).isNotEqualTo(next);
    }

    @Test
    void acceptsRewrittenIdsOnlyWhenBothTreesAndTheBaseMatch() {
        assertHistory(true, 0, "Release history verified");
    }

    @Test
    void resumesAnImmutableTagAfterItsExactReleaseTreeWasRebased() {
        ReleaseParametersResolver.validateStagedReleaseAncestry(repository,
                new ReleaseParametersResolver.Parameters(
                        "1.4.0", "1.4.1-SNAPSHOT", "false", "false", "true"),
                "1.4.1-SNAPSHOT");
        assertHistory(false, 0, "Release history verified");
    }

    @Test
    void rejectsChangedSnapshotContents() throws Exception {
        Files.writeString(repository.resolve("unexpected.txt"), "not verified");
        git("add", ".");
        git("commit", "--amend", "--no-edit");
        assertHistory(true, 1, "snapshot tree");
    }

    @Test
    void rejectsChangedIntermediateReleaseEvenIfFinalTreeMatches() throws Exception {
        git("checkout", "--detach", "HEAD^");
        Files.writeString(repository.resolve("unexpected.txt"), "not released");
        git("add", ".");
        git("commit", "--amend", "--no-edit");
        String modifiedRelease = git("rev-parse", "HEAD");
        git("reset", "--hard", copyCommit(next, modifiedRelease));
        assertHistory(true, 1, "release tree");
        assertHistory(false, 1, "release tree");
    }

    @Test
    void rejectsAdditionalCommitEvenIfItHasNoContentChanges() throws Exception {
        git("commit", "--allow-empty", "-m", "Concurrent main change");
        assertHistory(true, 1, "two single-parent commits");
    }

    @Test
    void rejectsSameTreesOnAnotherBase() throws Exception {
        String differentBase = copyCommit(base, base);
        String mappedRelease = copyCommit(release, differentBase);
        git("reset", "--hard", copyCommit(next, mappedRelease));
        assertHistory(true, 1, "two single-parent commits");
        assertHistory(false, 1, "release tree");
    }

    @Test
    void rejectsSquashWhichLosesTheReleaseSnapshot() throws Exception {
        git("reset", "--hard", copyCommit(next, base));
        assertHistory(true, 1, "two single-parent commits");
        assertHistory(false, 1, "release tree");
        assertThatThrownBy(() -> ReleaseParametersResolver.validateStagedReleaseAncestry(
                repository, new ReleaseParametersResolver.Parameters(
                        "1.4.0", "1.4.1-SNAPSHOT", "false", "false", "true"),
                "1.4.1-SNAPSHOT")).hasMessageContaining("repair release ancestry");
    }

    @Test
    void stillAcceptsAnUnrewrittenLinearHandoff() throws Exception {
        git("reset", "--hard", next);
        assertHistory(true, 0, "Release history verified");
        assertHistory(false, 0, "Release history verified");
    }

    @Test
    void rejectsMergeCommitsInTheNewHandoff() throws Exception {
        String merged = git("commit-tree", git("rev-parse", next + "^{tree}"),
                "-p", base, "-p", next, "-m", "Nonlinear merge");
        git("reset", "--hard", merged);
        assertHistory(true, 1, "single-parent");
    }

    @Test
    void rejectsAnUnexpectedStagingHead() throws Exception {
        next = release;
        assertHistory(true, 1, "two single-parent commits");
    }

    @Test
    void allowsLaterDevelopmentWhileResumingTheSameRelease() throws Exception {
        Files.writeString(repository.resolve("later.txt"), "next development");
        git("add", ".");
        git("commit", "-m", "Later development");
        assertHistory(false, 0, "Release history verified");
    }

    @Test
    void failsClosedForMissingGitObjects() throws Exception {
        git("tag", "-d", "v1.4.0");
        assertHistory(false, 1, "failed");
    }

    @Test
    void rejectsIncompleteHandoffArguments() {
        var output = new ByteArrayOutputStream();
        int exit = TaxonomyTooling.run(new String[]{"check-release-history",
                "--release-commit", release, "--main-commit", "HEAD",
                "--expected-next-commit", next}, repository,
                new PrintStream(output), new PrintStream(output));
        assertThat(exit).isEqualTo(1);
        assertThat(output.toString()).contains("expected-base-commit");
    }

    private void assertHistory(boolean handoff, int exit, String message) {
        List<String> arguments = new ArrayList<>(List.of("check-release-history",
                "--release-commit", "v1.4.0", "--main-commit", "HEAD"));
        if (handoff) {
            arguments.addAll(List.of("--expected-next-commit", next,
                    "--expected-base-commit", base));
        }
        var output = new ByteArrayOutputStream();
        int actual = TaxonomyTooling.run(arguments.toArray(String[]::new),
                repository, new PrintStream(output), new PrintStream(output));
        assertThat(actual).as(output.toString()).isEqualTo(exit);
        assertThat(output.toString()).contains(message);
    }

    private String commitVersion(String version) throws Exception {
        Files.writeString(repository.resolve("pom.xml"),
                "<project><version>" + version + "</version></project>\n");
        git("add", ".");
        git("commit", "-m", "Version " + version);
        return git("rev-parse", "HEAD");
    }

    private String copyCommit(String source, String parent) throws Exception {
        return git("-c", "user.name=GitHub Rebase", "commit-tree",
                git("rev-parse", source + "^{tree}"), "-p", parent,
                "-m", "Rebased " + source);
    }

    private String git(String... arguments) throws Exception {
        return TestGit.run(repository, arguments).strip();
    }
}
