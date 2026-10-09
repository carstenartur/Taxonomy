package com.taxonomy.templates;

import com.taxonomy.templates.api.TemplateManifest;
import com.taxonomy.templates.api.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.AbbreviatedObjectId;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Counts actual Git object reads, while retaining real storage, validation and ZIP creation. */
class DocumentTemplateRepositoryReadCostTest {
    private CountingRepository git;
    private DocumentTemplateGitRepository writer;
    private Map<String, byte[]> parts;
    private final Map<String, String> versions = new LinkedHashMap<>();

    @BeforeEach
    void createIndependentTemplateHistory() throws Exception {
        git = new CountingRepository();
        writer = new DocumentTemplateGitRepository(git);
        try (var input = getClass().getResourceAsStream(
                "/" + TemplateTestFixture.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            parts = new OoxmlTemplatePackageCodec().unpack(input).parts();
        }
        for (int index = 0; index < 12; index++) {
            String id = "template-" + index;
            versions.put(id, create(writer, id, "Template " + index).commitId());
        }
    }

    @AfterEach
    void closeRepository() {
        git.close();
    }

    @Test
    void coldListReadsEachHistoryCommitAtMostOnceBeyondTheManifestRead() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        git.resetCounts();

        var listed = reader.list();

        assertThat(listed).hasSize(12);
        listed.forEach(template -> assertThat(template.headCommit())
                .isEqualTo(versions.get(template.templateId())));
        System.out.println("Template cold list object opens: " + git.counts());
        assertThat(git.commitOpens).as("one history traversal for twelve templates")
                .isLessThanOrEqualTo(13);
    }

    @Test
    void unchangedListDoesNotRevisitHistory() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        var first = reader.list();
        git.resetCounts();

        assertThat(reader.list()).isEqualTo(first);

