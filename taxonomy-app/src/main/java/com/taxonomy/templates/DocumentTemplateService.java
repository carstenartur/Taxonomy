package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.PartChange;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateConflictException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDiff;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateNotFoundException;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateRevision;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application boundary shared by the web UI and virtual WebDAV projection. */
@Service
public class DocumentTemplateService {

    private final DocumentTemplateGitRepository repository;
    private final OoxmlTemplatePackageCodec codec;
    private final OoxmlActiveContentValidator activeContent;
    private final DocumentTemplateMaterializationCache materializations;
    private final Map<String, DocumentTemplateContract> contracts;

    @Autowired
    public DocumentTemplateService(
            DocumentTemplateGitRepository repository,
            OoxmlTemplatePackageCodec codec,
            List<DocumentTemplateContract> contracts,
            OoxmlActiveContentValidator activeContent,
            DocumentTemplateMaterializationCache materializations) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.activeContent = Objects.requireNonNull(activeContent, "activeContent");
        this.materializations = Objects.requireNonNull(materializations, "materializations");
        this.contracts = indexContracts(contracts);
    }

    /** Focused-test constructor retaining the existing generic-template contract. */
    public DocumentTemplateService(
            DocumentTemplateGitRepository repository,
            OoxmlTemplatePackageCodec codec,
            List<DocumentTemplateContract> contracts) {
        this(repository, codec, contracts, new OoxmlActiveContentValidator(),
                new DocumentTemplateMaterializationCache());
    }

    public DocumentTemplateService(
            DocumentTemplateGitRepository repository,
            OoxmlTemplatePackageCodec codec) {
        this(repository, codec, List.of());
    }

    public List<TemplateDescriptor> list() throws IOException {
        return repository.list();
    }

    public boolean exists(String templateId) throws IOException {
        DocumentTemplateGitRepository.validateTemplateId(templateId);
        try {
            repository.readCurrent(templateId);
            return true;
        } catch (TemplateNotFoundException exception) {
            return false;
        }
    }

    /** Import one complete DOTX and atomically commit its canonical package tree. */
    public TemplateDescriptor upload(
            String templateId,
            String displayName,
            InputStream dotx,
            String expectedVersion,
            String actor,
            String message) throws IOException {
        DocumentTemplateGitRepository.validateTemplateId(templateId);
        String normalizedDisplayName = normalizeDisplayName(displayName, templateId);
        OoxmlTemplatePackageCodec.PackageData packageData = codec.unpack(dotx);
        validatePackage(templateId, packageData.parts());
        String effectiveExpectedVersion = resolveExpectedVersion(templateId, expectedVersion);
        String user = actor == null || actor.isBlank() ? "taxonomy" : actor;
        TemplateManifest manifest = new TemplateManifest(
                1,
                templateId,
                normalizedDisplayName,
                templateId + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                Instant.now().toString(),
                user,
                packageData.uncompressedSize(),
                packageData.parts().size(),
                packageData.sha256());
        TemplateSnapshot saved = repository.commit(
                manifest,
                packageData.parts(),
                effectiveExpectedVersion,
                user,
                message);
        return descriptor(saved);
    }

    /** Validated metadata without allocating a downloadable DOTX archive. */
    public TemplateDescriptor describeCurrent(String templateId) throws IOException {
        return describeSnapshot(repository.readCurrent(templateId));
    }

    /** Preserve the selected version; never fall back to the current template. */
    public TemplateDescriptor describe(String templateId, String revision) throws IOException {
        return describeSnapshot(repository.read(templateId, revision));
    }

    private TemplateDescriptor describeSnapshot(TemplateSnapshot snapshot) throws IOException {
        // Retain canonical OOXML and domain/privacy validation without ZIP creation.
        // Download/archive-size validation remains on the unchanged download path.
        codec.validatePackage(snapshot.parts());
        validateSnapshot(snapshot);
        return descriptor(snapshot);
    }

    public TemplateFile downloadCurrent(String templateId) throws IOException {
        return toTemplateFile(repository.readCurrent(templateId));
    }

    public TemplateFile downloadCurrentValidated(String templateId) throws IOException {
        return toTemplateFile(repository.readCurrent(templateId));
    }

    public TemplateFile download(String templateId, String revision) throws IOException {
        return toTemplateFile(repository.read(templateId, revision));
    }

    public List<TemplateRevision> history(String templateId) throws IOException {
        return repository.history(templateId);
    }

    public TemplateDiff diff(String templateId, String fromRevision, String toRevision)
            throws IOException {
        return repository.diff(templateId, fromRevision, toRevision);
    }

    public TemplatePartView readPart(
            String templateId,
            String revision,
            String path) throws IOException {
        OoxmlTemplatePackageCodec.validatePartPath(path);
        TemplateSnapshot snapshot = repository.read(templateId, revision);
        validateSnapshot(snapshot);
        byte[] content = snapshot.parts().get(path);
        if (content == null) {
            throw new TemplateNotFoundException(templateId + "/" + path, revision);
        }
        return partView(path, content);
    }

    /** Read each immutable snapshot once and compare only the requested package part. */
    public TemplatePartComparison comparePart(
            String templateId, String fromRevision, String toRevision, String path) throws IOException {
        OoxmlTemplatePackageCodec.validatePartPath(path);
        if (fromRevision == null || toRevision == null || !fromRevision.matches("[0-9a-f]{40}")
                || !toRevision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Two immutable template revisions are required");
        }
        // Resolve both snapshots before interpreting an absent part as added or deleted.
        TemplateSnapshot before = repository.read(templateId, fromRevision);
        TemplateSnapshot after = fromRevision.equals(toRevision)
                ? before : repository.read(templateId, toRevision);
        validateSnapshot(before);
        if (after != before) validateSnapshot(after);
        byte[] beforeContent = before.parts().get(path);
        byte[] afterContent = after == before ? beforeContent : after.parts().get(path);
        if (beforeContent == null && afterContent == null) {
            throw new TemplateNotFoundException(templateId + "/" + path, fromRevision);
        }
        PartChange change = beforeContent == null ? PartChange.ADDED
                : afterContent == null ? PartChange.DELETED
                : Arrays.equals(beforeContent, afterContent) ? null : PartChange.MODIFIED;
        TemplatePartView beforePart = beforeContent == null ? null : partView(path, beforeContent);
        TemplatePartView afterPart = after == before ? beforePart
                : afterContent == null ? null : partView(path, afterContent);
        return new TemplatePartComparison(change, beforePart, afterPart);
    }

    private static TemplatePartView partView(String path, byte[] content) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        boolean text = lower.endsWith(".xml") || lower.endsWith(".rels")
                || lower.endsWith(".txt") || lower.endsWith(".json");
        String rendered = text && content.length <= 1_048_576
                ? new String(content, StandardCharsets.UTF_8)
                : null;
        return new TemplatePartView(
                path,
                content.length,
                text ? "application/xml" : "application/octet-stream",
                rendered);
    }

    public TemplateDescriptor restore(
            String templateId,
            String revision,
            String expectedVersion,
            String actor) throws IOException {
        TemplateSnapshot historical = repository.read(templateId, revision);
        validateSnapshot(historical);
        OoxmlTemplatePackageCodec.PackageData packageData = codec.unpack(
                new ByteArrayInputStream(codec.pack(historical.parts())));
        validatePackage(templateId, packageData.parts());
        String effectiveExpected = resolveExpectedVersion(templateId, expectedVersion);
        String user = actor == null || actor.isBlank() ? "taxonomy" : actor;
        TemplateManifest restored = new TemplateManifest(
                historical.manifest().schemaVersion(),
                templateId,
                historical.manifest().displayName(),
                historical.manifest().fileName(),
                historical.manifest().mediaType(),
                Instant.now().toString(),
                user,
                packageData.uncompressedSize(),
                packageData.parts().size(),
                packageData.sha256());
        TemplateSnapshot saved = repository.commit(
                restored,
                packageData.parts(),
                effectiveExpected,
                user,
                "Restore document template " + templateId + " from " + revision);
        return descriptor(saved);
    }

    /** Repository-wide head retained only for diagnostics. */
    public String headCommit() throws IOException {
        return repository.headCommit();
    }

    /** Resolve a strong HTTP If-Match list against the current per-template version. */
    String resolveExpectedVersion(String templateId, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        TemplateSnapshot current;
        try {
            current = repository.readCurrent(templateId);
        } catch (TemplateNotFoundException missing) {
            throw new TemplateConflictException(value.strip(), null);
        }
        String currentCommit = current.commitId();
        for (String candidate : value.split(",")) {
            String tag = candidate.strip();
            if ("*".equals(tag)) {
                return currentCommit;
            }
            if (tag.regionMatches(true, 0, "W/", 0, 2)) {
                continue;
            }
            if (currentCommit.equalsIgnoreCase(stripQuotedEtag(tag))) {
                return currentCommit;
            }
        }
        throw new TemplateConflictException(value.strip(), currentCommit);
    }

    private TemplateFile toTemplateFile(TemplateSnapshot snapshot) throws IOException {
        validateSnapshot(snapshot);
        byte[] dotx = materializations.packed(
                snapshot.manifest().templateId(),
                snapshot.commitId(),
                () -> codec.pack(snapshot.parts()));
        return new TemplateFile(
                snapshot.manifest(),
                snapshot.commitId(),
                dotx,
                parseInstant(snapshot.manifest().updatedAt()));
    }

    private void validateSnapshot(TemplateSnapshot snapshot) throws IOException {
        materializations.validateOnce(
                snapshot.manifest().templateId(),
                snapshot.commitId(),
                () -> validatePackage(snapshot.manifest().templateId(), snapshot.parts()));
    }

    private void validatePackage(String templateId, Map<String, byte[]> packageParts) {
        validateReservedPackagePaths(packageParts);
        activeContent.validate(packageParts);
        DocumentTemplateContract contract = contracts.get(templateId);
        if (contract != null) {
            contract.validate(packageParts);
        }
    }

    private static void validateReservedPackagePaths(Map<String, byte[]> packageParts) {
        for (String path : packageParts.keySet()) {
            String normalized = path.toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals(DocumentTemplateGitRepository.MANIFEST_NAME)
                    || normalized.endsWith("/" + DocumentTemplateGitRepository.MANIFEST_NAME)) {
                throw new IllegalArgumentException(
                        "OOXML package part uses reserved Taxonomy metadata name: " + path);
            }
        }
    }

    private static Map<String, DocumentTemplateContract> indexContracts(
            List<DocumentTemplateContract> contracts) {
        LinkedHashMap<String, DocumentTemplateContract> indexed = new LinkedHashMap<>();
        for (DocumentTemplateContract contract :
                contracts == null ? List.<DocumentTemplateContract>of() : contracts) {
            Objects.requireNonNull(contract, "document template contract");
            DocumentTemplateGitRepository.validateTemplateId(contract.templateId());
            DocumentTemplateContract previous = indexed.putIfAbsent(
                    contract.templateId(), contract);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate document template contract for " + contract.templateId());
            }
        }
        return Map.copyOf(indexed);
    }

    private static TemplateDescriptor descriptor(TemplateSnapshot snapshot) {
        TemplateManifest manifest = snapshot.manifest();
        return new TemplateDescriptor(
                manifest.templateId(), manifest.displayName(), manifest.fileName(),
                snapshot.commitId(), manifest.updatedAt(), manifest.updatedBy(),
                manifest.uncompressedSize(), manifest.partCount(), manifest.packageSha256());
    }

    static String stripEtag(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.startsWith("W/")) {
            stripped = stripped.substring(2).strip();
        }
        return stripQuotedEtag(stripped);
    }

    private static String stripQuotedEtag(String value) {
        String stripped = value == null ? null : value.strip();
        if (stripped != null && stripped.length() >= 2
                && stripped.startsWith("\"") && stripped.endsWith("\"")) {
            return stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    static String normalizeDisplayName(String displayName, String templateId) {
        String normalized = displayName == null || displayName.isBlank()
                ? templateId : displayName.strip();
        normalized = normalized.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (!isValidXml10CodePoint(codePoint)) {
                throw new IllegalArgumentException(
                        "Template display name contains a character not permitted in XML 1.0");
            }
            offset += Character.charCount(codePoint);
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                    "Template display name must not exceed 160 characters");
        }
        return normalized;
    }

    static boolean isValidXml10CodePoint(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            return Instant.EPOCH;
        }
    }

    public record TemplateFile(
            TemplateManifest manifest,
            String commitId,
            byte[] content,
            Instant lastModified) {
        public TemplateFile {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(commitId, "commitId");
            content = content.clone();
        }
        @Override public byte[] content() { return content.clone(); }
        public String etag() { return "\"" + commitId + "\""; }
    }

    /** A null change denotes unchanged bytes, as in TemplateDiff's absent change entry. */
    public record TemplatePartComparison(
            PartChange change, TemplatePartView before, TemplatePartView after) { }

    public record TemplatePartView(
            String path,
            long size,
            String mediaType,
            String textContent) {
    }
}
