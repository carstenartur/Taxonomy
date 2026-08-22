package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTemplateGitRepositoryTest {

    private final OoxmlTemplatePackageCodec codec =
            new OoxmlTemplatePackageCodec();
    private InMemoryRepository git;
    private DocumentTemplateGitRepository repository;
    private Map<String, byte[]> parts;

    @BeforeEach
    void setUp() throws Exception {
        git = new InMemoryRepository(
                new DfsRepositoryDescription("document-templates-test"));
        repository = new DocumentTemplateGitRepository(git);
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            parts = codec.unpack(input).parts();
        }
    }

    @AfterEach
    void tearDown() {
        git.close();
    }

    @Test
    void unrelatedTemplateCommitDoesNotChangeRepresentationVersionOrEtag()
            throws Exception {
        TemplateSnapshot alpha = create("alpha", "Alpha");
        TemplateSnapshot beta = create("beta", "Beta");

        TemplateSnapshot currentAlpha = repository.readCurrent("alpha");
        List<TemplateDescriptor> listed = repository.list();
        TemplateDescriptor listedAlpha = listed.stream()
                .filter(item -> item.templateId().equals("alpha"))
                .findFirst()
                .orElseThrow();

        assertThat(currentAlpha.commitId()).isEqualTo(alpha.commitId());
        assertThat(currentAlpha.commitId()).isNotEqualTo(beta.commitId());
        assertThat(listedAlpha.headCommit()).isEqualTo(alpha.commitId());
    }

    @Test
    void replacementRebasesAcrossUnrelatedTemplateChangesUsingPerTemplateVersion()
            throws Exception {
        TemplateSnapshot alphaV1 = create("alpha", "Alpha");
        TemplateSnapshot beta = create("beta", "Beta");

        TemplateSnapshot alphaV2 = repository.commit(
                manifest("alpha", "Alpha revised"),
                parts,
                alphaV1.commitId(),
                "editor",
                "Revise alpha");

        assertThat(alphaV2.commitId()).isNotEqualTo(alphaV1.commitId());
        assertThat(repository.readCurrent("beta").commitId())
                .isEqualTo(beta.commitId());
        assertThat(repository.history("alpha")).hasSize(2);
        assertThat(repository.history("beta")).hasSize(1);
    }

    @Test
    void createOnlyPreconditionCannotOverwriteAnExistingTemplate() throws Exception {
        TemplateSnapshot first = create("alpha", "Alpha");

        assertThatThrownBy(() -> repository.commit(
                manifest("alpha", "Competing alpha"),
                parts,
                null,
                "other-editor",
                "Competing first upload"))
                .isInstanceOf(TemplateConflictException.class)
                .hasMessageContaining(first.commitId());
        assertThat(repository.readCurrent("alpha").manifest().displayName())
                .isEqualTo("Alpha");
    }

    @Test
    void stalePerTemplateVersionIsRejectedAfterTheSameTemplateChanges()
            throws Exception {
        TemplateSnapshot first = create("alpha", "Alpha");
        TemplateSnapshot second = repository.commit(
                manifest("alpha", "Alpha v2"),
                parts,
                first.commitId(),
                "editor",
                "Second version");

        assertThatThrownBy(() -> repository.commit(
                manifest("alpha", "Stale replacement"),
                parts,
                first.commitId(),
                "stale-editor",
                "Stale version"))
                .isInstanceOf(TemplateConflictException.class)
                .hasMessageContaining(second.commitId());
    }

    private TemplateSnapshot create(String id, String displayName) throws Exception {
        return repository.commit(
                manifest(id, displayName),
                parts,
                null,
                "creator",
                "Create " + id);
    }

    private TemplateManifest manifest(String id, String displayName) {
        return new TemplateManifest(
                1,
                id,
                displayName,
                id + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                Instant.parse("2026-08-22T16:00:00Z").toString(),
                "tester",
                parts.values().stream().mapToLong(value -> value.length).sum(),
                parts.size(),
                OoxmlTemplatePackageCodec.packageSha256(parts));
    }
}
