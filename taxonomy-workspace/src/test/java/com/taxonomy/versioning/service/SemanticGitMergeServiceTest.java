package com.taxonomy.versioning.service;

import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SemanticGitMergeServiceTest {

    @Test
    void usesRequestedMessageForOrdinaryMergeCommit() throws Exception {
        try (DslGitRepository repository = ordinaryMergeRepository()) {
            SemanticGitMergeService.MergeOutcome outcome = new SemanticGitMergeService()
                    .mergeBranches(
                            repository,
                            "alice",
                            "bob",
                            "architect",
                            "Portfolio review completed");

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.semanticFallback()).isFalse();
            assertMergeCommit(repository, outcome.commitId(), "Portfolio review completed");
        }
    }

    @Test
    void doesNotRewriteAnAlreadyMergedHead() throws Exception {
        try (DslGitRepository repository = ordinaryMergeRepository()) {
            SemanticGitMergeService service = new SemanticGitMergeService();
            SemanticGitMergeService.MergeOutcome first = service.mergeBranches(
                    repository,
                    "alice",
                    "bob",
                    "architect",
                    "Portfolio review completed");
            SemanticGitMergeService.MergeOutcome repeated = service.mergeBranches(
                    repository,
                    "alice",
                    "bob",
                    "architect",
                    "This must not rewrite history");

            assertThat(repeated.success()).isTrue();
            assertThat(repeated.commitId()).isEqualTo(first.commitId());
            assertMergeCommit(repository, repeated.commitId(), "Portfolio review completed");
        }
    }

    @Test
    void mergesIndependentRequirementContributionsIntoARealMergeCommit() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            repository.commitDsl("draft", document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Initial";
                    }
                    """), "system", "base");
            repository.createBranch("alice", "draft");
            repository.createBranch("bob", "draft");

            repository.commitDsl("alice", document("""
                    project P-001 {
                      title: "Reviewed architecture";
                      description: "Initial";
                    }
                    projectRequirement P-001 REQ-A-001 {
                      title: "Alice requirement";
                      text: "Secure voice";
                    }
                    """), "alice", "Alice requirements");
            repository.commitDsl("bob", document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Shared target";
                    }
                    projectRequirement P-001 REQ-B-001 {
                      title: "Bob requirement";
                      text: "Offline operation";
                    }
                    """), "bob", "Bob requirements");

            SemanticGitMergeService.MergeOutcome outcome = new SemanticGitMergeService()
                    .mergeBranches(
                            repository,
                            "alice",
                            "bob",
                            "architect",
                            "Merge reviewed requirements");

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.semanticFallback()).isTrue();
            assertThat(repository.getDslAtHead("bob"))
                    .contains("REQ-A-001")
                    .contains("REQ-B-001")
                    .contains("Reviewed architecture")
                    .contains("Shared target");
            assertMergeCommit(repository, outcome.commitId(), "Merge reviewed requirements");
        }
    }

    @Test
    void preservesSourceOnlyDocumentsDuringSemanticMerge() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String initialDsl = document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Initial";
                    }
                    """);
            repository.commitDsl("draft", initialDsl, "system", "base");
            commitFiles(repository, "draft", initialDsl, Map.of("README.md", "base"));
            repository.createBranch("alice", "draft");
            repository.createBranch("bob", "draft");

            commitFiles(repository, "alice", document("""
                    project P-001 {
                      title: "Reviewed architecture";
                      description: "Initial";
                    }
                    projectRequirement P-001 REQ-A-001 {
                      title: "Alice requirement";
                      text: "Secure voice";
                    }
                    """), Map.of("README.md", "base"));
            commitFiles(repository, "bob", document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Shared target";
                    }
                    projectRequirement P-001 REQ-B-001 {
                      title: "Bob requirement";
                      text: "Offline operation";
                    }
                    """), Map.of("README.md", "base", "requirements.md", "Source contribution"));

            SemanticGitMergeService.MergeOutcome outcome = new SemanticGitMergeService()
                    .mergeBranches(repository, "bob", "alice", "architect");

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.semanticFallback()).isTrue();
            assertThat(fileAtCommit(repository, outcome.commitId(), "requirements.md"))
                    .isEqualTo("Source contribution");
            assertThat(fileAtCommit(repository, outcome.commitId(), "README.md")).isEqualTo("base");
        }
    }

    @Test
    void rejectsConflictingNonDslFilesWithoutAdvancingTargetBranch() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            String initialDsl = document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Initial";
                    }
                    """);
            repository.commitDsl("draft", initialDsl, "system", "base");
            commitFiles(repository, "draft", initialDsl, Map.of("README.md", "base"));
            repository.createBranch("alice", "draft");
            repository.createBranch("bob", "draft");

            commitFiles(repository, "alice", document("""
                    project P-001 {
                      title: "Reviewed architecture";
                      description: "Initial";
                    }
                    projectRequirement P-001 REQ-A-001 {
                      title: "Alice requirement";
                      text: "Secure voice";
                    }
                    """), Map.of("README.md", "alice"));
            commitFiles(repository, "bob", document("""
                    project P-001 {
                      title: "Architecture";
                      description: "Shared target";
                    }
                    projectRequirement P-001 REQ-B-001 {
                      title: "Bob requirement";
                      text: "Offline operation";
                    }
                    """), Map.of("README.md", "bob"));
            String targetBefore = repository.getGitRepository().resolve("refs/heads/bob").name();

            SemanticGitMergeService service = new SemanticGitMergeService();
            SemanticGitMergeService.MergeOutcome preview = service.preview(repository, "alice", "bob");
            assertThat(preview.success()).isFalse();
            assertThat(preview.conflicts()).contains("file README.md");
            assertThat(previewMerge(repository, service).canMerge()).isFalse();
            assertThat(repository.getGitRepository().resolve("refs/heads/bob").name())
                    .isEqualTo(targetBefore);

            SemanticGitMergeService.MergeOutcome outcome = service
                    .mergeBranches(repository, "alice", "bob", "architect");

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.conflicts()).contains("file README.md");
            assertThat(repository.getGitRepository().resolve("refs/heads/bob").name())
                    .isEqualTo(targetBefore);
        }
    }

    @Test
    void refusesTwoDifferentEditsToTheSameRequirementText() throws Exception {
        try (DslGitRepository repository = new DslGitRepository()) {
            repository.commitDsl("draft", requirement("Original"), "system", "base");
            repository.createBranch("alice", "draft");
            repository.createBranch("bob", "draft");
            repository.commitDsl("alice", requirement("Alice text"), "alice", "Alice edit");
            repository.commitDsl("bob", requirement("Bob text"), "bob", "Bob edit");

            SemanticGitMergeService.MergeOutcome outcome = new SemanticGitMergeService()
                    .mergeBranches(repository, "alice", "bob", "architect");

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.conflicts())
                    .contains("projectRequirement P-001 REQ-001:text");
        }
    }

    @Test
    void mergesIndependentAuxiliaryLineEditsDuringSemanticFallback() throws Exception {
        String base = "first\nsecond\nthird\nfourth\nfifth\n";
        try (DslGitRepository repository = auxiliaryMergeRepository(
                Map.of("README.md", base),
                Map.of("README.md", base.replace("first", "Alice first")),
                Map.of("README.md", base.replace("fifth", "Bob fifth")))) {
            SemanticGitMergeService service = new SemanticGitMergeService();
            String targetBefore = repository.getGitRepository().resolve("refs/heads/bob").name();
            assertThat(service.preview(repository, "alice", "bob").success()).isTrue();
            assertThat(repository.getGitRepository().resolve("refs/heads/bob").name())
                    .isEqualTo(targetBefore);

            var outcome = service.mergeBranches(repository, "alice", "bob", "architect");

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.semanticFallback()).isTrue();
            assertThat(fileAtCommit(repository, outcome.commitId(), "README.md"))
                    .isEqualTo("Alice first\nsecond\nthird\nfourth\nBob fifth\n");
        }
    }

    @Test
    void mergesAuxiliaryContentEditWithExecutableBitChange() throws Exception {
        try (DslGitRepository repository = auxiliaryMergeRepository(
                Map.of("run.sh", "echo base\n"),
                Map.of("run.sh", "echo Alice\n"),
                Map.of("run.sh", "echo base\n"))) {
            commitFiles(repository, "bob", repository.getDslAtHead("bob"),
                    Map.of("run.sh", "echo base\n"), Map.of("run.sh", FileMode.EXECUTABLE_FILE));
            SemanticGitMergeService service = new SemanticGitMergeService();
            assertThat(service.preview(repository, "alice", "bob").success()).isTrue();

            var outcome = service.mergeBranches(repository, "alice", "bob", "architect");

            assertThat(outcome.success()).isTrue();
            assertThat(outcome.semanticFallback()).isTrue();
            assertThat(fileAtCommit(repository, outcome.commitId(), "run.sh")).isEqualTo("echo Alice\n");
            try (RevWalk walk = new RevWalk(repository.getGitRepository());
                 TreeWalk tree = TreeWalk.forPath(repository.getGitRepository(), "run.sh",
                         walk.parseCommit(ObjectId.fromString(outcome.commitId())).getTree())) {
                assertThat(tree.getFileMode(0)).isEqualTo(FileMode.EXECUTABLE_FILE);
            }
        }
    }

    @Test
    void previewRejectsAuxiliaryFileDirectoryConflictWithoutAdvancingBranches() throws Exception {
        try (DslGitRepository repository = auxiliaryMergeRepository(
                Map.of(), Map.of("notes", "Alice notes"), Map.of("notes/review.md", "Bob review"))) {
            SemanticGitMergeService service = new SemanticGitMergeService();
            String targetBefore = repository.getGitRepository().resolve("refs/heads/bob").name();
            String sourceBefore = repository.getGitRepository().resolve("refs/heads/alice").name();

            var preview = service.preview(repository, "alice", "bob");
            assertThat(preview.success()).isFalse();
            assertThat(preview.conflicts()).anyMatch(conflict -> conflict.contains("notes"));
            assertThat(previewMerge(repository, service).canMerge()).isFalse();
            var outcome = service.mergeBranches(repository, "alice", "bob", "architect");
            assertThat(outcome.success()).isFalse();
            assertThat(outcome.conflicts()).containsExactlyElementsOf(preview.conflicts());
            assertThat(repository.getGitRepository().resolve("refs/heads/bob").name()).isEqualTo(targetBefore);
            assertThat(repository.getGitRepository().resolve("refs/heads/alice").name()).isEqualTo(sourceBefore);
        }
    }

    private static ConflictDetectionService.MergePreview previewMerge(DslGitRepository repository,
                                                                       SemanticGitMergeService merger) {
        DslGitRepositoryFactory factory = mock(DslGitRepositoryFactory.class);
        when(factory.resolveRepository(WorkspaceContext.SHARED)).thenReturn(repository);
        return new SemanticConflictDetectionService(factory, merger)
                .previewMerge("alice", "bob", WorkspaceContext.SHARED);
    }

    private static DslGitRepository auxiliaryMergeRepository(Map<String, String> baseFiles,
                                                              Map<String, String> aliceFiles,
                                                              Map<String, String> bobFiles) throws Exception {
        DslGitRepository repository = new DslGitRepository();
        String base = document("""
                project P-001 {
                  title: "Architecture";
                  description: "Initial";
                }
                """);
        repository.commitDsl("draft", base, "system", "base");
        commitFiles(repository, "draft", base, baseFiles);
        repository.createBranch("alice", "draft");
        repository.createBranch("bob", "draft");
        commitFiles(repository, "alice", base.replace("Architecture", "Reviewed architecture"), aliceFiles);
        commitFiles(repository, "bob", base.replace("Initial", "Shared target"), bobFiles);
        return repository;
    }

    private static DslGitRepository ordinaryMergeRepository() throws Exception {
        DslGitRepository repository = new DslGitRepository();
        repository.commitDsl("draft", document("""
                project P-001 {
                  title: "Architecture";
                  description: "Initial";
                }

                project P-002 {
                  title: "Operations";
                  description: "Initial";
                }
                """), "system", "base");
        repository.createBranch("alice", "draft");
        repository.createBranch("bob", "draft");

        repository.commitDsl("alice", document("""
                project P-001 {
                  title: "Reviewed architecture";
                  description: "Initial";
                }

                project P-002 {
                  title: "Operations";
                  description: "Initial";
                }
                """), "alice", "Alice project");
        repository.commitDsl("bob", document("""
                project P-001 {
                  title: "Architecture";
                  description: "Initial";
                }

                project P-002 {
                  title: "Reviewed operations";
                  description: "Initial";
                }
                """), "bob", "Bob project");
        return repository;
    }

    private static void assertMergeCommit(DslGitRepository repository,
                                          String commitId,
                                          String expectedMessage) throws Exception {
        try (RevWalk walk = new RevWalk(repository.getGitRepository())) {
            RevCommit mergeCommit = walk.parseCommit(ObjectId.fromString(commitId));
            assertThat(mergeCommit.getParentCount()).isEqualTo(2);
            assertThat(mergeCommit.getFullMessage()).isEqualTo(expectedMessage);
        }
    }

    private static String requirement(String text) {
        return document("""
                projectRequirement P-001 REQ-001 {
                  title: "Requirement";
                  text: "%s";
                }
                """.formatted(text));
    }

    private static String document(String blocks) {
        return """
                meta {
                  language: "taxdsl";
                  version: "2.1";
                  namespace: "test";
                }

                %s
                """.formatted(blocks);
    }

    private static void commitFiles(DslGitRepository repository,
                                    String branch,
                                    String dsl,
                                    Map<String, String> files) throws Exception {
        commitFiles(repository, branch, dsl, files, Map.of());
    }

    private static void commitFiles(DslGitRepository repository,
                                    String branch,
                                    String dsl,
                                    Map<String, String> files,
                                    Map<String, FileMode> modes) throws Exception {
        var git = repository.getGitRepository();
        Ref branchRef = git.getRefDatabase().exactRef(Constants.R_HEADS + branch);
        try (ObjectInserter inserter = git.newObjectInserter()) {
            DirCache cache = DirCache.newInCore();
            var builder = cache.builder();
            Map<String, String> contents = new java.util.TreeMap<>(files);
            contents.put(DslGitRepository.DSL_FILENAME, dsl);
            for (var entry : contents.entrySet()) {
                String path = entry.getKey();
                String content = entry.getValue();
                ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
                DirCacheEntry cacheEntry = new DirCacheEntry(path);
                cacheEntry.setFileMode(modes.getOrDefault(path, FileMode.REGULAR_FILE));
                cacheEntry.setObjectId(blob);
                builder.add(cacheEntry);
            }
            builder.finish();
            ObjectId treeId = cache.writeTree(inserter);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setParentId(branchRef.getObjectId());
            PersonIdent author = new PersonIdent("test", "test@example.invalid");
            commit.setAuthor(author);
            commit.setCommitter(author);
            commit.setMessage("Update branch files");
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();

            RefUpdate update = git.updateRef(Constants.R_HEADS + branch);
            update.setExpectedOldObjectId(branchRef.getObjectId());
            update.setNewObjectId(commitId);
            if (update.update() != RefUpdate.Result.FAST_FORWARD) {
                throw new IllegalStateException("Could not update test branch");
            }
        }
    }

    private static String fileAtCommit(DslGitRepository repository, String commitId, String path)
            throws Exception {
        try (ObjectReader reader = repository.getGitRepository().newObjectReader();
             RevWalk revWalk = new RevWalk(reader);
             TreeWalk tree = new TreeWalk(repository.getGitRepository())) {
            RevCommit commit = revWalk.parseCommit(ObjectId.fromString(commitId));
            tree.addTree(commit.getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                if (path.equals(tree.getPathString())) {
                    return new String(reader.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
                }
            }
            return null;
        }
    }
}
