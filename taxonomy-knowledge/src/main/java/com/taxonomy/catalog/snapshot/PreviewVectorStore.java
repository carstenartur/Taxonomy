package com.taxonomy.catalog.snapshot;

import com.taxonomy.catalog.service.NodeEmbeddingText;
import com.taxonomy.dsl.model.CatalogueDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/** Optional, two-slot disk cache of catalogue document vectors. Request texts and query vectors never enter this store. */
final class PreviewVectorStore {
    private static final Logger log = LoggerFactory.getLogger(PreviewVectorStore.class);
    private static final int DIMENSIONS = 384;
    private static final int ROW_BYTES = DIMENSIONS * Float.BYTES;
    private static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    private static final int SHA_BYTES = 32;
    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("\\S+");
    private static final int DESCRIPTION_LEAD_WORDS = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;

    PreviewVectorStore(Path directory) {
        this.directory = directory;
    }

    /** Key and ordered model inputs are built together; vector rows use this exact code order. */
    record Manifest(String key, Map<String, String> documents) {
        private long fileBytes() { return SHA_BYTES * 2L + (long) ROW_BYTES * documents.size(); }
    }

    static Manifest manifest(List<RootCatalogueSnapshot> snapshots, String modelKey) {
        var first = snapshots.getFirst();
        var source = first.catalogueProvenance();
        var spec = first.catalogueDefinition() == null ? CatalogueDefinition.standard().spec() : first.catalogueDefinition();
        var digest = sha256();
        // Frame format, model/tokenizer identity, exact workbook/overlay generation and DSL definition.
        digest.update(JSON.writeValueAsBytes(List.of("bilingual-preview-reference-v2:titles-and-parallel-description-leads:fp32be:384", modelKey,
                source.id(), source.workbook(), source.overlay(), spec)));
        var documents = new TreeMap<String, String>();
        for (var snapshot : snapshots) {
            if (!source.equals(snapshot.catalogueProvenance()) || !first.source().equals(snapshot.source())
                    || !java.util.Objects.equals(first.catalogueDefinition(), snapshot.catalogueDefinition()))
                throw new IllegalArgumentException("Mixed catalogue inputs for semantic preview");
            for (var node : snapshot.nodes()) {
                if (documents.putIfAbsent(node.code(), documentText(node)) != null)
                    throw new IllegalArgumentException("Duplicate catalogue preview document code");
            }
        }
        documents.forEach((code, text) -> {
            frame(digest, code);
            frame(digest, text);
        });
        return new Manifest(HexFormat.of().formatHex(digest.digest()), Collections.unmodifiableMap(documents));
    }

    // Short-context multilingual encoders should see both catalogue titles and both introductory descriptions.
    // Retain the full tails for models with longer context windows.
    private static String documentText(RootCatalogueSnapshot.Node node) {
        if ((node.nameDe() == null || node.nameDe().isBlank()) && (node.descriptionDe() == null || node.descriptionDe().isBlank()))
            return NodeEmbeddingText.globalReferenceText(node.detached());
        var parts = new java.util.StringJoiner("\n");
        if (!node.nameEn().isBlank()) parts.add(node.nameEn() + ".");
        add(parts, node.nameDe());
        String en = node.descriptionEn() == null ? "" : node.descriptionEn().strip();
        String de = node.descriptionDe() == null ? "" : node.descriptionDe().strip();
        if (en.isEmpty() || de.isEmpty()) {
            add(parts, en);
            add(parts, de);
        } else {
            int enLead = leadEnd(en);
            int deLead = leadEnd(de);
            add(parts, en.substring(0, enLead));
            add(parts, de.substring(0, deLead));
            add(parts, en.substring(enLead));
            add(parts, de.substring(deLead));
        }
        return parts.toString();
    }

    private static void add(java.util.StringJoiner parts, String text) {
        if (text != null && !text.isBlank()) parts.add(text.strip());
    }

    private static int leadEnd(String text) {
        var words = WORD.matcher(text);
        for (int count = 0; count < DESCRIPTION_LEAD_WORDS; count++) {
            if (!words.find()) return text.length();
        }
        return words.end();
    }

