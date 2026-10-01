package com.taxonomy.backup.web;

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
    public ResponseEntity<Map<String, String>> submit(Authentication authentication, HttpServletRequest request) throws IOException {
        var actor = principals.require(authentication);
        if (request.getContentLengthLong() > BackupManifestCodec.MAX_REQUEST_BYTES) throw new RequestTooLarge();
        byte[] document = request.getInputStream().readNBytes(BackupManifestCodec.MAX_REQUEST_BYTES + 1);
        if (document.length > BackupManifestCodec.MAX_REQUEST_BYTES) throw new RequestTooLarge();
        var id = service().submit(codec.readRequest(document), actor);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("id", id.value().toString()));
    }
    @GetMapping("/api/backups/jobs") @ResponseBody
    public ResponseEntity<List<BackupJobService.JobView>> list(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().list(principals.require(authentication)));
    }
    @GetMapping("/api/backups/jobs/{id}") @ResponseBody
    public ResponseEntity<BackupJobService.JobView> status(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().status(id(id), principals.require(authentication)));
    }
    @PostMapping("/api/backups/jobs/{id}/cancel") @ResponseBody
    public ResponseEntity<Map<String, Boolean>> cancel(@PathVariable String id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("cancelled", service().cancel(id(id), principals.require(authentication))));
    }
    @GetMapping("/api/backups/jobs/{id}/download")
    public void download(@PathVariable String id, Authentication authentication, HttpServletResponse response) throws IOException {
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
