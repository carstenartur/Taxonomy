package com.taxonomy.interop.controller;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.IntegrationService;
import com.taxonomy.interop.IntegrationService.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class IntegrationController {
    private final IntegrationService service;
    private final WorkspaceResolver resolver;
    public IntegrationController(IntegrationService service, WorkspaceResolver resolver) { this.service = service; this.resolver = resolver; }
    @GetMapping("/integrations") public String page(jakarta.servlet.http.HttpServletRequest request, org.springframework.ui.Model model) {
        model.addAttribute("mayWrite", request.isUserInRole("ADMIN") || request.isUserInRole("ARCHITECT"));
        return "integrations";
    }
    @GetMapping("/api/integrations/profiles") @ResponseBody
    public List<IntegrationDescriptor> profiles() { return service.profiles(resolver.resolveCurrentRepositoryContext()); }
    @GetMapping("/api/integrations") @ResponseBody
    public List<Connection> connections() { return service.connections(resolver.resolveCurrentRepositoryContext()); }
    @PostMapping("/api/integrations") @ResponseBody
    public Connection create(@RequestBody CreateConnection request) { return service.create(resolver.resolveCurrentRepositoryContext(), request); }
    @GetMapping("/api/integrations/{connection}") @ResponseBody
    public Overview overview(@PathVariable UUID connection) { return service.overview(resolver.resolveCurrentRepositoryContext(), connection); }
    @PostMapping(value="/api/integrations/{connection}/previews", consumes="multipart/form-data") @ResponseBody
    public Operation preview(@PathVariable UUID connection, @RequestPart("request") PreviewRequest request, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > com.taxonomy.exchange.ExchangeXml.MAX_BYTES) throw new IntegrationProblem("PAYLOAD_LIMIT", 413, "Exchange exceeds 16 MiB");
        return service.preview(resolver.resolveCurrentRepositoryContext(), connection, request, file.getBytes());
    }
    @PostMapping("/api/integrations/{connection}/export-previews") @ResponseBody
    public Operation previewExport(@PathVariable UUID connection, @RequestBody ExportRequest request) { return service.previewExport(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @PostMapping("/api/integrations/{connection}/remote-previews") @ResponseBody
    public Operation previewRemote(@PathVariable UUID connection, @RequestBody RemoteRequest request) { return service.previewRemote(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}") @ResponseBody
    public Operation operation(@PathVariable UUID connection, @PathVariable UUID operation) { return service.operation(resolver.resolveCurrentRepositoryContext(), connection, operation); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/events") @ResponseBody
    public List<Event> events(@PathVariable UUID connection, @PathVariable UUID operation) { return service.events(resolver.resolveCurrentRepositoryContext(), connection, operation); }
    @GetMapping("/api/integrations/{connection}/identities") @ResponseBody
    public List<Identity> identities(@PathVariable UUID connection) { return service.identities(resolver.resolveCurrentRepositoryContext(), connection); }
    @PostMapping("/api/integrations/{connection}/apply") @ResponseBody
    public Operation apply(@PathVariable UUID connection, @RequestBody ReviewedChangeSet request) { return service.apply(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @PostMapping("/api/integrations/{connection}/files") @ResponseBody
    public Operation prepareFile(@PathVariable UUID connection, @RequestBody ReviewedChangeSet request) { return service.prepareFile(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/file") @ResponseBody
    public ResponseEntity<byte[]> file(@PathVariable UUID connection, @PathVariable UUID operation) {
        var context = resolver.resolveCurrentRepositoryContext();
        var file = service.file(context, connection, operation); var authority = service.operation(context, connection, operation).context().internalState();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff")
                .header("X-Taxonomy-Semantic-Revision", Long.toString(authority.semanticRevision()))
                .header("X-Taxonomy-Checkpoint", authority.commitId() == null ? "none" : authority.commitId()).body(file.content());
    }
    @PostMapping("/api/integrations/{connection}/operations/{operation}/retry") @ResponseBody
    public Operation retry(@PathVariable UUID connection, @PathVariable UUID operation) { return service.retry(resolver.resolveCurrentRepositoryContext(), connection, operation); }
    public record Cancel(String rationale) {}
    @PostMapping("/api/integrations/{connection}/operations/{operation}/cancel") @ResponseBody
    public Operation cancel(@PathVariable UUID connection, @PathVariable UUID operation, @RequestBody Cancel request) { return service.cancel(resolver.resolveCurrentRepositoryContext(), connection, operation, request.rationale()); }

    @ExceptionHandler(IntegrationProblem.class) @ResponseBody
    public ResponseEntity<Map<String, String>> problem(IntegrationProblem problem) { return ResponseEntity.status(problem.status()).body(Map.of("code", problem.code(), "message", problem.getMessage())); }
    @ExceptionHandler(ExchangeFormatException.class) @ResponseBody
    public ResponseEntity<Map<String, String>> format(ExchangeFormatException problem) { return ResponseEntity.unprocessableContent().body(Map.of("code", problem.code(), "message", problem.getMessage())); }
    @ExceptionHandler(CommandProblem.class) @ResponseBody
    public ResponseEntity<Map<String, Object>> command(CommandProblem problem) { return ResponseEntity.unprocessableContent().body(Map.of("code", problem.code(), "message", problem.getMessage(), "dependencies", problem.dependencies())); }
    @ExceptionHandler(RevisionConflict.class) @ResponseBody
    public ResponseEntity<Map<String, String>> revision() { return ResponseEntity.status(409).body(Map.of("code", "INTERNAL_STATE_CHANGED", "message", "Refresh the exact workspace state and preview again")); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    public ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Check the required bounded integration fields")); }
}
