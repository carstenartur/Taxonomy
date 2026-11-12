package com.taxonomy.catalog.service;

import com.taxonomy.identity.StableIdentityHash;
import com.taxonomy.workspace.storage.DslGitRepository;
import com.taxonomy.workspace.storage.DslGitRepositoryFactory;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class CatalogueMappingReviewHistory {
    public enum Action { ACCEPT, CHANGE, REJECT }

    public record Decision(String code, String catalogueRevision, String mappingVersion, String workbookSha256,
                           String overlaySha256, TaxonomyService.ProvisionalProductMapping original,
                           Action action, String proposedParent, List<String> secondary, String rationale,
                           String reviewer, Instant reviewedAt, String proposedVersion, String proposedSha256) { }
    public record Entry(String commit, String parent, Decision decision) { }
    public record Page(String head, List<Entry> decisions, String next) { }

    private static final String DECISION = "decision.json";
    private static final String OVERLAY = "overlay.json";
    private final DslGitRepositoryFactory repositories;
    private final ObjectMapper mapper;

    public CatalogueMappingReviewHistory(DslGitRepositoryFactory repositories, ObjectMapper mapper) {
        this.repositories = repositories;
        this.mapper = mapper;
    }

    private static String ref(String code) {
        if (code == null || code.isBlank() || code.length() > 160)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product code");
        return "refs/catalogue-review/products/" + StableIdentityHash.sha256(code);
    }
    private static ObjectId commitId(String value) {
        if (value == null || !ObjectId.isId(value))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An exact review commit is required");
        return ObjectId.fromString(value);
    }
    private static ObjectId head(Repository repository, String ref) throws IOException {
        repository.getRefDatabase().refresh();
        Ref current = repository.exactRef(ref);
        return current == null ? ObjectId.zeroId() : current.getObjectId();
    }

    public Entry append(String expectedHead, Decision decision, byte[] proposedOverlay) throws IOException {
        boolean rejected = decision.action() == Action.REJECT;
        if (rejected != (proposedOverlay == null) || rejected != (decision.proposedSha256() == null)
                || rejected != (decision.proposedVersion() == null))
            throw new IOException("Incomplete review proposal");
        if (proposedOverlay != null && !decision.proposedSha256().equals(
                com.taxonomy.catalog.provenance.CatalogueSourceBytes.read(new java.io.ByteArrayInputStream(proposedOverlay)).sha256()))
            throw new IOException("Unexpected review proposal digest");
        var storage = repositories.getCatalogueReviewRepository();
        var repository = storage.getGitRepository();
        String ref = ref(decision.code());
        var expected = commitId(expectedHead);
        if (!head(repository, ref).equals(expected)) throw conflict();
        try (var objects = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(DECISION, FileMode.REGULAR_FILE, objects.insert(Constants.OBJ_BLOB, mapper.writeValueAsBytes(decision)));
            if (proposedOverlay != null) tree.append(OVERLAY, FileMode.REGULAR_FILE, objects.insert(Constants.OBJ_BLOB, proposedOverlay));
            var commit = new CommitBuilder();
            commit.setTreeId(objects.insert(tree));
            if (!expected.equals(ObjectId.zeroId())) commit.setParentId(expected);
            var author = new PersonIdent("Taxonomy catalogue review", "catalogue-review@localhost");
            commit.setAuthor(author); commit.setCommitter(author);
            commit.setMessage("Review provisional mapping " + decision.code());
            storage.signCommit(commit, ref);
            ObjectId saved = objects.insert(commit);
            objects.flush();
            var update = repository.updateRef(ref);
            update.setExpectedOldObjectId(expected); update.setNewObjectId(saved);
            var result = update.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) throw conflict();
            return new Entry(saved.name(), expected.name(), decision);
        }
    }

    public Page page(String code, String after) throws IOException {
        var storage = repositories.getCatalogueReviewRepository();
        var repository = storage.getGitRepository();
        var head = head(repository, ref(code));
        if (head.equals(ObjectId.zeroId())) {
            if (after != null) throw missing();
            return new Page(head.name(), List.of(), null);
        }
        try (var walk = new RevWalk(repository)) {
            var cursor = selected(walk, head, after);
            var entries = new ArrayList<Entry>();
            while (cursor != null && entries.size() < 40) {
                var reviewed = read(storage, cursor, code);
                entries.add(reviewed);
                cursor = cursor.getParentCount() == 0 ? null : walk.parseCommit(cursor.getParent(0));
            }
            return new Page(head.name(), List.copyOf(entries), cursor == null ? null : cursor.name());
        }
    }

    public byte[] proposal(String code, String commit) throws IOException {
        var storage = repositories.getCatalogueReviewRepository();
        var repository = storage.getGitRepository();
        var head = head(repository, ref(code));
        if (head.equals(ObjectId.zeroId())) throw missing();
        try (var walk = new RevWalk(repository)) {
            var selected = selected(walk, head, commitId(commit).name());
            var decision = read(storage, selected, code).decision();
            if (decision.proposedSha256() == null) throw missing();
            byte[] bytes = blob(repository, selected, OVERLAY, com.taxonomy.catalog.provenance.CatalogueSourceBytes.MAX_BYTES);
            if (!com.taxonomy.catalog.provenance.CatalogueSourceBytes.read(new java.io.ByteArrayInputStream(bytes))
                    .sha256().equals(decision.proposedSha256())) throw new IOException("Invalid retained proposal");
            return bytes;
        }
    }

    private Entry read(DslGitRepository storage, RevCommit commit, String code) throws IOException {
        storage.verifySnapshot(commit.name());
        if (commit.getParentCount() > 1) throw new IOException("Invalid review ancestry");
        var decision = mapper.readValue(blob(storage.getGitRepository(), commit, DECISION, 128 * 1024), Decision.class);
        if (!code.equals(decision.code())) throw new IOException("Review belongs to a different product");
        return new Entry(commit.name(), commit.getParentCount() == 0 ? ObjectId.zeroId().name() : commit.getParent(0).name(), decision);
    }

    private static RevCommit selected(RevWalk walk, ObjectId head, String id) throws IOException {
        var latest = walk.parseCommit(head);
        if (id == null) return latest;
        try {
            var selected = walk.parseCommit(commitId(id));
            if (!walk.isMergedInto(selected, latest)) throw missing();
            return selected;
        } catch (org.eclipse.jgit.errors.MissingObjectException | org.eclipse.jgit.errors.IncorrectObjectTypeException unknown) {
            throw missing();
        }
    }

    private static byte[] blob(Repository repo, RevCommit commit, String name, int limit) throws IOException {
        try (var path = TreeWalk.forPath(repo, name, commit.getTree())) {
            if (path == null || !FileMode.REGULAR_FILE.equals(path.getFileMode(0))) throw new IOException("Incomplete review record");
            return repo.open(path.getObjectId(0), Constants.OBJ_BLOB).getBytes(limit);
        }
    }
    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalogue review not found");
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Catalogue review has changed");
    }
}
