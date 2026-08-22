package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDiff;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateManifest;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateRevision;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateSnapshot;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Application boundary shared by the web UI and the virtual WebDAV projection.
 */
@Service
public class DocumentTemplateService {

    private final DocumentTemplateGitRepository repository;
    private final OoxmlTemplatePackageCodec codec;

    public DocumentTemplateService(
            DocumentTemplateGitRepository repository,
            OoxmlTemplatePackageCodec codec) {
        this.repository = repository;
        this.codec = codec;
    }

    public List<TemplateDescriptor> list() throws IOException {
        return repository.list();
    }

    /**
     * Import a full DOTX and commit its canonical unzipped package atomically.
     */
    public TemplateDescriptor upload(
            String templateId,
            String displayName,
            InputStream dotx,
            String expectedHead,
            String actor,
            String message) throws IOException {

        DocumentTemplateGitRepository.validateTemplateId(templateId);
        String normalizedDisplayName = normalizeDisplayName(displayName, templateId);
        OoxmlTemplatePackageCodec.PackageData packageData = codec.unpack(dotx);
        String observedHead = repository.headCommit();
        String effectiveExpectedHead = expectedHead == null || expectedHead.isBlank()
                ? observedHead
                : stripEtag(expectedHead);

        String now = Instant.now().toString();
        String user = actor == null || actor.isBlank() ? "taxonomy" : actor;
        TemplateManifest manifest = new TemplateManifest(
                1,
                templateId,
                normalizedDisplayName,
                templateId + ".dotx",
                OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                now,
                user,
                packageData.uncompressedSize(),
                packageData.parts().size(),
                packageData.sha256());

        TemplateSnapshot saved = repository.commit(
                manifest,
                packageData.parts(),
                effectiveExpectedHead,
                user,
                message);
        return descriptor(saved);
    }

    public TemplateFile downloadCurrent(String templateId) throws IOException {
        return toTemplateFile(repository.readCurrent(templateId));
    }

    public TemplateFile download(String templateId, String revision) throws IOException {
        return toTemplateFile(repository.read(templateId, revision));
    }

    public List<TemplateRevision> history(String templateId) throws IOException {
        return repository.history(templateId);
    }

    public TemplateDiff diff(
            String templateId,
            String fromRevision,
            String toRevision) throws IOException {
        return repository.diff(templateId, fromRevision, toRevision);
    }

    public TemplatePartView readPart(
            String templateId,
            String revision,
            String path) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/")
                || path.contains("\\") || path.contains("../")) {
            throw new IllegalArgumentException("Invalid OOXML part path");
        }
        TemplateSnapshot snapshot = repository.read(templateId, revision);
        byte[] content = snapshot.parts().get(path);
        if (content == null) {
            throw new DocumentTemplateGitRepository.TemplateNotFoundException(
                    templateId + "/" + path, revision);
        }
        boolean text = path.endsWith(".xml") || path.endsWith(".rels")
                || path.endsWith(".txt") || path.endsWith(".json");
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
            String expectedHead,
            String actor) throws IOException {
        TemplateSnapshot historical = repository.read(templateId, revision);
        OoxmlTemplatePackageCodec.PackageData packageData = codec.unpack(
                new ByteArrayInputStream(codec.pack(historical.parts())));
        String observedHead = repository.headCommit();
        String effectiveExpected = expectedHead == null || expectedHead.isBlank()
                ? observedHead
                : stripEtag(expectedHead);
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

    public String headCommit() throws IOException {
        return repository.headCommit();
    }

    private TemplateFile toTemplateFile(TemplateSnapshot snapshot) throws IOException {
        byte[] dotx = codec.pack(snapshot.parts());
        return new TemplateFile(
                snapshot.manifest(),
                snapshot.commitId(),
                dotx,
                parseInstant(snapshot.manifest().updatedAt()));
    }

    private static TemplateDescriptor descriptor(TemplateSnapshot snapshot) {
        TemplateManifest manifest = snapshot.manifest();
        return new TemplateDescriptor(
                manifest.templateId(),
                manifest.displayName(),
                manifest.fileName(),
                snapshot.commitId(),
                manifest.updatedAt(),
                manifest.updatedBy(),
                manifest.uncompressedSize(),
                manifest.partCount(),
                manifest.packageSha256());
    }

    static String stripEtag(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.startsWith("W/")) {
            stripped = stripped.substring(2).strip();
        }
        if (stripped.length() >= 2
                && stripped.startsWith("\"")
                && stripped.endsWith("\"")) {
            stripped = stripped.substring(1, stripped.length() - 1);
        }
        return stripped;
    }

    private static String normalizeDisplayName(String displayName, String templateId) {
        String normalized = displayName == null || displayName.isBlank()
                ? templateId
                : displayName.strip();
        normalized = normalized.replaceAll("[\\r\\n\\t]", " ");
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                    "Template display name must not exceed 160 characters");
        }
        return normalized;
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

        public String etag() {
            return "\"" + commitId + "\"";
        }
    }

    public record TemplatePartView(
            String path,
            long size,
            String mediaType,
            String textContent) {
    }
}
