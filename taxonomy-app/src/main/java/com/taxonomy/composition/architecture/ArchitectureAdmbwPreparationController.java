package com.taxonomy.composition.architecture;

import com.taxonomy.extension.api.integration.AdmbwQeaExport;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;

/** Native basis bytes are bounded and multipart files spool to disk before the reviewed operation. */
@RestController
@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
@RequestMapping("/api/architecture/editor/admbw")
public class ArchitectureAdmbwPreparationController {
    private final ArchitectureAdmbwPreparationService service;
    private final LifecycleHttpContext contexts;
    private final ObjectMapper json;
    private static final int MAX_REVIEW_BYTES = 64 * 1024;

    public ArchitectureAdmbwPreparationController(ArchitectureAdmbwPreparationService service, LifecycleHttpContext contexts, ObjectMapper json) {
        this.service = service;
        this.contexts = contexts;
        this.json = json;
    }

    @PostMapping(value = "/basis", consumes = {AdmbwQeaExport.MEDIA_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE})
    @Operation(summary = "Inspect actual ADMBw reference identities in a supplied QEA basis without changing it")
    public ResponseEntity<AdmbwQeaExport.Basis> basis(@RequestParam String repositoryId,
            @RequestParam String workspaceScopeKey, @RequestParam String branch, HttpServletRequest request) throws IOException {
        var context = contexts.resolveForRead(repositoryId, workspaceScopeKey, branch);
        var result = service.inspect(context, read(request.getInputStream()));
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(result);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Preview a version-bound C1–S1 and R7 draft using actual native references")
    public ResponseEntity<ArchitectureAdmbwPreparationService.Preview> preview(
            @RequestPart("request") MultipartFile review,
            @RequestPart("basis") MultipartFile basis,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        var request = review(review);
        LifecycleHttpContext.requireRevision(request.context(), ifMatch, ifNoneMatch);
        var result = service.preview(contexts.resolveForRead(), request, read(basis));
        return LifecycleHttpContext.response(result.context()).body(result);
    }

    @PostMapping(value = "/accept", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Apply explicitly reviewed ADMBw assignments atomically and retain their checkpoint receipt")
    public ResponseEntity<ArchitectureLifecycleService.Publication> accept(
            @RequestPart("request") MultipartFile review,
            @RequestPart("basis") MultipartFile basis,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        var request = review(review);
        LifecycleHttpContext.requireRevision(request.context(), ifMatch, ifNoneMatch);
        var result = service.accept(contexts.resolveForRead(), request, read(basis));
        return LifecycleHttpContext.response(result.context()).body(result);
    }

    private ArchitectureAdmbwPreparationService.Request review(MultipartFile part) throws IOException {
        if (part.getSize() > MAX_REVIEW_BYTES) throw new IllegalArgumentException("The ADMBw review exceeds 64 KiB");
        try (var stream = part.getInputStream()) {
            byte[] bytes = stream.readNBytes(MAX_REVIEW_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_REVIEW_BYTES)
                throw new IllegalArgumentException("Supply a nonempty ADMBw review up to 64 KiB");
            try {
                var request = json.readValue(bytes, ArchitectureAdmbwPreparationService.Request.class);
                if (request == null) throw new IllegalArgumentException("The ADMBw review must be an object");
                return request;
            }
            catch (tools.jackson.core.JacksonException invalid) {
                throw new IllegalArgumentException("The ADMBw review is not a valid preparation request", invalid);
            }
        }
    }

    private static byte[] read(MultipartFile file) throws IOException {
        if (file.getSize() > AdmbwQeaExport.MAX_BASIS_BYTES) throw new IllegalArgumentException("QEA basis exceeds 32 MiB");
        try (var stream = file.getInputStream()) { return read(stream); }
    }

    private static byte[] read(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(AdmbwQeaExport.MAX_BASIS_BYTES + 1);
        if (bytes.length == 0 || bytes.length > AdmbwQeaExport.MAX_BASIS_BYTES)
            throw new IllegalArgumentException("Supply a nonempty QEA basis up to 32 MiB");
        return bytes;
    }
}
