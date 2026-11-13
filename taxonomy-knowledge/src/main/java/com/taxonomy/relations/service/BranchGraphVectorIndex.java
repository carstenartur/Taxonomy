package com.taxonomy.relations.service;

import com.taxonomy.identity.StableIdentityHash;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** One immutable Lucene generation. Its node payloads and relation facets belong to the same source. */
final class BranchGraphVectorIndex {
    static final String FORMAT = "lucene9-cosine384-v1";
    private static final String NODE = "nodeVector";
    private static final String RELATION = "relationVector";
    private static final int DIMENSION = 384;
    private static final long MAX_BYTES = 128L * 1024 * 1024;

    record Archive(byte[] bytes, int nodeCount, int relationCount, String sourceKey) { }
    record RelationHit(String root, String type) { }
    record Hits(List<String> nodes, List<RelationHit> relations) { }

    static final class Builder implements Closeable {
        private final Directory directory = new ByteBuffersDirectory();
        private final IndexWriter writer;
        private final String sourceKey;
        private int nodes;
        private int relations;

        Builder(String sourceKey) throws IOException {
            this.sourceKey = sourceKey;
            writer = new IndexWriter(directory, new IndexWriterConfig());
            writer.setLiveCommitData(Map.of("source", sourceKey, "format", FORMAT).entrySet());
        }

        void node(String nodeJson, String text, float[] vector) throws IOException {
            var document = new Document();
            document.add(new StoredField("node", nodeJson));
            add(document, NODE, text, vector);
            nodes++;
        }

        void relation(String sourceCode, String targetCode, String sourceRoot, String type, String text, float[] vector) throws IOException {
            var document = new Document();
            document.add(new StoredField("source", sourceCode));
            document.add(new StoredField("target", targetCode));
            document.add(new StoredField("root", sourceRoot));
            document.add(new StoredField("type", type));
            add(document, RELATION, text, vector);
            relations++;
        }

        private void add(Document document, String kind, String text, float[] vector) throws IOException {
            checkVector(vector);
            document.add(new StoredField("textSha256", StableIdentityHash.sha256(text)));
            document.add(new KnnFloatVectorField(kind, vector, VectorSimilarityFunction.COSINE));
            writer.addDocument(document);
        }

        Archive finish() throws IOException {
            if (nodes == 0 || relations == 0) throw new IllegalStateException("A branch generation requires both vector types");
            writer.commit();
            try (var reader = DirectoryReader.open(directory)) {
                verify(reader, nodes, relations, sourceKey);
            }
            var bytes = new ByteArrayOutputStream();
            long total = 0;
            byte[] buffer = new byte[16384];
            try (var archive = new ZipOutputStream(bytes)) {
                // Store only files reachable from the committed reader; omit transient Lucene locks.
                var commit = DirectoryReader.listCommits(directory).getLast();
                for (var name : commit.getFileNames().stream().sorted().toList()) {
                    try (var input = directory.openInput(name, IOContext.READONCE)) {
                        total += input.length();
                        if (total > MAX_BYTES) throw new IOException("Branch generation exceeds its storage budget");
                        var entry = new ZipEntry(name);
                        archive.putNextEntry(entry);
                        while (input.getFilePointer() < input.length()) {
                            int count = (int) Math.min(buffer.length, input.length() - input.getFilePointer());
                            input.readBytes(buffer, 0, count);
                            archive.write(buffer, 0, count);
                        }
                        archive.closeEntry();
                    }
                }
            }
            var result = new Archive(bytes.toByteArray(), nodes, relations, sourceKey);
            verifyArchive(result);
            return result;
        }

        @Override public void close() throws IOException {
            try { writer.close(); } finally { directory.close(); }
        }
    }

    static void verifyArchive(Archive archive) throws IOException {
        try (Directory directory = new ByteBuffersDirectory()) {
            restore(directory, archive.bytes());
            try (var reader = DirectoryReader.open(directory)) {
                verify(reader, archive.nodeCount(), archive.relationCount(), archive.sourceKey());
            }
        }
    }

    static Hits search(Archive archive, float[] query, int nodeLimit, int relationLimit) throws IOException {
        checkVector(query);
        try (Directory directory = new ByteBuffersDirectory()) {
            restore(directory, archive.bytes());
            try (var reader = DirectoryReader.open(directory)) {
                verify(reader, archive.nodeCount(), archive.relationCount(), archive.sourceKey());
                var searcher = new IndexSearcher(reader);
                var fields = searcher.storedFields();
                var nodes = new ArrayList<String>();
                for (var hit : searcher.search(new KnnFloatVectorQuery(NODE, query, nodeLimit), nodeLimit).scoreDocs) {
                    nodes.add(fields.document(hit.doc).get("node"));
                }
                var relations = new ArrayList<RelationHit>();
                for (var hit : searcher.search(new KnnFloatVectorQuery(RELATION, query, relationLimit), relationLimit).scoreDocs) {
                    var document = fields.document(hit.doc);
                    relations.add(new RelationHit(document.get("root"), document.get("type")));
                }
                return new Hits(List.copyOf(nodes), List.copyOf(relations));
            }
        }
    }

    private static void restore(Directory directory, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid branch generation length");
        Set<String> files = new HashSet<>();
        long total = 0;
        byte[] buffer = new byte[16384];
        try (var archive = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = archive.getNextEntry()) != null;) {
                String name = entry.getName();
                if (!name.matches("[A-Za-z0-9_.-]{1,120}") || !files.add(name) || files.size() > 8192) {
                    throw new IOException("Invalid branch index file");
                }
                try (var output = directory.createOutput(name, IOContext.DEFAULT)) {
                    for (int count; (count = archive.read(buffer)) != -1;) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("Branch generation exceeds its storage budget");
                        output.writeBytes(buffer, 0, count);
                    }
                }
            }
        }
    }

    private static void verify(DirectoryReader reader, int nodes, int relations, String sourceKey) throws IOException {
        var manifest = reader.getIndexCommit().getUserData();
        if (!FORMAT.equals(manifest.get("format")) || !sourceKey.equals(manifest.get("source"))) {
            throw new IOException("Branch vector manifest points to another source");
        }
        int visibleNodes = 0;
        int visibleRelations = 0;
        for (var leaf : reader.leaves()) {
            var nodeVectors = leaf.reader().getFloatVectorValues(NODE);
            var relationVectors = leaf.reader().getFloatVectorValues(RELATION);
            if (nodeVectors != null) visibleNodes += nodeVectors.size();
            if (relationVectors != null) visibleRelations += relationVectors.size();
        }
        if (nodes < 1 || relations < 1 || reader.numDocs() != (long) nodes + relations
                || visibleNodes != nodes || visibleRelations != relations) {
            throw new IOException("Persisted branch vector coverage is incomplete");
        }
    }

    private static void checkVector(float[] vector) {
        if (vector == null || vector.length != DIMENSION) throw new IllegalArgumentException("Unexpected model vector dimensions");
        double squaredNorm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite model vector");
            squaredNorm += (double) value * value;
        }
        if (squaredNorm == 0) throw new IllegalArgumentException("Empty model vector");
    }
}
