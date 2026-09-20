package com.taxonomy.interop.controller;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.exchange.ExchangeFormatException;
import com.taxonomy.extension.api.integration.IntegrationContracts.*;
import com.taxonomy.extension.api.integration.PublicationContracts.*;
import com.taxonomy.interop.IntegrationProblem;
import com.taxonomy.interop.IntegrationService;
import com.taxonomy.interop.IntegrationService.*;
import com.taxonomy.interop.persistence.IntegrationStore.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import com.taxonomy.workspace.service.RepositoryContext;
import com.taxonomy.workspace.service.WorkspaceRevisionConflict;
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
    public Overview overview(@PathVariable UUID connection, @RequestParam(required=false) String rootResource, @RequestParam(required=false) String selectorFingerprint) { return service.overview(resolver.resolveCurrentRepositoryContext(), connection, rootResource, selectorFingerprint); }
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
    @GetMapping("/api/integrations/{connection}/operations/{operation}/endpoint-options") @ResponseBody
    public com.taxonomy.interop.IntegrationDomainAdapter.EndpointIndex endpointOptions(@PathVariable UUID connection, @PathVariable UUID operation, @RequestParam(required=false) String branch) {
        return service.operationEndpointOptions(operationContext(connection, operation, branch), connection, operation);
    }
    @PostMapping("/api/integrations/{connection}/operations/{operation}/retry") @ResponseBody
    public Object retry(@PathVariable UUID connection, @PathVariable UUID operation, @RequestParam(required=false) String branch) { return service.retryOperation(operationContext(connection, operation, branch), connection, operation); }
    public record Cancel(String rationale) {}
    @PostMapping("/api/integrations/{connection}/operations/{operation}/cancel") @ResponseBody
    public Object cancel(@PathVariable UUID connection, @PathVariable UUID operation, @RequestBody Cancel request, @RequestParam(required=false) String branch) { return service.cancelOperation(operationContext(connection, operation, branch), connection, operation, request.rationale()); }

    /** Nullable wire state is checked before constructing the strict bounded contract. */
    public record PublicationInput(UUID operationId, InternalState expected, PublicationMode mode, PublicationScope scope, String expectedExternalRevision) {
        PublicationPreviewRequest request() {
            if (expected == null || expectedExternalRevision == null || expectedExternalRevision.isBlank()) throw new IntegrationProblem("EXACT_STATE_REQUIRED", 428, "An exact local state and remote revision are required");
            if (operationId == null || mode == null || scope == null) throw new IllegalArgumentException("Publication identity, mode and scope are required");
            return new PublicationPreviewRequest(operationId, expected, mode, scope, expectedExternalRevision);
        }
    }
    @PostMapping("/api/integrations/{connection}/publication-previews") @ResponseBody
    public PublicationOperation previewPublication(@PathVariable UUID connection, @RequestBody PublicationInput request, @RequestParam(required=false) String branch) { return service.previewPublication(publicationContext(branch), connection, request.request()); }
    @PostMapping("/api/integrations/{connection}/publish") @ResponseBody
    public PublicationOperation publish(@PathVariable UUID connection, @RequestBody PublicationReview request, @RequestParam(required=false) String branch) { return service.publish(publicationContext(branch), connection, request); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/publication") @ResponseBody
    public PublicationOperation publication(@PathVariable UUID connection, @PathVariable UUID operation, @RequestParam(required=false) String branch) { return service.publication(publicationContext(branch), connection, operation); }
    @PostMapping("/api/integrations/{connection}/operations/{operation}/reconciliation-previews") @ResponseBody
    public PublicationOperation reconcile(@PathVariable UUID connection, @PathVariable UUID operation, @RequestBody ReconciliationPreviewRequest request, @RequestParam(required=false) String branch) { return service.reconcilePublication(publicationContext(branch), connection, operation, request); }

    private RepositoryContext publicationContext(String branch) {
        return requirePublicationBranch(resolver.resolveCurrentRepositoryContext(), branch);
    }
    private RepositoryContext operationContext(UUID connection, UUID operation, String branch) {
        var context = resolver.resolveCurrentRepositoryContext();
        var direction = service.operation(context, connection, operation).direction();
        return java.util.Set.of("PUSH", "SYNCHRONIZE").contains(direction) ? requirePublicationBranch(context, branch) : context;
    }
    private static RepositoryContext requirePublicationBranch(RepositoryContext context, String branch) {
        if (branch != null && !context.branch().equals(branch))
            throw new IntegrationProblem("INTERNAL_STATE_CHANGED", 409, "The requested branch differs from the authorized workspace branch");
        return context;
    }

    @ExceptionHandler(IntegrationProblem.class) @ResponseBody
    public ResponseEntity<Map<String, String>> problem(IntegrationProblem problem) { return ResponseEntity.status(problem.status()).body(Map.of("code", problem.code(), "message", problem.getMessage())); }
    @ExceptionHandler(ExchangeFormatException.class) @ResponseBody
    public ResponseEntity<Map<String, String>> format(ExchangeFormatException problem) { return ResponseEntity.unprocessableContent().body(Map.of("code", problem.code(), "message", problem.getMessage())); }
    @ExceptionHandler(CommandProblem.class) @ResponseBody
    public ResponseEntity<Map<String, Object>> command(CommandProblem problem) { return ResponseEntity.unprocessableContent().body(Map.of("code", problem.code(), "message", problem.getMessage(), "dependencies", problem.dependencies())); }
    @ExceptionHandler(WorkspaceRevisionConflict.class) @ResponseBody
    public ResponseEntity<Map<String, String>> revision() { return ResponseEntity.status(409).body(Map.of("code", "INTERNAL_STATE_CHANGED", "message", "Refresh the exact workspace state and preview again")); }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseBody
    public ResponseEntity<Map<String, String>> invalid() { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "Check the required bounded integration fields")); }
}