    Optional<Map<String, float[]>> read(Manifest manifest) {
        if (directory == null || manifest.fileBytes() > MAX_FILE_BYTES) return Optional.empty();
        Path file = slot(manifest, ".bin");
        try (var channel = FileChannel.open(file, java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var in = new DataInputStream(new BufferedInputStream(java.nio.channels.Channels.newInputStream(channel)))) {
            if (channel.size() != manifest.fileBytes()) return Optional.empty();
            byte[] key = in.readNBytes(SHA_BYTES);
            if (!MessageDigest.isEqual(key, HexFormat.of().parseHex(manifest.key()))) return Optional.empty();
            var digest = sha256();
            digest.update(key);
            var bytes = ByteBuffer.allocate(ROW_BYTES);
            var vectors = new LinkedHashMap<String, float[]>();
            for (String code : manifest.documents().keySet()) {
                in.readFully(bytes.array());
                digest.update(bytes.array());
                var vector = new float[DIMENSIONS];
                bytes.asFloatBuffer().get(vector);
                if (!valid(vector)) return Optional.empty();
                vectors.put(code, vector);
            }
            if (!MessageDigest.isEqual(digest.digest(), in.readNBytes(SHA_BYTES)) || in.read() != -1)
                return Optional.empty();
            return Optional.of(Map.copyOf(vectors));
        } catch (NoSuchFileException absent) {
            return Optional.empty();
        } catch (IOException | SecurityException unavailable) {
            log.warn("Catalogue vector cache read unavailable ({})", unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    void write(Manifest manifest, Map<String, float[]> vectors) {
        if (directory == null || manifest.fileBytes() > MAX_FILE_BYTES || !vectors.keySet().equals(manifest.documents().keySet())) return;
        try {
            Files.createDirectories(directory);
            // A stable staging name bounds remnants of crashed writers; OS locks release on process exit.
            try (var channel = FileChannel.open(slot(manifest, ".lock"), CREATE, WRITE, LinkOption.NOFOLLOW_LINKS);
                 var lock = channel.tryLock()) {
                if (lock == null) return;
                Path staging = slot(manifest, ".part");
                try {
                    writePayload(staging, manifest, vectors);
                    Files.move(staging, slot(manifest, ".bin"), ATOMIC_MOVE, REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(staging);
                }
            }
        } catch (OverlappingFileLockException busy) {
            // Another publisher owns this slot in this JVM. The in-memory generation remains usable.
        } catch (IOException | SecurityException unavailable) {
            log.warn("Catalogue vector cache write unavailable ({})", unavailable.getClass().getSimpleName());
        }
    }

    private static void writePayload(Path staging, Manifest manifest, Map<String, float[]> vectors) throws IOException {
        byte[] key = HexFormat.of().parseHex(manifest.key());
        var digest = sha256();
        digest.update(key);
        var row = ByteBuffer.allocate(ROW_BYTES);
        try (var out = new BufferedOutputStream(Files.newOutputStream(staging, CREATE, TRUNCATE_EXISTING, WRITE, LinkOption.NOFOLLOW_LINKS))) {
            out.write(key);
            for (String code : manifest.documents().keySet()) {
                var vector = vectors.get(code);
                if (!valid(vector)) throw new IOException("Invalid embedding vector");
                row.asFloatBuffer().put(vector);
                digest.update(row.array());
                out.write(row.array());
            }
            out.write(digest.digest());
        }
    }

    private Path slot(Manifest manifest, String suffix) {
        int slot = Character.digit(manifest.key().charAt(0), 16) % 2;
        return directory.resolve("taxonomy-preview-v1-" + slot + suffix);
    }

    static boolean valid(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) return false;
        double squaredLength = 0;
        for (float v : vector) squaredLength += (double) v * v;
        return squaredLength >= Float.MIN_NORMAL && squaredLength <= Float.MAX_VALUE;
    }

    private static void frame(MessageDigest digest, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
