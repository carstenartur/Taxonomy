package com.taxonomy.workspace.storage;

import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DefaultCommitAdoptionTest {
    @Test
    void independentAndDependentCommitsKeepTheirOrderAndNeverOverwriteDivergence() throws Exception {
        var git = new DslGitRepositoryFactory(null).getCentralRepository("r");
        String initial = model("shared", "shared", "shared");
        var base = git.commitDsl("main", initial, "a", "base");
        git.createBranch("draft", "main");
        var local = git.commitDsl("draft", model("local", "shared", "shared"), "a", "local");
        var first = git.commitDsl("main", model("shared", "first", "shared"), "a", "independent");
        var second = git.commitDsl("main", model("shared", "second", "shared"), "a", "dependent");
        var conflict = git.commitDsl("main", model("other", "second", "shared"), "a", "conflicting A");
        assertThat(DefaultCommitAdoption.next(git, base, conflict)).isEqualTo(first);
        var step = git.prepareDefaultCommit("r", "main", first, "draft", local, "a");
        assertThat(step.result()).isEqualTo("PREPARED");
        assertThat(git.getHeadCommit("draft")).isEqualTo(local);
        git.fastForwardIfHeadMatches("draft", local, step.candidate(), "a", "adopt");
        assertThat(git.getDslAtHead("draft")).isEqualTo(model("local", "first", "shared"));
        assertThat(git.prepareDefaultCommit("r", "main", first, "draft", step.candidate(), "a").result())
                .isEqualTo("ALREADY_CONTAINED");
        var dependent = git.prepareDefaultCommit("r", "main", second, "draft", step.candidate(), "a");
        var racing = git.commitDsl("draft", model("local", "first", "concurrent"), "b", "concurrent");
        assertThatThrownBy(
                () -> git.fastForwardIfHeadMatches("draft", step.candidate(), dependent.candidate(), "a", "adopt"))
                .isInstanceOf(BranchHeadConflictException.class);
        var retry = git.prepareDefaultCommit("r", "main", second, "draft", racing, "a");
        git.fastForwardIfHeadMatches("draft", racing, retry.candidate(), "a", "adopt");
        assertThat(git.prepareDefaultCommit("r", "main", conflict, "draft", retry.candidate(), "a").result())
                .isEqualTo("CONFLICT");
        assertThat(git.getDslAtHead("draft")).isEqualTo(model("local", "second", "concurrent"));
        assertThat(git.getHeadCommit("main")).isEqualTo(conflict);
    }

    @Test
    void symbolicAliasesCannotRedirectAWallClockWorkerIntoTheDefaultBranch() throws Exception {
        var git = new DslGitRepositoryFactory(null).getCentralRepository("symbolic");
        var original = git.commitDsl("main", model("a", "b", "c"), "a", "original");
        git.createBranch("candidate", "main");
        var candidate = git.commitDsl("candidate", model("a", "different", "c"), "a", "candidate");
        git.getGitRepository().updateRef("refs/heads/indirect").link("refs/heads/main");
        assertThatThrownBy(() -> git.publishDefaultCommit("indirect", original, candidate, "a", "adopt"))
                .isInstanceOf(java.io.IOException.class);
        assertThat(git.getHeadCommit("main")).isEqualTo(original);
        assertThat(git.readFreshRef("refs/heads/indirect").isSymbolic()).isTrue();
    }

    private static String model(String first, String second, String third) {
        return "element A type System {\n  title: \"" + first + "\";\n}\n"
                + "element B type System {\n  title: \"" + second + "\";\n}\n"
                + "element C type System {\n  title: \"" + third + "\";\n}\n";
    }
}
