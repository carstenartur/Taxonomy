package com.taxonomy.templates;

import io.swagger.v3.oas.annotations.media.SchemaProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.headers.Header;

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
@Tag(name = "Document templates", description = "ADMIN-only versioned DOTX packages and inspection")
@RequestMapping("/api/admin/document-templates")
@ApiResponse(responseCode = "500", description = "Template storage operation failed",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
@ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
@ApiResponse(responseCode = "403", description = "Administrator role or CSRF protection required", content = @Content)
public class DocumentTemplateAdminController {

    private final DocumentTemplateService templates;

    public DocumentTemplateAdminController(DocumentTemplateService templates) {
        this.templates = templates;
    }

    @Operation(summary = "List versioned document templates",
            description = "ADMIN only. Lists available template descriptors and current Git heads without downloading package bytes.")
    @ApiResponse(responseCode = "200", description = "Available template descriptors")
    @GetMapping
    public List<TemplateDescriptor> list() throws IOException {
        return templates.list();
    }

    /**
     * Accepts the DOTX as the raw request body. This keeps the same bounded package
     * import path as WebDAV and avoids a second multipart buffering limit.
     */
    @Operation(summary = "Upload a raw DOTX document template",
            description = "ADMIN only. Sends the DOTX archive as the raw request body, not multipart/form-data. "
                    + "The bounded archive and active content are validated before committing the versioned package. "
                    + "Use the current head ETag as If-Match when replacing a template; new templates may omit it. "
                    + "The returned ETag identifies the saved revision. Does not execute a report or macros.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Non-empty raw DOTX ZIP archive",
                    content = {
                            @Content(mediaType = OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                                    schema = @Schema(type = "string", format = "binary")),
                            @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                    schema = @Schema(type = "string", format = "binary"))
                    }))
    @ApiResponse(responseCode = "201", description = "Validated template revision saved",
            headers = @Header(name = "ETag", description = "Quoted Git commit of the saved revision",
                    schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Invalid identifier, archive or package content",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "412", description = "Template head changed or required current head was not supplied",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @PutMapping(
            value = "/{templateId}",
            consumes = {
                    OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                    MediaType.APPLICATION_OCTET_STREAM_VALUE
            })
    public ResponseEntity<TemplateDescriptor> upload(
            @Parameter(description = "Registered template identifier, not a filesystem path") @PathVariable String templateId,
            @Parameter(description = "Human-readable template display name") @RequestParam String displayName,
            @Parameter(description = "Current quoted head ETag; required for an existing template, optional for initial creation") @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String expectedHead,
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

    @Operation(summary = "Download a document template revision",
            description = "ADMIN only. Downloads the current DOTX when revision is absent or blank; otherwise downloads the exact retained Git revision. The response is a binary attachment with ETag and Last-Modified headers and private cache revalidation.")
    @ApiResponse(responseCode = "404", description = "Template, revision or package part not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "400", description = "Invalid identifier, revision or package path",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "200", description = "DOTX binary attachment",
            content = @Content(mediaType = OoxmlTemplatePackageCodec.DOTX_MEDIA_TYPE,
                    schema = @Schema(type = "string", format = "binary")),
            headers = {
                    @Header(name = "ETag", description = "Quoted revision identifier", schema = @Schema(type = "string")),
                    @Header(name = "Last-Modified", description = "Revision timestamp as an HTTP date", schema = @Schema(type = "string")),
                    @Header(name = "Content-Disposition", description = "Attachment filename", schema = @Schema(type = "string"))
            })
    @GetMapping("/{templateId}/download")
    public ResponseEntity<byte[]> download(
            @Parameter(description = "Registered template identifier, not a filesystem path") @PathVariable String templateId,
            @Parameter(description = "Exact retained Git revision; absent or blank selects the current head") @RequestParam(required = false) String revision) throws IOException {
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

    @Operation(summary = "Read template revision history",
            description = "ADMIN only. Returns retained Git revisions for the selected template; this is a metadata read without restoring an older version.")
    @ApiResponse(responseCode = "404", description = "Template, revision or package part not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "400", description = "Invalid identifier, revision or package path",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "200", description = "Requested template metadata or bounded package evidence")
    @GetMapping("/{templateId}/history")
    public List<TemplateRevision> history(@Parameter(description = "Registered template identifier, not a filesystem path") @PathVariable String templateId)
            throws IOException {
        return templates.history(templateId);
    }

    @Operation(summary = "Compare two template revisions",
            description = "ADMIN only. Compares the exact from and to Git revisions of one template, including added, removed and changed package parts. Does not modify the template or generate a report.")
    @ApiResponse(responseCode = "404", description = "Template, revision or package part not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "400", description = "Invalid identifier, revision or package path",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "200", description = "Requested template metadata or bounded package evidence")
    @GetMapping("/{templateId}/diff")
    public TemplateDiff diff(
            @Parameter(description = "Registered template identifier, not a filesystem path") @PathVariable String templateId,
            @Parameter(description = "Exact Git revision used as comparison source") @RequestParam String from,
            @Parameter(description = "Exact Git revision used as comparison target") @RequestParam String to) throws IOException {
        return templates.diff(templateId, from, to);
    }

    @Operation(summary = "Inspect a retained template package part",
            description = "ADMIN only. Reads the bounded part preview at an exact template revision and package-relative path. The path identifies a package entry, not a server filesystem path.")
    @ApiResponse(responseCode = "404", description = "Template, revision or package part not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "400", description = "Invalid identifier, revision or package path",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "200", description = "Requested template metadata or bounded package evidence")
    @GetMapping("/{templateId}/part")
    public TemplatePartView part(
            @Parameter(description = "Registered template identifier, not a filesystem path") @PathVariable String templateId,
            @Parameter(description = "Exact retained Git revision to read or restore") @RequestParam String revision,
            @Parameter(description = "Package-relative part path, for example word/document.xml") @RequestParam String path) throws IOException {
        return templates.readPart(templateId, revision, path);
    }

    @Operation(summary = "Restore a historical document template",
            description = "ADMIN only. Creates a new current revision from an existing historical DOTX package. Does not erase intervening history. If-Match must identify the current head; a concurrent change is rejected. Returns the new head ETag.")
    @ApiResponse(responseCode = "404", description = "Template, revision or package part not found",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "400", description = "Invalid identifier, revision or package path",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @ApiResponse(responseCode = "200", description = "New current template revision",
            headers = @Header(name = "ETag", description = "Quoted restored head", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "412", description = "Missing or stale current template head",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(type = "object", requiredProperties = "error"),
                    schemaProperties = @SchemaProperty(name = "error",
                            schema = @Schema(type = "string", description = "Reason the template operation was rejected"))))
    @PostMapping("/{templateId}/restore")
    public ResponseEntity<TemplateDescriptor> restore(
            @Parameter(description = "Registered template identifier, not a filesystem path") @PathVariable String templateId,
            @Parameter(description = "Exact retained Git revision to read or restore") @RequestParam String revision,
            @Parameter(description = "Required current quoted head ETag; restore creates a new commit",
                    required = true) @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String expectedHead,
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
