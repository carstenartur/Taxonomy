package com.taxonomy.backup.web;

import com.taxonomy.backup.runtime.BackupManifestCodec;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.headers.Header;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.backup.*;
import com.taxonomy.backup.jobs.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.util.*;

@Controller
@Tag(name = "Backup jobs")
@ConditionalOnProperty(name = "taxonomy.backup.enabled", havingValue = "true")
public final class BackupJobController {
    private final ObjectProvider<BackupJobService> services;
    private final BackupPrincipalResolver principals;
    private final BackupManifestCodec codec = new BackupManifestCodec();
    public BackupJobController(ObjectProvider<BackupJobService> services, BackupPrincipalResolver principals) {
        this.services = services; this.principals = principals;
    }
    @GetMapping("/backup-jobs") public String page(Authentication authentication, HttpServletResponse response) {
        principals.require(authentication); response.setHeader("Cache-Control", "no-store"); return "backup-jobs";
    }
    @PostMapping(value = "/api/backups/jobs", consumes = MediaType.APPLICATION_JSON_VALUE) @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Submit a bounded backup job",
            description = "Accepts a bounded JSON backup request after resolving the authorized principal and queues a backup job. Returns 202 with its identifier; capacity exhaustion is 429, oversized input 413 and unavailable storage 503.")
    @ApiResponse(responseCode = "202", description = "Submit a bounded backup job response")
    @ApiResponse(responseCode = "400", description = "Invalid bounded backup request",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "413", description = "JSON request exceeds 64 KiB",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "429", description = "Backup job capacity exhausted",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "503", description = "Backup source or storage unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "403", description = "Backup authority denied",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Strict JSON request: profile, scope and time must agree; all wire fields must be present",
            content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = BackupManifestCodec.WireRequest.class,
                            requiredProperties = {"profile", "scope", "time", "gitRepresentation", "secrets"})))
    public ResponseEntity<Map<String, String>> submit(Authentication authentication, HttpServletRequest request) throws IOException {
        var actor = principals.require(authentication);
        if (request.getContentLengthLong() > BackupManifestCodec.MAX_REQUEST_BYTES) throw new RequestTooLarge();
        byte[] document = request.getInputStream().readNBytes(BackupManifestCodec.MAX_REQUEST_BYTES + 1);
        if (document.length > BackupManifestCodec.MAX_REQUEST_BYTES) throw new RequestTooLarge();
        var id = service().submit(codec.readRequest(document), actor);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("id", id.value().toString()));
    }
    @GetMapping("/api/backups/jobs") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "List visible backup jobs",
            description = "Lists backup jobs visible to the authorized backup principal without starting work or revealing another principal's archives.")
    @ApiResponse(responseCode = "200", description = "List visible backup jobs response")
    @ApiResponse(responseCode = "403", description = "Backup authority denied",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "503", description = "Backup service unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    public ResponseEntity<List<BackupJobService.JobView>> list(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().list(principals.require(authentication)));
    }
    @GetMapping("/api/backups/jobs/{id}") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read a backup job",
            description = "Reads the persisted state and progress of one authorized backup job. A successful HTTP read does not mean the backup itself has completed.")
    @ApiResponse(responseCode = "200", description = "Read a backup job response")
    @ApiResponse(responseCode = "400", description = "Invalid backup job identifier",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "403", description = "Backup authority denied",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "503", description = "Backup job unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    public ResponseEntity<BackupJobService.JobView> status(@Parameter(description = "Backup job UUID visible to the authorized backup principal")
            @PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().status(id(id), principals.require(authentication)));
    }
    @PostMapping("/api/backups/jobs/{id}/cancel") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Cancel a backup job",
            description = "Requests cancellation of the authorized backup job and returns whether cancellation was accepted. This does not delete an already downloaded archive or report unfinished work as successful.")
    @ApiResponse(responseCode = "200", description = "Cancel a backup job response")
    @ApiResponse(responseCode = "400", description = "Invalid backup job identifier",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "403", description = "Backup authority denied",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "503", description = "Backup job unavailable",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    public ResponseEntity<Map<String, Boolean>> cancel(@Parameter(description = "Backup job UUID visible to the authorized backup principal")
            @PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("cancelled", service().cancel(id(id), principals.require(authentication))));
    }
    @GetMapping("/api/backups/jobs/{id}/download")
    @io.swagger.v3.oas.annotations.Operation(summary = "Download a completed backup archive",
            description = "Streams the completed archive for an authorized backup job as an application/octet-stream attachment. The response is private and no-store; unfinished or unavailable archives are not replaced with empty successful downloads.")
    @ApiResponse(responseCode = "200", description = "Completed backup archive",
            content = @Content(mediaType = "application/octet-stream", schema = @Schema(type = "string", format = "binary")),
            headers = @Header(name = "Content-Disposition", description = "Archive attachment filename", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Invalid backup job identifier",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "403", description = "Backup authority denied",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    @ApiResponse(responseCode = "503", description = "Backup archive not available",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object", requiredProperties = "code"),
                    schemaProperties = @SchemaProperty(name = "code", schema = @Schema(type = "string"))))
    public void download(@Parameter(description = "Backup job UUID visible to the authorized backup principal")
            @PathVariable String id, Authentication authentication, HttpServletResponse response) throws IOException {
        try (var download = service().download(id(id), principals.require(authentication))) {
            response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Content-Disposition", ContentDisposition.attachment().filename(download.filename()).build().toString());
            response.setContentType("application/octet-stream"); response.setContentLengthLong(download.length());
            download.writeTo(response.getOutputStream());
        }
    }
    @ExceptionHandler(AccessDeniedException.class) @ResponseBody ResponseEntity<Map<String, String>> denied() { return error(403, "BACKUP_ACCESS_DENIED"); }
    @ExceptionHandler(BackupCapacityException.class) @ResponseBody ResponseEntity<Map<String, String>> capacity() { return error(429, "BACKUP_CAPACITY"); }
    @ExceptionHandler(RequestTooLarge.class) @ResponseBody ResponseEntity<Map<String, String>> tooLarge() { return error(413, "BACKUP_REQUEST_TOO_LARGE"); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody ResponseEntity<Map<String, String>> invalid() { return error(400, "BACKUP_INVALID_REQUEST"); }
    @ExceptionHandler({IllegalStateException.class, IOException.class}) @ResponseBody ResponseEntity<Map<String, String>> unavailable() { return error(503, "BACKUP_UNAVAILABLE"); }
    private ResponseEntity<Map<String, String>> error(int status, String code) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code));
    }
    private BackupJobService service() {
        var service = services.getIfAvailable(); if (service == null) throw new IllegalStateException("Backup source is unavailable"); return service;
    }
    private static BackupJobId id(String id) { return new BackupJobId(UUID.fromString(id)); }
    private static final class RequestTooLarge extends RuntimeException { }
}
