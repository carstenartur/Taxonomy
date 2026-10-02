package com.taxonomy.templates;

import com.taxonomy.backup.BackupCheckpoint;
import com.taxonomy.backup.BackupLimits;
import com.taxonomy.backup.PortableGitPaths;
import com.taxonomy.templates.DocumentTemplateGitRepository.CapturedFile;
import com.taxonomy.templates.DocumentTemplateGitRepository.CapturedTree;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Complete preflight of one immutable template tree, with bounded metadata and streamed checksums. */
final class TemplateBackupCapture {
    private static final int MAX_MANIFEST_BYTES = 1_048_576;
    private static final int MAX_COMMIT_BYTES = 1_048_576;
    private static final int MAX_TREE_BYTES = 4 * 1_048_576;
    private static final int MAX_METADATA_BYTES = 16 * 1_048_576;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private TemplateBackupCapture() { }

    static CapturedTree capture(Repository repository, ObjectId head, BackupCheckpoint checkpoint) throws IOException {
        if (head == null) return new CapturedTree(null, List.of());
        var files = new ArrayList<CapturedFile>();
        var packages = new TreeMap<String, PackageFiles>();
        try (var reader = repository.newObjectReader(); var tree = new TreeWalk(reader)) {
            long metadataBytes = checkMetadata(reader, head, Constants.OBJ_COMMIT, MAX_COMMIT_BYTES, 0, checkpoint);
            // A size lookup only hints the type; parseCommit would peel an unchecked tag target.
            byte[] commit = reader.open(head, Constants.OBJ_COMMIT).getBytes(MAX_COMMIT_BYTES);
            var root = RevCommit.parse(commit).getTree();
            metadataBytes = checkMetadata(reader, root, Constants.OBJ_TREE, MAX_TREE_BYTES, metadataBytes, checkpoint);
            tree.addTree(root);
            // Visit directories explicitly so every subtree is bounded before JGit loads it.
            tree.setRecursive(false);
            while (tree.next()) {
                checkpoint.check();
                String path = PortableGitPaths.requireFile(tree.getPathString());
                if (!Arrays.equals(tree.getRawPath(), path.getBytes(StandardCharsets.UTF_8))) {
                    throw new IOException("Template path is not canonical UTF-8");
                }
                if (tree.isSubtree()) {
                    metadataBytes = checkMetadata(reader, tree.getObjectId(0), Constants.OBJ_TREE, MAX_TREE_BYTES, metadataBytes, checkpoint);
                    tree.enterSubtree();
                    continue;
                }
                if (files.size() >= BackupLimits.MAX_ITEMS - 1) throw new IOException("Template capture file limit exceeded");
                if (!FileMode.REGULAR_FILE.equals(tree.getFileMode(0))) {
                    throw new IOException("Template capture requires regular files");
                }
                String[] parts = path.split("/", 4);
                if (parts.length < 3 || !parts[0].equals("templates")) throw new IOException("Unclassified template repository file");
                DocumentTemplateGitRepository.validateTemplateId(parts[1]);
                var group = packages.computeIfAbsent(parts[1], ignored -> new PackageFiles());
                var loader = repository.open(tree.getObjectId(0), Constants.OBJ_BLOB);
                var file = new CapturedFile(path, tree.getObjectId(0).name(), loader.getSize());
                if (parts.length == 3 && parts[2].equals("template.json")) {
                    if (file.length() > MAX_MANIFEST_BYTES || group.manifest != null) throw new IOException("Invalid template manifest size or duplicate");
                    group.manifest = file;
                } else if (parts.length == 4 && parts[2].equals("package")) {
                    OoxmlTemplatePackageCodec.validatePartPath(parts[3]);
                    if (file.length() > OoxmlTemplatePackageCodec.MAX_PART_BYTES
                            || group.parts.size() >= OoxmlTemplatePackageCodec.MAX_PARTS
                            || file.length() > OoxmlTemplatePackageCodec.MAX_UNCOMPRESSED_BYTES - group.length) {
                        throw new IOException("Template package capture limit exceeded");
                    }
                    if (group.parts.putIfAbsent(parts[3], file) != null) throw new IOException("Duplicate template package part");
                    group.length += file.length();
                } else {
                    throw new IOException("Unclassified template repository file");
                }
                files.add(file);
            }
        }
        PortableGitPaths.requireTree(files.stream().map(CapturedFile::path).toList());
        for (var entry : packages.entrySet()) {
            checkpoint.check();
            verifyPackage(repository, entry.getKey(), entry.getValue(), checkpoint);
        }
        return new CapturedTree(head.name(), files);
    }

    private static long checkMetadata(ObjectReader reader, AnyObjectId id, int type, int maximum,
                                      long consumed, BackupCheckpoint checkpoint) throws IOException {
        checkpoint.check();
        long size = reader.getObjectSize(id, type);
        if (size < 0 || size > maximum || size > MAX_METADATA_BYTES - consumed) {
            throw new IOException("Template capture metadata limit exceeded");
        }
        return consumed + size;
    }

    private static void verifyPackage(Repository repository, String templateId, PackageFiles files,
                                      BackupCheckpoint checkpoint) throws IOException {
        if (files.manifest == null) throw new IOException("Template package manifest is missing");
        TemplateManifest manifest;
        try {
            byte[] content = repository.open(ObjectId.fromString(files.manifest.objectId()), Constants.OBJ_BLOB)
                    .getBytes(MAX_MANIFEST_BYTES);
            manifest = JSON.readValue(content, TemplateManifest.class);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid stored template manifest", invalid);
        }
        if (manifest == null) throw new IOException("Template package manifest is null");
        DocumentTemplateGitRepository.validateStoredManifest(templateId, manifest, null);
        if (manifest.partCount() != files.parts.size() || manifest.uncompressedSize() != files.length) {
            throw new IOException("Stored template package statistics do not match its manifest");
        }
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8_192];
        for (var part : files.parts.entrySet()) {
            checkpoint.check();
            digest.update(part.getKey().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            long length = 0;
            try (var input = repository.open(ObjectId.fromString(part.getValue().objectId()), Constants.OBJ_BLOB).openStream()) {
                for (int read; (read = input.read(buffer)) != -1;) {
                    checkpoint.check();
                    if (read > part.getValue().length() - length) throw new IOException("Template blob exceeds its declared size");
                    digest.update(buffer, 0, read);
                    length += read;
                }
            }
            if (length != part.getValue().length()) throw new IOException("Truncated template blob");
            digest.update((byte) 0);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(manifest.packageSha256())) {
            throw new IOException("Stored template package checksum does not match its manifest");
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class PackageFiles {
        CapturedFile manifest;
        final TreeMap<String, CapturedFile> parts = new TreeMap<>();
        long length;
    }
}
