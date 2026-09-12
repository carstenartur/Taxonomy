package com.taxonomy.dsl.storage;

import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceDslReadPort;
import com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitMetadata;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.taxonomy.workspace.service.WorkspaceDslReadPort.CommitRelationship.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DslWorkspaceHistoryReadTest {
    private static final RepositoryContext CONTEXT =
            RepositoryContext.workspace("repo-a", "workspace-a", "review", "alice");

    @Test
    void metadataKeepsTheExactHistoricalAuthorMessageAndParents() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(CONTEXT);
            String first = git.commitDsl("review", "# first", "alice", "First");
            String message = "Reviewed change\n\nCausation-Id: decision-17\nRationale: Gründe 日本語";
            String second = git.commitDsl("review", "# second", "alice", message);
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            String newest = git.commitDsl("review", "# newest", "bob", "Unrelated new version");

            var evidence = read.commitMetadata(second.toUpperCase(Locale.ROOT));

            assertThat(evidence.summary()).isEqualTo("Reviewed change");
            assertThat(evidence.author()).isEqualTo("alice");
            assertThat(evidence.message()).isEqualTo(message);
            assertThat(evidence.parents()).containsExactly(first);
            assertThat(read.commitMetadata(first).parents()).isEmpty();
            assertThat(git.getHeadCommit("review")).isEqualTo(newest);
            assertThat(git.getCommitCount("review")).isEqualTo(3);
        }
    }

    @Test
    void relationshipsPreserveOrderDuplicatesAndDirectionWithoutTreatingUnavailableAsProof() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(CONTEXT);
            String first = git.commitDsl("review", "# first", "alice", "First");
            String second = git.commitDsl("review", "# second", "alice", "Second");
            String unrelated = git.commitDsl("other", "# unrelated", "bob", "Other root");
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);

            var relationships = read.relationshipsTo(second, Arrays.asList(second, first,
                    unrelated, "a".repeat(40), "invalid", null, first.toUpperCase(Locale.ROOT), first));

            assertThat(relationships).containsExactly(SAME, ANCESTOR, UNRELATED,
                    UNAVAILABLE, UNAVAILABLE, UNAVAILABLE, ANCESTOR, ANCESTOR);
            assertThat(read.relationshipsTo(first, List.of(second))).containsExactly(UNRELATED);
            assertThat(read.relationshipsTo(second, List.of())).isEmpty();
            assertThatThrownBy(() -> relationships.add(SAME)).isInstanceOf(UnsupportedOperationException.class);
            assertThat(git.getHeadCommit("review")).isEqualTo(second);
            assertThat(git.getCommitCount("review")).isEqualTo(2);
        }
    }

    @Test
    void missingOrMalformedDescendantFailsEvenWithNoCandidates() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            assertThatThrownBy(() -> read.relationshipsTo("a".repeat(40), List.of()))
                    .isInstanceOf(IOException.class);
            assertThatThrownBy(() -> read.relationshipsTo("review", List.of()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> read.commitMetadata("a".repeat(40))).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> read.commitMetadata("review")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void metadataAndAncestryStayInTheBoundWorkspace() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(CONTEXT);
            String own = git.commitDsl("review", "# own", "alice", "Own");
            var other = RepositoryContext.workspace("repo-a", "workspace-b", "review", "bob");
            String foreign = factory.resolveRepository(other).commitDsl("review", "# foreign", "bob", "Foreign");
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);

            assertThatThrownBy(() -> read.commitMetadata(foreign)).isInstanceOf(IOException.class);
            assertThat(read.relationshipsTo(own, List.of(foreign))).containsExactly(UNAVAILABLE);
            assertThatThrownBy(() -> read.relationshipsTo(foreign, List.of(own))).isInstanceOf(IOException.class);
            assertThat(git.getHeadCommit("review")).isEqualTo(own);
        }
    }

    @Test
    void mergeEvidenceRetainsEveryParentAndProvesBothHistories() throws Exception {
        try (var factory = new DslGitRepositoryFactory(null)) {
            var git = factory.resolveRepository(CONTEXT);
            String first = git.commitDsl("review", "# first", "alice", "First root");
            String other = git.commitDsl("other", "# other", "bob", "Other root");
            String merge;
            try (var inserter = git.getGitRepository().newObjectInserter()) {
                var commit = new CommitBuilder();
                commit.setTreeId(inserter.insert(new TreeFormatter()));
                commit.setParentIds(ObjectId.fromString(first), ObjectId.fromString(other));
                var actor = new PersonIdent("alice", "alice@taxonomy.local");
                commit.setAuthor(actor);
                commit.setCommitter(actor);
                commit.setMessage("Merge evidence");
                merge = inserter.insert(commit).name();
                inserter.flush();
            }
            var read = new DslWorkspaceReadAdapter(factory).openRead(CONTEXT);
            assertThat(read.commitMetadata(merge).parents()).containsExactly(first, other);
            assertThat(read.relationshipsTo(merge, List.of(first, other, merge)))
                    .containsExactly(ANCESTOR, ANCESTOR, SAME);
            assertThat(git.getHeadCommit("review")).isEqualTo(first);
        }
    }

    @Test
    void detachedMetadataDefensivelyCopiesItsParents() {
        var parents = new ArrayList<>(List.of("a".repeat(40)));
        var metadata = new CommitMetadata("Summary", "alice", "Summary\n\nBody", parents);
        parents.clear();
        assertThat(metadata.parents()).containsExactly("a".repeat(40));
        assertThatThrownBy(() -> metadata.parents().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void canonicalObjectIdsRejectReferencesAbbreviationsWhitespaceAndNonHex() {
        String mixed = "0123456789aBcDeF0123456789aBcDeF01234567";
        assertThat(WorkspaceDslReadPort.normalizeCommitId(mixed)).isEqualTo(mixed.toLowerCase(Locale.ROOT));
        assertThat(WorkspaceDslReadPort.normalizeCommitId("0".repeat(40))).isEqualTo("0".repeat(40));
        for (String invalid : Arrays.asList(null, "", "main", "abc123", " " + mixed, mixed + " ",
                "z".repeat(40), "０".repeat(40))) {
            assertThatThrownBy(() -> WorkspaceDslReadPort.normalizeCommitId(invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
