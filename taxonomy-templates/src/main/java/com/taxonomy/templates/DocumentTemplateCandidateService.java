package com.taxonomy.templates;

import com.taxonomy.templates.api.RevisionPinnedTemplateReportPreview;
import com.taxonomy.templates.api.TemplateConflictException;
import com.taxonomy.templates.api.TemplateContribution;
import com.taxonomy.templates.api.TemplateDescriptor;
import com.taxonomy.templates.api.TemplateFile;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
final class DocumentTemplateCandidateService {
    private final DocumentTemplateCandidateStore store;
    private final DocumentTemplateService templates;
    private final Map<String, TemplateContribution> contributions;

    DocumentTemplateCandidateService(DocumentTemplateCandidateStore store, DocumentTemplateService templates,
                                     List<TemplateContribution> contributions) {
        this.store = store;
        this.templates = templates;
        this.contributions = TemplateContributions.index(contributions);
    }

    record Candidate(String templateId, String version, String baseVersion, String displayName,
                     String originalSha256, String packageSha256, boolean ready) { }

    Candidate stage(String id, String displayName, String expectedHead, byte[] original, String actor) throws IOException {
        var family = contributions.get(id);
        if (family == null || !(family.preview() instanceof RevisionPinnedTemplateReportPreview preview)) {
            throw new IllegalArgumentException("Choose a template family with an exact-revision sample");
        }
        String base = expectedHead;
        if (base == null || !ObjectId.isId(base)) throw new IllegalArgumentException("Choose a complete active template revision");
        var current = templates.describeCurrent(id);
        if (!current.headCommit().equals(base)) throw new TemplateConflictException(base, current.headCommit());
        var checked = templates.checkedUpload(id, displayName, new ByteArrayInputStream(original), actor);
        var manifest = checked.manifest();
        var metadata = new DocumentTemplateCandidateStore.Metadata(id, manifest.displayName(), base,
                sha256(original), manifest.packageSha256());
        String version = store.save(metadata, original);
        byte[] generated;
        try {
            generated = preview.renderPreview(new TemplateFile(manifest, version, original, Instant.parse(manifest.updatedAt())));
        } catch (RuntimeException rejectedSample) {
            // Report the bounded outcome without exposing private template text.
            return candidate(metadata, version, false);
        }
        if (!isDocument(generated)) return candidate(metadata, version, false);
        store.certify(id, version, generated);
        return candidate(metadata, version, true);
    }

    Candidate describe(String id, String version) throws IOException {
        var metadata = store.metadata(id, version);
        return candidate(metadata, version, store.certified(id, version));
    }

    byte[] original(String id, String version) throws IOException {
        return store.original(id, version);
    }

    byte[] sample(String id, String version) throws IOException {
        return store.sample(id, version);
    }

    TemplateDescriptor activate(String id, String version, String actor) throws IOException {
        var metadata = store.metadata(id, version);
        if (!store.certified(id, version)) throw new IllegalArgumentException("Generate a successful sample before activation");
        byte[] original = store.original(id, version);
        if (!metadata.originalSha256().equals(sha256(original))) throw new IOException("Candidate verification failed");
        return templates.upload(id, metadata.displayName(), new ByteArrayInputStream(original), metadata.baseVersion(), actor,
                "Activate preflighted candidate " + version);
    }

    private static Candidate candidate(DocumentTemplateCandidateStore.Metadata metadata, String version, boolean ready) {
        return new Candidate(metadata.templateId(), version, metadata.baseVersion(), metadata.displayName(),
                metadata.originalSha256(), metadata.packageSha256(), ready);
    }

    private static boolean isDocument(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES) return false;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument(new ByteArrayInputStream(bytes))) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                    .equals(document.getPackagePart().getContentType()) && document.getDocument().getBody() != null;
        } catch (IOException | RuntimeException malformed) {
            return false;
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