        System.out.println("Template warm list object opens: " + git.counts());
        assertThat(git.commitOpens).as("only the current manifest tree needs its commit")
                .isLessThanOrEqualTo(1);
    }

    @Test
    void repeatedCurrentReadDoesNotRevisitUnrelatedTemplateHistory() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        reader.readCurrent("template-0");
        git.resetCounts();

        assertThat(reader.readCurrent("template-0").commitId())
                .isEqualTo(versions.get("template-0"));

        System.out.println("Template warm read object opens: " + git.counts());
        assertThat(git.commitOpens).as("resolve and load the immutable snapshot only")
                .isLessThanOrEqualTo(2);
    }

    @Test
    void anotherHandleAdvancingHeadRefreshesOnlyTheChangedTemplateVersion() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        reader.list();
        reader.readCurrent("template-0");
        var replacement = writer.commit(manifest("template-0", "Revised template"),
                parts, versions.get("template-0"), "editor", "Revise one template");

        assertThat(reader.readCurrent("template-0").commitId()).isEqualTo(replacement.commitId());
        assertThat(reader.readCurrent("template-1").commitId()).isEqualTo(versions.get("template-1"));
        assertThat(reader.list()).filteredOn(template -> template.templateId().equals("template-0"))
                .singleElement().satisfies(template -> {
                    assertThat(template.headCommit()).isEqualTo(replacement.commitId());
                    assertThat(template.displayName()).isEqualTo("Revised template");
                });
        assertThatThrownBy(() -> reader.commit(manifest("template-0", "Stale"), parts,
                versions.get("template-0"), "stale", "Stale replacement"))
                .isInstanceOf(com.taxonomy.templates.api.TemplateConflictException.class);
    }

    @Test
    void repositoriesWithTheSameLogicalNameDoNotShareCurrentVersions() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        reader.list();
        try (var otherGit = new CountingRepository()) {
            var other = new DocumentTemplateGitRepository(otherGit);
            var otherVersion = create(other, "template-0", "Other repository");
            assertThat(other.readCurrent("template-0").manifest().displayName())
                    .isEqualTo("Other repository");
            assertThat(other.list()).singleElement()
                    .satisfies(template -> assertThat(template.headCommit()).isEqualTo(otherVersion.commitId()));
            assertThat(reader.readCurrent("template-0").commitId()).isEqualTo(versions.get("template-0"));
        }
    }

    @Test
    void missingVersionDoesNotSurviveACreationByAnotherHandle() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        assertThatThrownBy(() -> reader.readCurrent("new-template"))
                .isInstanceOf(TemplateNotFoundException.class);
        var created = create(writer, "new-template", "Created later");

        assertThat(reader.readCurrent("new-template").commitId()).isEqualTo(created.commitId());
    }

    @Test
    void listTreatsANonDirectoryTemplateRootInAnOlderCommitAsAbsent() throws Exception {
        ObjectId head = ObjectId.fromString(writer.headCommit());
        ObjectId restored;
        try (RevWalk walk = new RevWalk(git); ObjectInserter inserter = git.newObjectInserter()) {
            ObjectId originalTree = walk.parseCommit(head).getTree().copy();
            TreeFormatter fileRoot = new TreeFormatter();
            fileRoot.append("templates", FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, new byte[]{1}));
            ObjectId removed = insertCommit(inserter, inserter.insert(fileRoot), head);
            restored = insertCommit(inserter, originalTree, removed);
            inserter.flush();
        }
        RefUpdate ref = git.updateRef(Constants.R_HEADS + DocumentTemplateGitRepository.BRANCH);
        ref.setExpectedOldObjectId(head);
        ref.setNewObjectId(restored);
        assertThat(ref.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);

        var listed = new DocumentTemplateGitRepository(git).list();

        assertThat(listed).hasSize(12).allSatisfy(template ->
                assertThat(template.headCommit()).isEqualTo(restored.name()));
    }

    @Test
    void versionCacheEvictsOldLookupsAtItsEntryBudget() throws Exception {
        var reader = new DocumentTemplateGitRepository(git);
        reader.readCurrent("template-0");
        git.resetCounts();
        reader.readCurrent("template-0");
        assertThat(git.commitOpens).isLessThanOrEqualTo(2);

        // Missing-template lookups also consume bounded cache entries. The oldest
        // real template must be resolved again after 1,024 other identities.
        for (int index = 0; index < 1_024; index++) {
            String missing = "missing-" + index;
            assertThatThrownBy(() -> reader.readCurrent(missing))
                    .isInstanceOf(TemplateNotFoundException.class);
        }
        git.resetCounts();

        assertThat(reader.readCurrent("template-0").commitId())
                .isEqualTo(versions.get("template-0"));
        assertThat(git.commitOpens).as("evicted history is resolved again correctly")
                .isGreaterThan(2);
    }

    @Test
    void repeatedDepthOnePropfindReusesVersionHistoryAndPackedBytes() throws Exception {
        var codec = spy(new OoxmlTemplatePackageCodec());
        var service = new DocumentTemplateService(new DocumentTemplateGitRepository(git), codec);
        var servlet = new DocumentTemplateWebDavServlet(service, new DocumentTemplateWebDavLockManager());
        String first = propfind(servlet);
        git.resetCounts();

        assertThat(propfind(servlet)).isEqualTo(first);

        verify(codec, times(12)).pack(anyMap());
        assertThat(first).contains("<D:getcontentlength>", versions.get("template-0"));
        System.out.println("Template warm Depth 1 PROPFIND object opens: " + git.counts());
        assertThat(git.commitOpens).as("manifest read and two snapshot commit reads per template")
                .isLessThanOrEqualTo(25);

        var replacement = writer.commit(manifest("template-0", "Revised template"),
                parts, versions.get("template-0"), "editor", "Revise one template");
        String changed = propfind(servlet);
        assertThat(changed).contains(replacement.commitId(), versions.get("template-1"))
                .doesNotContain(versions.get("template-0"));
        verify(codec, times(13)).pack(anyMap());
    }

    private static String propfind(DocumentTemplateWebDavServlet servlet) throws Exception {
        var request = new MockHttpServletRequest("PROPFIND", "/dav/templates/");
        request.setPathInfo("/");
        request.addHeader("Depth", "1");
        var response = new MockHttpServletResponse();
        servlet.service(request, response);
        assertThat(response.getStatus()).isEqualTo(207);
        return response.getContentAsString();
    }

    private TemplateSnapshot create(DocumentTemplateGitRepository repository, String id, String name)
            throws IOException {
        return repository.commit(manifest(id, name), parts, null, "creator", "Create " + id);
    }

    private TemplateManifest manifest(String id, String name) {
        return new TemplateManifest(1, id, name, id + ".dotx", OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                "2026-08-22T16:00:00Z", "tester",
                parts.values().stream().mapToLong(value -> value.length).sum(), parts.size(),
                OoxmlTemplatePackageCodec.packageSha256(parts));
    }

    private static ObjectId insertCommit(ObjectInserter inserter, ObjectId tree, ObjectId parent)
            throws IOException {
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(tree);
        commit.setParentId(parent);
        var author = new PersonIdent("git-editor", "git-editor@taxonomy.local");
        commit.setAuthor(author);
        commit.setCommitter(author);
        commit.setMessage("Edit template root through Git");
        return inserter.insert(commit);
    }

    private static final class CountingRepository extends InMemoryRepository {
        int commitOpens;
        int treeOpens;

        void resetCounts() {
            commitOpens = treeOpens = 0;
        }

        String counts() {
            return "commits=" + commitOpens + ", trees=" + treeOpens;
        }

        CountingRepository() {
            super(new DfsRepositoryDescription("template-read-cost"));
        }

        @Override
        public ObjectReader newObjectReader() {
            return count(super.newObjectReader());
        }

        private ObjectReader count(ObjectReader delegate) {
            return new ObjectReader() {
                @Override public ObjectReader newReader() { return count(delegate.newReader()); }
                @Override public Collection<ObjectId> resolve(AbbreviatedObjectId id) throws IOException {
                    return delegate.resolve(id);
                }
                @Override public Set<ObjectId> getShallowCommits() throws IOException {
                    return delegate.getShallowCommits();
                }
                @Override public ObjectLoader open(AnyObjectId id, int type) throws IOException {
                    ObjectLoader loader = delegate.open(id, type);
                    switch (loader.getType()) {
                        case Constants.OBJ_COMMIT -> commitOpens++;
                        case Constants.OBJ_TREE -> treeOpens++;
                        default -> { }
                    }
                    return loader;
                }
                @Override public void close() { delegate.close(); }
            };
        }
    }
}
