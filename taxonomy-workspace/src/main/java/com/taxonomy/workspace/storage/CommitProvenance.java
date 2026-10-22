package com.taxonomy.workspace.storage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.workspace.signing.GitCommitSignatureService;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * One-hop evidence for a semantic copy across independent Git histories.
 * The target's signed tree covers this document. Original commit/tree bytes
 * prove the cited source signature and DSL blob ID, without recursively copying
 * old evidence or claiming that a semantic merge is byte-identical to its source.
 */
public record CommitProvenance(int version, String operation, List<Source> sources) {
    public static final String FILENAME = ".taxonomy-provenance.json";
    public static final int MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_SOURCE_BYTES = 256 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public CommitProvenance {
        if (version != 1) throw new IllegalArgumentException("Unsupported provenance version");
        operation = text(operation, "operation");
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (sources.isEmpty() || sources.size() > 4) throw new IllegalArgumentException("Invalid provenance source count");
    }

    public record Source(String repositoryId, String branch, String commitId, String baseCommitId,
                         String dslBlobId, byte[] rawCommit, byte[] rawTree) {
        public Source {
            repositoryId = text(repositoryId, "repositoryId");
            branch = text(branch, "branch");
            commitId = objectId(commitId);
            if (baseCommitId != null) baseCommitId = objectId(baseCommitId);
            dslBlobId = objectId(dslBlobId);
            rawCommit = bounded(rawCommit);
            rawTree = bounded(rawTree);
        }
        @Override public byte[] rawCommit() { return rawCommit.clone(); }
        @Override public byte[] rawTree() { return rawTree.clone(); }
    }

    public static CommitProvenance capture(String operation, DslGitRepository source,
                                           String repositoryId, String branch, String head,
                                           String baseCommitId) throws IOException {
        source.verifySnapshot(head);
        var repository = source.getGitRepository();
        byte[] commit = repository.open(ObjectId.fromString(head), Constants.OBJ_COMMIT).getBytes(MAX_SOURCE_BYTES);
        byte[] tree = repository.open(RevCommit.parse(commit).getTree(), Constants.OBJ_TREE).getBytes(MAX_SOURCE_BYTES);
        return new CommitProvenance(1, operation, List.of(new Source(repositoryId, branch, head, baseCommitId,
                dslBlob(tree), commit, tree)));
    }

    public void verify(GitCommitSignatureService signatures) throws IOException {
        try (var formatter = new ObjectInserter.Formatter()) {
            for (var source : sources) {
                if (!formatter.idFor(Constants.OBJ_COMMIT, source.rawCommit).name().equals(source.commitId)) {
                    throw new IOException("Provenance source commit does not match its object ID");
                }
                signatures.requireAcceptable(source.rawCommit);
                var sourceCommit = RevCommit.parse(source.rawCommit);
                if (!formatter.idFor(Constants.OBJ_TREE, source.rawTree).equals(sourceCommit.getTree())) {
                    throw new IOException("Provenance source tree does not match the signed commit");
                }
                if (!dslBlob(source.rawTree).equals(source.dslBlobId)) {
                    throw new IOException("Provenance DSL blob does not match the source tree");
                }
            }
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed provenance evidence", malformed);
        }
    }

    public byte[] toBytes() throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(this);
        if (bytes.length > MAX_DOCUMENT_BYTES) throw new IOException("Provenance document is too large");
        return bytes;
    }

    public static CommitProvenance fromBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > MAX_DOCUMENT_BYTES) throw new IOException("Invalid provenance document size");
        try {
            var provenance = JSON.readValue(bytes, CommitProvenance.class);
            if (provenance == null) throw new IOException("Provenance document must be an object");
            return provenance;
        } catch (RuntimeException invalid) {
            throw new IOException("Malformed provenance document", invalid);
        }
    }

    private static String dslBlob(byte[] rawTree) throws IOException {
        var tree = new CanonicalTreeParser();
        tree.reset(rawTree);
        String blob = null;
        while (!tree.eof()) {
            if (DslGitRepository.DSL_FILENAME.equals(tree.getEntryPathString())) {
                if (blob != null || !FileMode.REGULAR_FILE.equals(tree.getEntryFileMode())) {
                    throw new IOException("Provenance source tree has an ambiguous architecture DSL");
                }
                blob = tree.getEntryObjectId().name();
            }
            tree.next(1);
        }
        if (blob != null) return blob;
        throw new IOException("Provenance source tree has no architecture DSL");
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 512) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
    private static String objectId(String value) {
        if (value == null || !ObjectId.isId(value)) throw new IllegalArgumentException("Invalid provenance object ID");
        return value;
    }
    private static byte[] bounded(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_SOURCE_BYTES) throw new IllegalArgumentException("Invalid provenance evidence size");
        return bytes.clone();
    }
}
