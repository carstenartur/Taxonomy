package com.taxonomy.workspace.storage;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class GitSelectedTagTest {
    @Test
    void anAbsentTagIsDistinctFromAnInvalidName() throws Exception {
        try (var source = new DslGitRepository()) {
            assertThat(GitSelectedTag.read(source, "review/not-yet-published")).isNull();
            assertThatIllegalArgumentException().isThrownBy(() -> GitSelectedTag.read(source, null));
            assertThatIllegalArgumentException().isThrownBy(() -> GitSelectedTag.read(source, "review name"));
        }
    }

    @Test
    void aValidGitTagPointingToATreeIsNotACommitSelection() throws Exception {
        try (var source = new DslGitRepository();
             var walk = new RevWalk(source.getGitRepository())) {
            String revision = source.commitDsl("main", "architecture", "owner", "Review candidate");
            var tree = walk.parseCommit(ObjectId.fromString(revision)).getTree();
            var tag = Git.wrap(source.getGitRepository()).tag().setName("review/tree")
                    .setObjectId(tree).setAnnotated(false).call();

            assertThat(GitSelectedTag.objectAt(source, "review/tree")).isEqualTo(tag.getObjectId().name());
            assertThatIllegalArgumentException().isThrownBy(() -> GitSelectedTag.read(source, "review/tree"));
            assertThat(GitSelectedTag.objectAt(source, "review/tree")).isEqualTo(tree.name());
        }
    }

    @Test
    void selectingANestedAnnotationTransfersBothObjectsAndOnlyTheNamedRef() throws Exception {
        try (var source = new DslGitRepository(); var target = new DslGitRepository();
             var walk = new RevWalk(source.getGitRepository())) {
            String revision = source.commitDsl("main", "architecture", "owner", "Reviewed snapshot");
            var sourceGit = Git.wrap(source.getGitRepository());
            var reviewer = new PersonIdent("Reviewer", "reviewer@example.test");
            var inner = sourceGit.tag().setName("review/first").setTagger(reviewer).setMessage("First review")
                    .setObjectId(walk.parseCommit(ObjectId.fromString(revision))).call();
            var outer = sourceGit.tag().setName("review/second").setTagger(reviewer).setMessage("Second review")
                    .setObjectId(walk.parseTag(inner.getObjectId())).call();

            GitHistoryTransfer.copyCommitClosure(source, target, revision, List.of());
            var selection = GitSelectedTag.read(source, "review/second");
            assertThat(selection.commit()).isEqualTo(revision);
            selection.publish(target, "reviewer");

            assertThat(GitSelectedTag.objectAt(target, "review/second")).isEqualTo(outer.getObjectId().name());
            assertThat(GitSelectedTag.objectAt(target, "review/first")).isNull();
            assertThat(target.getHeadCommit("main")).isNull();
            for (var objectId : List.of(inner.getObjectId(), outer.getObjectId())) {
                assertThat(target.getGitRepository().open(objectId, Constants.OBJ_TAG).getBytes())
                        .isEqualTo(source.getGitRepository().open(objectId, Constants.OBJ_TAG).getBytes());
            }
        }
    }
}
