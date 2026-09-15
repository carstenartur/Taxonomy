package com.taxonomy.templates;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import jakarta.annotation.PreDestroy;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Versions canonical, unzipped OOXML template packages in one logical JGit repository.
 *
 * <p>This facade is intentionally separate from {@code DslGitRepository}, which owns
 * the single-file {@code architecture.taxdsl} convention. The branch head is the atomic
 * repository update boundary, while each template exposes the most recent commit that
 * actually changed its own subtree as its representation version and HTTP ETag.</p>
 */
@Component
public class DocumentTemplateGitRepository implements AutoCloseable {

    static final String REPOSITORY_NAME = "taxonomy-document-templates";
    static final String BRANCH = "main";
    static final String ROOT = "templates/";
    static final String MANIFEST_NAME = "template.json";
    static final String PACKAGE_DIRECTORY = "package/";

    private static final Pattern COMMIT_ID = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_WRITE_ATTEMPTS = 5;

    private final HibernateGitStorage storageHandle;
    private final Repository repository;
    private final boolean closeRepository;
    private final ReentrantLock writeLock = new ReentrantLock();

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentTemplateGitRepository(HibernateRepositoryFactory storageFactory) {
        this(Objects.requireNonNull(storageFactory, "storageFactory")
                .open(new RepositoryName(REPOSITORY_NAME)));
    }

    private DocumentTemplateGitRepository(HibernateGitStorage storageHandle) {
        this.storageHandle = storageHandle;
        this.repository = storageHandle.repository();
        this.closeRepository = false;
    }

    DocumentTemplateGitRepository(Repository repository) {
        this.storageHandle = null;
        this.repository = Objects.requireNonNull(repository, "repository");
        this.closeRepository = false;
    }

    /**
     * Commit a complete replacement package while preserving all other templates.
     *
     * <p>A {@code null} expected template version means create-only. A non-null version
     * must match the most recent commit that changed this template. The repository-wide
     * branch head is intentionally not exposed as the template ETag: unrelated template
     * changes can therefore be rebased transparently without causing false conflicts.</p>
     */
    public TemplateSnapshot commit(
            TemplateManifest manifest,
            Map<String, byte[]> packageParts,
            String expectedTemplateVersion,
            String author,
            String message) throws IOException {

        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(packageParts, "packageParts");
        validateStoredManifest(manifest.templateId(), manifest, packageParts);
        String prefix = templatePrefix(manifest.templateId());
        String normalizedExpected = normalizeExpectedVersion(expectedTemplateVersion);

        writeLock.lock();
        try {
            for (int attempt = 1; attempt <= MAX_WRITE_ATTEMPTS; attempt++) {
                Ref currentRef = repository.getRefDatabase()
                        .exactRef(Constants.R_HEADS + BRANCH);
                ObjectId repositoryHead = currentRef == null
                        ? null : currentRef.getObjectId();
                String actualTemplateVersion = currentTemplateVersion(
                        manifest.templateId(), repositoryHead);
                requireTemplatePrecondition(
                        normalizedExpected, actualTemplateVersion);

                TreeMap<String, ObjectId> treeObjects = repositoryHead == null
                        ? new TreeMap<>()
                        : readTreeObjectIds(repositoryHead);
                treeObjects.keySet().removeIf(path -> path.startsWith(prefix));

                ObjectId commitId;
                PersonIdent identity = identity(author);
                try (ObjectInserter inserter = repository.newObjectInserter()) {
                    byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
                    treeObjects.put(prefix + MANIFEST_NAME,
                            inserter.insert(Constants.OBJ_BLOB, manifestBytes));

                    TreeMap<String, byte[]> sortedParts = new TreeMap<>(packageParts);
                    for (Map.Entry<String, byte[]> part : sortedParts.entrySet()) {
                        treeObjects.put(prefix + PACKAGE_DIRECTORY + part.getKey(),
                                inserter.insert(Constants.OBJ_BLOB, part.getValue()));
                    }

                    DirCache cache = DirCache.newInCore();
                    DirCacheBuilder builder = cache.builder();
                    for (Map.Entry<String, ObjectId> entry : treeObjects.entrySet()) {
                        DirCacheEntry cacheEntry = new DirCacheEntry(entry.getKey());
                        cacheEntry.setFileMode(FileMode.REGULAR_FILE);
                        cacheEntry.setObjectId(entry.getValue());
                        builder.add(cacheEntry);
                    }
                    builder.finish();
                    ObjectId treeId = cache.writeTree(inserter);

                    CommitBuilder commit = new CommitBuilder();
                    commit.setTreeId(treeId);
                    commit.setAuthor(identity);
                    commit.setCommitter(identity);
                    commit.setMessage(message == null || message.isBlank()
                            ? "Update document template " + manifest.templateId()
                            : message.strip());
                    if (repositoryHead != null) {
                        commit.setParentId(repositoryHead);
                    }

                    commitId = inserter.insert(commit);
                    inserter.flush();
                }

                RefUpdate update = repository.updateRef(Constants.R_HEADS + BRANCH);
                update.setNewObjectId(commitId);
                update.setExpectedOldObjectId(repositoryHead == null
                        ? ObjectId.zeroId() : repositoryHead);
                update.setForceUpdate(false);
                update.setRefLogIdent(identity);
                update.setRefLogMessage("template: " + manifest.templateId(), false);
                RefUpdate.Result result = update.update();
                if (successful(result)) {
                    return read(manifest.templateId(), commitId.name());
                }
                if (!concurrent(result)) {
                    throw new IOException("Template repository ref update failed: " + result);
                }
                // Another process advanced the shared branch. Re-read it and retry only
                // if this template still satisfies the same per-template precondition.
            }

            String actual = currentTemplateVersion(
                    manifest.templateId(), headObjectId());
            throw new TemplateConflictException(normalizedExpected, actual);
        } finally {
            writeLock.unlock();
        }
    }

