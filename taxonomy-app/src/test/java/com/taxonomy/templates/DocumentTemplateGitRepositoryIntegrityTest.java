package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTemplateGitRepositoryIntegrityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OoxmlTemplatePackageCodec codec =
            new OoxmlTemplatePackageCodec();
    private InMemoryRepository git;
    private DocumentTemplateGitRepository repository;
    private Map<String, byte[]> parts;

    @BeforeEach
    void setUp() throws Exception {
        git = new InMemoryRepository(
                new DfsRepositoryDescription("document-template-integrity-test"));
        repository = new DocumentTemplateGitRepository(git);
        try (InputStream input = getClass().getResourceAsStream(
                "/" + DecisionRationaleTemplateContract.DEFAULT_RESOURCE)) {
            assertThat(input).isNotNull();
            parts = codec.unpack(input).parts();
        }
        repository.commit(
                manifest("alpha", "Alpha"),
                parts,
                null,
                "creator",
                "Create alpha");
    }

    @AfterEach
    void tearDown() {
        git.close();
    }

    @Test
    void nestedOoxmlTemplateJsonIsNeverTreatedAsARepositoryManifest()
            throws Exception {
        TreeMap<String, byte[]> files = readHeadFiles();
        byte[] rootManifest = files.get(
                "templates/alpha/" + DocumentTemplateGitRepository.MANIFEST_NAME);
        files.put(
                "templates/alpha/package/custom/"
                        + DocumentTemplateGitRepository.MANIFEST_NAME,
                rootManifest);
        rawCommit(files, "Inject nested OOXML metadata-looking part");

        assertThat(repository.list())
                .singleElement()
                .satisfies(template -> {
                    assertThat(template.templateId()).isEqualTo("alpha");
                    assertThat(template.displayName()).isEqualTo("Alpha");
                });
    }

    @Test
    void directGitManifestWithMismatchingFileNameFailsClosed()
            throws Exception {
        TreeMap<String, byte[]> files = readHeadFiles();
        TemplateManifest invalid = new TemplateManifest(
                1,
                "alpha",
                "Alpha",
                "different.dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                Instant.parse("2026-08-22T16:00:00Z").toString(),
                "expert",
                parts.values().stream().mapToLong(value -> value.length).sum(),
                parts.size(),
                OoxmlTemplatePackageCodec.packageSha256(parts));
        files.put(
                "templates/alpha/" + DocumentTemplateGitRepository.MANIFEST_NAME,
                JSON.writeValueAsBytes(invalid));
        rawCommit(files, "Inject invalid direct-Git manifest");

        assertThatThrownBy(repository::list)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("filename does not match its ID");
        assertThatThrownBy(() -> repository.readCurrent("alpha"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("filename does not match its ID");
    }

    @Test
    void repositoryCommitRejectsManifestStatisticsThatDoNotMatchThePackage() {
        TemplateManifest invalid = new TemplateManifest(
                1,
                "beta",
                "Beta",
                "beta.dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                Instant.parse("2026-08-22T16:00:00Z").toString(),
                "creator",
                1,
                parts.size(),
                OoxmlTemplatePackageCodec.packageSha256(parts));

        assertThatThrownBy(() -> repository.commit(
                invalid,
                parts,
                null,
                "creator",
                "Invalid beta"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("size does not match its manifest");
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

    private TreeMap<String, byte[]> readHeadFiles() throws Exception {
        ObjectId head = git.resolve(Constants.R_HEADS
                + DocumentTemplateGitRepository.BRANCH);
        TreeMap<String, byte[]> files = new TreeMap<>();
        try (RevWalk walk = new RevWalk(git);
             TreeWalk tree = new TreeWalk(git)) {
            tree.addTree(walk.parseCommit(head).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                files.put(
                        tree.getPathString(),
                        git.open(tree.getObjectId(0), Constants.OBJ_BLOB).getBytes());
            }
        }
        return files;
    }

    private void rawCommit(TreeMap<String, byte[]> files, String message)
            throws Exception {
        ObjectId previous = git.resolve(Constants.R_HEADS
                + DocumentTemplateGitRepository.BRANCH);
        PersonIdent identity = new PersonIdent(
                "direct-git-expert", "expert@taxonomy.local");
        ObjectId commitId;
        try (ObjectInserter inserter = git.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            DirCacheBuilder builder = cache.builder();
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                DirCacheEntry entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(
                        Constants.OBJ_BLOB, file.getValue()));
                builder.add(entry);
            }
            builder.finish();
            ObjectId treeId = cache.writeTree(inserter);

            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setParentId(previous);
            commit.setAuthor(identity);
            commit.setCommitter(identity);
            commit.setMessage(message);
            commitId = inserter.insert(commit);
            inserter.flush();
        }

        RefUpdate update = git.updateRef(Constants.R_HEADS
                + DocumentTemplateGitRepository.BRANCH);
        update.setExpectedOldObjectId(previous);
        update.setNewObjectId(commitId);
        assertThat(update.update()).isEqualTo(RefUpdate.Result.FAST_FORWARD);
    }
}
