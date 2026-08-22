package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDescriptor;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateDiff;
import com.taxonomy.templates.DocumentTemplateGitRepository.TemplateRevision;
import com.taxonomy.templates.DocumentTemplateService.TemplateFile;
import com.taxonomy.templates.DocumentTemplateService.TemplatePartView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.security.Principal;
import java.util.List;

/**
 * Administrative upload/download, history and package-inspection API.
 */
@RestController
@RequestMapping("/api/admin/document-templates")
public class DocumentTemplateAdminController {

    private final DocumentTemplateService templates;

    public DocumentTemplateAdminController(DocumentTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    public List<TemplateDescriptor> list() throws IOException {
        return templates.list();
    }

    /**
     * Accepts the DOTX as the raw request body. This keeps the same bounded package
     * import path as WebDAV and avoids a second multipart buffering limit.
     */
    @PutMapping(
            value = "/{templateId}",
            consumes = {
                    OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                    MediaType.APPLICATION_OCTET_STREAM_VALUE
            })
    public ResponseEntity<TemplateDescriptor> upload(
            @PathVariable String templateId,
            @RequestParam String displayName,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String expectedHead,
            HttpServletRequest request,
            Principal principal) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength == 0) {
            throw new IllegalArgumentException("Choose a non-empty DOTX file");
        }
        if (contentLength > OoxmlTemplatePackageCodec.MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("DOTX archive exceeds the permitted size");
        }
        TemplateDescriptor saved = templates.upload(
                templateId,
                displayName,
                request.getInputStream(),
                expectedHead,
                principal.getName(),
                "Upload document template " + templateId);
        return ResponseEntity.status(201)
                .eTag(etag(saved.headCommit()))
                .body(saved);
    }

    @GetMapping("/{templateId}/download")
    public ResponseEntity<byte[]> download(
            @PathVariable String templateId,
            @RequestParam(required = false) String revision) throws IOException {
        TemplateFile file = revision == null || revision.isBlank()
                ? templates.downloadCurrent(templateId)
                : templates.download(templateId, revision);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.manifest().mediaType()))
                .contentLength(file.content().length)
                .eTag(file.etag())
                .lastModified(file.lastModified().toEpochMilli())
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.manifest().fileName(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(file.content());
    }

    @GetMapping("/{templateId}/history")
    public List<TemplateRevision> history(@PathVariable String templateId)
            throws IOException {
        return templates.history(templateId);
    }

    @GetMapping("/{templateId}/diff")
    public TemplateDiff diff(
            @PathVariable String templateId,
            @RequestParam String from,
            @RequestParam String to) throws IOException {
        return templates.diff(templateId, from, to);
    }

    @GetMapping("/{templateId}/part")
    public TemplatePartView part(
            @PathVariable String templateId,
            @RequestParam String revision,
            @RequestParam String path) throws IOException {
        return templates.readPart(templateId, revision, path);
    }

    @PostMapping("/{templateId}/restore")
    public ResponseEntity<TemplateDescriptor> restore(
            @PathVariable String templateId,
            @RequestParam String revision,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String expectedHead,
            Principal principal) throws IOException {
        TemplateDescriptor restored = templates.restore(
                templateId,
                revision,
                expectedHead,
                principal.getName());
        return ResponseEntity.ok()
                .eTag(etag(restored.headCommit()))
                .body(restored);
    }

    private static String etag(String commitId) {
        return "\"" + commitId + "\"";
    }
}
