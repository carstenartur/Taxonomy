package com.taxonomy.backup.restore;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.taxonomy.backup.*;
import com.taxonomy.backup.archive.VerifiedBackup;
import com.taxonomy.exchange.backup.PortableRows;
import java.io.*;
import java.util.*;

/** Bounded owner DTO reader. Types are supplied by compiled owner adapters, never archive fields. */
public final class RestoreArchiveRecords {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100)
                    .maxStringLength(PortableRows.MAX_RECORD_BYTES).maxNumberLength(100).build())
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).addModule(canonicalUuids()).build();
    private RestoreArchiveRecords() { }
    private static com.fasterxml.jackson.databind.module.SimpleModule canonicalUuids() {
        var module=new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(UUID.class,new JsonDeserializer<UUID>() {
            @Override public UUID deserialize(JsonParser parser,DeserializationContext context)throws IOException {
                if(!parser.hasToken(JsonToken.VALUE_STRING))throw JsonMappingException.from(parser,"Invalid canonical UUID");
                String value=parser.getText();
                if(!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw JsonMappingException.from(parser,"Invalid canonical UUID");
                return UUID.fromString(value);
            }
        });return module;
    }
    @FunctionalInterface public interface Visitor<T extends Record> { void accept(T value) throws IOException; }

    /** A fixed owner-provided discriminator table is the only authority for selecting record types. */
    public static void readVariants(VerifiedBackup archive, BackupComponentId owner, String path, String kind,
            Map<String, Class<? extends Record>> variants, int maximumRows, BackupCheckpoint checkpoint,
            Visitor<Record> visitor) throws IOException {
        var approved = variants(variants); requireOwner(archive, owner, path);
        try (var input = archive.openEntry(path, checkpoint)) {
            readVariants(input, kind, archive.manifest().request().profile(), approved, maximumRows, checkpoint, visitor);
        }
    }
    static void readVariants(InputStream input, String kind, BackupProfile profile,
            Map<String, Class<? extends Record>> variants, int maximumRows, BackupCheckpoint checkpoint,
            Visitor<Record> visitor) throws IOException {
        var approved = variants(variants);
        readRows(input, kind, profile, bytes -> {
            var value = JSON.readTree(bytes);
            var discriminator = value == null || !value.isObject() ? null : value.get("recordType");
            if (discriminator == null || !discriminator.isTextual()) throw invalid();
            var type = approved.get(discriminator.textValue());
            if (type == null) throw invalid();
            return JSON.treeToValue(value, type);
        }, maximumRows, checkpoint, visitor);
    }
    private static Map<String, Class<? extends Record>> variants(Map<String, Class<? extends Record>> values) {
        if (values == null || values.isEmpty() || values.size() > 16) throw new IllegalArgumentException("Invalid portable record types");
        var approved = Map.copyOf(values);
        approved.forEach((kind, type) -> {
            if (!kind.matches("[a-z][a-z0-9-]{0,63}") || !type.isRecord()) throw new IllegalArgumentException("Invalid portable record types");
        });
        return approved;
    }

    public static <T extends Record> void read(VerifiedBackup archive, BackupComponentId owner, String path,
            String kind, Class<T> type, int maximumRows, BackupCheckpoint checkpoint, Visitor<T> visitor) throws IOException {
        requireOwner(archive, owner, path);
        try (var input = archive.openEntry(path, checkpoint)) {
            read(input, kind, archive.manifest().request().profile(), type, maximumRows, checkpoint, visitor);
        }
    }

    /** Standalone owner documents use the same strict compiled DTO contract as NDJSON records. */
    public static <T extends Record>T document(VerifiedBackup archive,BackupComponentId owner,String path,
                                               Class<T> type,BackupCheckpoint checkpoint)throws IOException {
        if(!type.isRecord())throw new IllegalArgumentException("Invalid typed restore document");
        requireOwner(archive,owner,path);
        try(var input=archive.openEntry(path, checkpoint)) {
            checkpoint.check();byte[] bytes=input.readNBytes(BackupLimits.MAX_MANIFEST_BYTES+1);checkpoint.check();
            if(bytes.length>BackupLimits.MAX_MANIFEST_BYTES)throw invalid();
            T value=JSON.readValue(bytes,type);if(value==null)throw invalid();return value;
        } catch(InterruptedIOException cancelled){throw cancelled();}
        catch(JsonProcessingException|IllegalArgumentException malformed){throw invalid();}
    }

    public static boolean declares(VerifiedBackup archive, BackupComponentId owner, String path) {
        return archive.manifest().components().stream().anyMatch(component -> component.id().equals(owner)
                && component.entryPaths().contains(path));
    }
    private static void requireOwner(VerifiedBackup archive, BackupComponentId owner, String path) throws IOException {
        if (!declares(archive, owner, path)) throw new IOException("Portable restore resource is not declared by its owner");
    }

    public static Optional<SourceDatabaseMetadata> sourceDatabase(VerifiedBackup archive, BackupCheckpoint checkpoint) throws IOException {
        requireOwner(archive, new BackupComponentId("capture-proof"), "verification/capture.json");
        try (var input = archive.openEntry("verification/capture.json", checkpoint)) {
            checkpoint.check(); byte[] bytes = input.readNBytes(BackupLimits.MAX_MANIFEST_BYTES + 1); checkpoint.check();
            if (bytes.length > BackupLimits.MAX_MANIFEST_BYTES) throw invalid();
            var proof = JSON.readTree(bytes); var source = proof == null ? null : proof.get("sourceDatabase");
            boolean declared = archive.manifest().requiredFeatures().contains(SourceDatabaseMetadata.REQUIRED_FEATURE);
            if (source == null) { if (declared) throw invalid(); return Optional.empty(); }
            if (!declared || !source.isObject() || source.size() != 2) throw invalid();
            return Optional.of(JSON.treeToValue(source, SourceDatabaseMetadata.class));
        } catch (InterruptedIOException cancelled) { throw cancelled(); }
        catch (RuntimeException | IOException malformed) { throw invalid(); }
    }

    /** Package boundary used by tests; production access always requires VerifiedBackup and declared owner. */
    static <T extends Record> void read(InputStream input, String kind, BackupProfile profile, Class<T> type,
            int maximumRows, BackupCheckpoint checkpoint, Visitor<T> visitor) throws IOException {
        if (type == null || !type.isRecord()) throw new IllegalArgumentException("Invalid typed restore reader");
        readRows(input, kind, profile, bytes -> JSON.readValue(bytes, type), maximumRows, checkpoint, visitor);
    }
    @FunctionalInterface private interface Decoder<T extends Record> { T decode(byte[] bytes) throws IOException; }
    private static <T extends Record> void readRows(InputStream input, String kind, BackupProfile profile, Decoder<T> decoder,
            int maximumRows, BackupCheckpoint checkpoint, Visitor<T> visitor) throws IOException {
        if (maximumRows < 0 || maximumRows > 1_000_000
                || kind == null || !kind.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid typed restore reader");
        Objects.requireNonNull(profile); Objects.requireNonNull(checkpoint); Objects.requireNonNull(visitor);
        var line = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int rows = -1;
        try {
            for (;;) {
                checkpoint.check(); int count = input.read(buffer); checkpoint.check();
                if (count < 0) break;
                for (int offset = 0; offset < count; offset++) {
                    if (buffer[offset] != '\n') {
                        if (line.size() >= PortableRows.MAX_RECORD_BYTES) throw new IOException("Portable restore record size limit exceeded");
                        line.write(buffer[offset]); continue;
                    }
                    if (line.size() == 0) throw invalid();
                    byte[] bytes = line.toByteArray(); line.reset();
                    if (rows == -1) {
                        var header = JSON.readValue(bytes, PortableRows.Header.class);
                        if (header.schemaVersion() != 1 || !kind.equals(header.kind()) || profile != header.profile()) throw invalid();
                        rows = 0;
                    } else {
                        if (rows >= maximumRows) throw new IOException("Portable restore row limit exceeded");
                        T record = decoder.decode(bytes);
                        if (record == null) throw invalid();
                        checkpoint.check(); visitor.accept(record); rows++; checkpoint.check();
                    }
                }
            }
            if (line.size() != 0 || rows < 0) throw invalid();
        } catch (InterruptedIOException interrupted) { throw cancelled(); }
        catch (JsonProcessingException | IllegalArgumentException malformed) { throw invalid(); }
    }
    private static IOException invalid() { return new IOException("Invalid or unsupported portable restore dataset"); }
    private static InterruptedIOException cancelled() {
        Thread.currentThread().interrupt(); return new InterruptedIOException("Portable restore inspection was cancelled");
    }
}
