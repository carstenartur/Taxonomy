package com.taxonomy.preferences.storage;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitPromptOverrideStoreTest {

    @Test
    void overlappingEditsMergeAgainstTheLatestRevision() {
        try (var repository = new PreferencesGitRepository();
             var other = new GitPromptOverrideStore(new PreferencesGitRepository(repository.getGitRepository()), new ObjectMapper())) {
            var interleaving = new AtomicBoolean(true);
            try (var first = new GitPromptOverrideStore(new PreferencesGitRepository(repository.getGitRepository()) {
                @Override
                public String commitIfUnchanged(String json, ObjectId previous, String author, String message) throws IOException {
                    if (interleaving.getAndSet(false)) other.save("BP", "A separate edit");
                    return super.commitIfUnchanged(json, previous, author, message);
                }
            }, new ObjectMapper())) {
                first.save("requirement-intake", "Reuse instructions\nwith a second line");
                assertThat(other.snapshot()).isEqualTo(Map.of("BP", "A separate edit",
                        "requirement-intake", "Reuse instructions\nwith a second line"));
                other.reset("BP");
                assertThat(first.snapshot()).containsOnlyKeys("requirement-intake");
            }
        }
    }

    @Test
    void failedWriteLeavesThePublishedOverrideIntact() {
        try (var repository = new PreferencesGitRepository();
             var viewed = new GitPromptOverrideStore(new PreferencesGitRepository(repository.getGitRepository()), new ObjectMapper())) {
            viewed.save("BP", "Saved");
            try (var failed = new GitPromptOverrideStore(new PreferencesGitRepository(repository.getGitRepository()) {
                @Override
                public String commitIfUnchanged(String json, ObjectId previous, String author, String message) throws IOException {
                    throw new IOException("Simulated storage failure");
                }
            }, new ObjectMapper())) {
                assertThatThrownBy(() -> failed.save("BP", "Uncommitted"))
                        .hasMessage("Prompt template storage is unavailable");
                assertThat(failed.snapshot()).containsEntry("BP", "Saved");
            }
        }
    }
}