    public List<TemplateDescriptor> list() throws IOException {
        ObjectId head = headObjectId();
        if (head == null) {
            return List.of();
        }
        Map<String, byte[]> manifests = readManifestFiles(head);
        List<TemplateDescriptor> templates = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : manifests.entrySet()) {
            String pathTemplateId = templateIdFromManifestPath(file.getKey());
            if (pathTemplateId == null) {
                continue;
            }
            TemplateManifest manifest = JSON.readValue(
                    file.getValue(), TemplateManifest.class);
            validateStoredManifest(pathTemplateId, manifest, null);
            String version = currentTemplateVersion(pathTemplateId, head);
            if (version == null) {
                continue;
            }
            templates.add(new TemplateDescriptor(
                    manifest.templateId(),
                    manifest.displayName(),
                    manifest.fileName(),
                    version,
                    manifest.updatedAt(),
                    manifest.updatedBy(),
                    manifest.uncompressedSize(),
                    manifest.partCount(),
                    manifest.packageSha256()));
        }
        return templates.stream()
                .sorted(Comparator.comparing(TemplateDescriptor::displayName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public TemplateSnapshot readCurrent(String templateId) throws IOException {
        validateTemplateId(templateId);
        ObjectId head = headObjectId();
        String version = currentTemplateVersion(templateId, head);
        if (version == null) {
            throw new TemplateNotFoundException(templateId, null);
        }
        return read(templateId, version);
    }

    public TemplateSnapshot read(String templateId, String revision) throws IOException {
        validateTemplateId(templateId);
        ObjectId objectId = resolveRevision(revision);
        String prefix = templatePrefix(templateId);
        Map<String, byte[]> files = readFiles(objectId, prefix);
        byte[] manifestBytes = files.get(prefix + MANIFEST_NAME);
        if (manifestBytes == null) {
            throw new TemplateNotFoundException(templateId, revision);
        }
        TemplateManifest manifest = JSON.readValue(
                manifestBytes, TemplateManifest.class);
        TreeMap<String, byte[]> parts = new TreeMap<>();
        String packagePrefix = prefix + PACKAGE_DIRECTORY;
        files.forEach((path, content) -> {
            if (path.startsWith(packagePrefix)) {
                parts.put(path.substring(packagePrefix.length()), content);
            }
        });
        validateStoredManifest(templateId, manifest, parts);
        return new TemplateSnapshot(manifest, objectId.name(), parts);
    }

    public List<TemplateRevision> history(String templateId) throws IOException {
        validateTemplateId(templateId);
        ObjectId head = headObjectId();
        if (head == null) {
            return List.of();
        }
        String path = templateDirectoryPath(templateId);
        List<TemplateRevision> history = new ArrayList<>();
        try (RevWalk walk = new RevWalk(repository)) {
            walk.markStart(walk.parseCommit(head));
            for (RevCommit commit : walk) {
                ObjectId current = pathObjectId(commit.getTree(), path);
                ObjectId parent = null;
                if (commit.getParentCount() > 0) {
                    RevCommit parsedParent = walk.parseCommit(commit.getParent(0));
                    parent = pathObjectId(parsedParent.getTree(), path);
                }
                if (current == null || Objects.equals(current, parent)) {
                    continue;
                }
                history.add(new TemplateRevision(
                        commit.name(),
                        commit.getAuthorIdent().getName(),
                        Instant.ofEpochSecond(commit.getCommitTime()).toString(),
                        commit.getFullMessage()));
            }
        }
        return List.copyOf(history);
    }

    public TemplateDiff diff(String templateId, String fromRevision, String toRevision)
            throws IOException {
        TemplateSnapshot before = read(templateId, fromRevision);
        TemplateSnapshot after = read(templateId, toRevision);
        TreeMap<String, byte[]> beforeParts = new TreeMap<>(before.parts());
        TreeMap<String, byte[]> afterParts = new TreeMap<>(after.parts());
        TreeMap<String, PartChange> changes = new TreeMap<>();

        for (String path : beforeParts.keySet()) {
            if (!afterParts.containsKey(path)) {
                changes.put(path, PartChange.DELETED);
            } else if (!Arrays.equals(beforeParts.get(path), afterParts.get(path))) {
                changes.put(path, PartChange.MODIFIED);
            }
        }
        for (String path : afterParts.keySet()) {
            if (!beforeParts.containsKey(path)) {
                changes.put(path, PartChange.ADDED);
            }
        }
        return new TemplateDiff(templateId, before.commitId(), after.commitId(), changes);
    }

    /** Repository-wide branch head used only for diagnostics and atomic ref updates. */
    public String headCommit() throws IOException {
        ObjectId head = headObjectId();
        return head == null ? null : head.name();
    }

    private String currentTemplateVersion(String templateId, ObjectId head)
            throws IOException {
        if (head == null) {
            return null;
        }
        String path = templateDirectoryPath(templateId);
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(head);
            if (pathObjectId(commit.getTree(), path) == null) {
                return null;
            }
            while (true) {
                ObjectId current = pathObjectId(commit.getTree(), path);
                RevCommit parentCommit = null;
                ObjectId parent = null;
                if (commit.getParentCount() > 0) {
                    parentCommit = walk.parseCommit(commit.getParent(0));
                    parent = pathObjectId(parentCommit.getTree(), path);
                }
                if (!Objects.equals(current, parent)) {
                    return commit.name();
                }
                if (parentCommit == null) {
                    return null;
                }
                commit = parentCommit;
            }
        }
    }

    private static void requireTemplatePrecondition(
            String expected,
            String actual) throws TemplateConflictException {
        if (expected == null) {
            if (actual != null) {
                throw new TemplateConflictException(null, actual);
            }
            return;
        }
        if (!expected.equals(actual)) {
            throw new TemplateConflictException(expected, actual);
        }
    }

    private static String normalizeExpectedVersion(String expected) {
        if (expected == null || expected.isBlank()) {
            return null;
        }
        String normalized = expected.strip();
        if (!COMMIT_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Expected template version must be a full Git commit ID");
        }
        return normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private ObjectId resolveRevision(String revision) throws IOException {
        if (revision == null || revision.isBlank()) {
            ObjectId head = headObjectId();
            if (head == null) {
                throw new TemplateNotFoundException("", revision);
            }
            return head;
        }
        if (!COMMIT_ID.matcher(revision).matches()) {
            throw new IllegalArgumentException("Template revision must be a full Git commit ID");
        }
        ObjectId candidate = ObjectId.fromString(revision);
        try (RevWalk walk = new RevWalk(repository)) {
            walk.parseCommit(candidate);
            return candidate;
        } catch (org.eclipse.jgit.errors.MissingObjectException exception) {
            throw new TemplateNotFoundException("", revision);
        }
    }

    private ObjectId headObjectId() throws IOException {
        Ref ref = repository.getRefDatabase().exactRef(Constants.R_HEADS + BRANCH);
        return ref == null ? null : ref.getObjectId();
    }

    private TreeMap<String, ObjectId> readTreeObjectIds(ObjectId commitId) throws IOException {
        TreeMap<String, ObjectId> result = new TreeMap<>();
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commitId).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                result.put(tree.getPathString(), tree.getObjectId(0).copy());
            }
        }
        return result;
    }

    private Map<String, byte[]> readManifestFiles(ObjectId commitId) throws IOException {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commitId).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (templateIdFromManifestPath(path) == null) {
                    continue;
                }
                ObjectLoader loader = repository.open(
                        tree.getObjectId(0), Constants.OBJ_BLOB);
                result.put(path, loader.getBytes(1_048_576));
            }
        }
        return result;
    }

    private Map<String, byte[]> readFiles(ObjectId commitId, String prefix) throws IOException {
        LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(commitId).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                String path = tree.getPathString();
                if (!path.startsWith(prefix)) {
                    continue;
                }
                ObjectLoader loader = repository.open(
                        tree.getObjectId(0), Constants.OBJ_BLOB);
                result.put(path, loader.getBytes(MAX_STORED_PART_BYTES));
            }
        }
        return result;
    }

    private ObjectId pathObjectId(RevTree tree, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, path, tree)) {
            return walk == null ? null : walk.getObjectId(0).copy();
        }
    }

    private static final int MAX_STORED_PART_BYTES =
            OoxmlTemplatePackageCodec.MAX_PART_BYTES + 1_048_576;

    private static String templatePrefix(String templateId) {
        return templateDirectoryPath(templateId) + "/";
    }

    private static String templateDirectoryPath(String templateId) {
        validateTemplateId(templateId);
        return ROOT + templateId;
    }

    private static String templateIdFromManifestPath(String path) {
        if (path == null || !path.startsWith(ROOT)) {
            return null;
        }
        String relative = path.substring(ROOT.length());
        int slash = relative.indexOf('/');
        if (slash <= 0 || slash != relative.lastIndexOf('/')
                || !MANIFEST_NAME.equals(relative.substring(slash + 1))) {
            return null;
        }
        String templateId = relative.substring(0, slash);
        try {
            validateTemplateId(templateId);
            return templateId;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void validateStoredManifest(
            String expectedTemplateId,
            TemplateManifest manifest,
            Map<String, byte[]> parts) throws IOException {
        if (manifest.schemaVersion() != 1) {
            throw new IOException("Unsupported stored template manifest schema version: "
                    + manifest.schemaVersion());
        }
        if (!expectedTemplateId.equals(manifest.templateId())) {
            throw new IOException("Stored template manifest ID does not match its path");
        }
        String normalizedDisplayName;
        try {
            normalizedDisplayName = DocumentTemplateService.normalizeDisplayName(
                    manifest.displayName(), expectedTemplateId);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Stored template display name is invalid", invalid);
        }
        if (!Objects.equals(normalizedDisplayName, manifest.displayName())) {
            throw new IOException("Stored template display name is not canonical");
        }
        if (!Objects.equals(expectedTemplateId + ".dotx", manifest.fileName())) {
            throw new IOException("Stored template filename does not match its ID");
        }
        if (!Objects.equals(
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE, manifest.mediaType())) {
            throw new IOException("Stored template media type is invalid");
        }
        try {
            Instant.parse(manifest.updatedAt());
        } catch (RuntimeException invalid) {
            throw new IOException("Stored template update timestamp is invalid", invalid);
        }
        if (manifest.uncompressedSize() < 0 || manifest.partCount() < 0) {
            throw new IOException("Stored template package statistics are invalid");
        }
        if (manifest.packageSha256() == null
                || !SHA256.matcher(manifest.packageSha256()).matches()) {
            throw new IOException("Stored template package checksum is invalid");
        }
        if (parts == null) {
            return;
        }
        long actualSize = 0;
        for (Map.Entry<String, byte[]> part : parts.entrySet()) {
            if (part.getKey() == null || part.getValue() == null) {
                throw new IOException("Stored OOXML package contains a null part");
            }
            actualSize += part.getValue().length;
        }
        if (manifest.partCount() != parts.size()) {
            throw new IOException("Stored OOXML part count does not match its manifest");
        }
        if (manifest.uncompressedSize() != actualSize) {
            throw new IOException("Stored OOXML size does not match its manifest");
        }
        String actualHash = OoxmlTemplatePackageCodec.packageSha256(parts);
        if (!actualHash.equals(manifest.packageSha256())) {
            throw new IOException("Stored OOXML package checksum does not match its manifest");
        }
    }

    static void validateTemplateId(String templateId) {
        if (templateId == null
                || !templateId.matches("[a-z0-9][a-z0-9._-]{0,79}")) {
            throw new IllegalArgumentException(
                    "Template ID must match [a-z0-9][a-z0-9._-]{0,79}");
        }
    }

    private static PersonIdent identity(String author) {
        String name = author == null || author.isBlank() ? "taxonomy" : author.strip();
        name = name.replaceAll("[\\r\\n<>]", "_");
        String local = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "_");
        if (local.isBlank()) {
            local = "taxonomy";
        }
        return new PersonIdent(name, local + "@taxonomy.local");
    }

    private static boolean successful(RefUpdate.Result result) {
        return result == RefUpdate.Result.NEW
                || result == RefUpdate.Result.FAST_FORWARD
                || result == RefUpdate.Result.NO_CHANGE;
    }

    private static boolean concurrent(RefUpdate.Result result) {
        return result == RefUpdate.Result.REJECTED
                || result == RefUpdate.Result.LOCK_FAILURE;
    }

    @PreDestroy
    @Override
    public void close() {
        if (storageHandle != null) {
            storageHandle.close();
        } else if (closeRepository) {
            repository.close();
        }
    }

    public record TemplateManifest(
            int schemaVersion,
            String templateId,
            String displayName,
            String fileName,
            String mediaType,
            String updatedAt,
            String updatedBy,
            long uncompressedSize,
            int partCount,
            String packageSha256) {
    }

    /**
     * Current per-template representation metadata. Despite the historic field name,
     * {@code headCommit} is the last commit that changed this template, not the shared
     * repository branch head.
     */
    public record TemplateDescriptor(
            String templateId,
            String displayName,
            String fileName,
            String headCommit,
            String updatedAt,
            String updatedBy,
            long uncompressedSize,
            int partCount,
            String packageSha256) {
    }

    public record TemplateSnapshot(
            TemplateManifest manifest,
            String commitId,
            Map<String, byte[]> parts) {

        public TemplateSnapshot {
            TreeMap<String, byte[]> copy = new TreeMap<>();
            parts.forEach((path, value) ->
                    copy.put(path, Arrays.copyOf(value, value.length)));
            parts = Map.copyOf(copy);
        }
    }

    public record TemplateRevision(
            String commitId,
            String author,
            String committedAt,
            String message) {
    }

    public record TemplateDiff(
            String templateId,
            String fromRevision,
            String toRevision,
            Map<String, PartChange> changes) {
    }

    public enum PartChange {
        ADDED,
        MODIFIED,
        DELETED
    }

    public static final class TemplateConflictException extends IOException {
        private final String expectedHead;
        private final String actualHead;

        TemplateConflictException(String expectedHead, String actualHead) {
            super(expectedHead == null
                    ? "Document template already exists"
                            + (actualHead == null ? "" : "; current version is " + actualHead)
                    : "Document template changed concurrently"
                            + (actualHead == null ? "; it no longer exists"
                                    : "; current version is " + actualHead));
            this.expectedHead = expectedHead;
            this.actualHead = actualHead;
        }

        public String expectedHead() {
            return expectedHead;
        }

        public String actualHead() {
            return actualHead;
        }
    }

    public static final class TemplateNotFoundException extends IOException {
        TemplateNotFoundException(String templateId, String revision) {
            super("Document template not found"
                    + (templateId == null || templateId.isBlank()
                    ? ""
                    : ": " + templateId)
                    + (revision == null || revision.isBlank()
                    ? ""
                    : " at " + revision));
        }
    }
}
