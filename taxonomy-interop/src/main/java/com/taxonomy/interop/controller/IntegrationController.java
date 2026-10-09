package com.taxonomy.interop.controller;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "Integrations")
public class IntegrationController {
    private final IntegrationService service;
    private final WorkspaceResolver resolver;
    public IntegrationController(IntegrationService service, WorkspaceResolver resolver) { this.service = service; this.resolver = resolver; }
    @GetMapping("/integrations") public String page(jakarta.servlet.http.HttpServletRequest request, org.springframework.ui.Model model) {
        model.addAttribute("mayWrite", request.isUserInRole("ADMIN") || request.isUserInRole("ARCHITECT"));
        return "integrations";
    }
    @GetMapping("/api/integrations/profiles") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "List available integration profiles",
            description = "Lists registered integration descriptors and capabilities for the authorized repository. This capability read does not create a connection or invoke a remote synchronization.")
    @ApiResponse(responseCode = "200", description = "List available integration profiles response")
    public List<IntegrationDescriptor> profiles() { return service.profiles(resolver.resolveCurrentRepositoryContext()); }
    @GetMapping("/api/integrations") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "List scoped integration connections",
            description = "Lists configured connections visible in the authorized repository context. This read does not initiate import, synchronization or publication.")
    @ApiResponse(responseCode = "200", description = "List scoped integration connections response")
    public List<Connection> connections() { return service.connections(resolver.resolveCurrentRepositoryContext()); }
    @PostMapping("/api/integrations") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Create an integration connection",
            description = "Creates a connection to a registered integration profile in the authorized repository context. Configuration alone does not import, synchronize or publish architecture data.")
    @ApiResponse(responseCode = "200", description = "Create an integration connection response")
    public Connection create(@RequestBody CreateConnection request) { return service.create(resolver.resolveCurrentRepositoryContext(), request); }
    @GetMapping("/api/integrations/{connection}") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read an integration overview",
            description = "Reads the connection overview in the authorized context, optionally narrowed by root resource and selector fingerprint. This inspection does not apply a change set.")
    @ApiResponse(responseCode = "200", description = "Read an integration overview response")
    public Overview overview(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Optional external root resource selector for this connection overview")
            @RequestParam(required=false) String rootResource, @Parameter(description = "Optional fingerprint of the selected external resource scope")
            @RequestParam(required=false) String selectorFingerprint) { return service.overview(resolver.resolveCurrentRepositoryContext(), connection, rootResource, selectorFingerprint); }
    @PostMapping(value="/api/integrations/{connection}/previews", consumes="multipart/form-data") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview an uploaded exchange file",
            description = "Accepts multipart request metadata and an exchange file, bounded to 16 MiB, and prepares an authorized import preview. Review and explicit apply are separate from parsing; oversized files return 413.")
    @ApiResponse(responseCode = "200", description = "Preview an uploaded exchange file response")
    public Operation preview(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestPart("request") PreviewRequest request, @RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > com.taxonomy.exchange.ExchangeXml.MAX_BYTES) throw new IntegrationProblem("PAYLOAD_LIMIT", 413, "Exchange exceeds 16 MiB");
        return service.preview(resolver.resolveCurrentRepositoryContext(), connection, request, file.getBytes());
    }
    @PostMapping("/api/integrations/{connection}/export-previews") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview a scoped integration export",
            description = "Creates an export preview from the requested exact local state for human review. This does not publish to a remote endpoint or implicitly accept a generated change set.")
    @ApiResponse(responseCode = "200", description = "Preview a scoped integration export response")
    public Operation previewExport(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestBody ExportRequest request) { return service.previewExport(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @PostMapping("/api/integrations/{connection}/remote-previews") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview a remote integration import",
            description = "Obtains a remote-source preview through the configured integration in the authorized context. It may contact that endpoint but does not apply changes to the local architecture.")
    @ApiResponse(responseCode = "200", description = "Preview a remote integration import response")
    public Operation previewRemote(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestBody RemoteRequest request) { return service.previewRemote(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read an integration operation",
            description = "Returns the retained integration operation after authorizing the connection and current repository context. HTTP success denotes a successful read, not necessarily successful execution of the operation.")
    @ApiResponse(responseCode = "200", description = "Read an integration operation response")
    public Operation operation(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation) { return service.operation(resolver.resolveCurrentRepositoryContext(), connection, operation); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/events") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read integration operation history",
            description = "Returns the retained event list for the authorized integration operation. This endpoint returns JSON history, not an SSE stream, and does not resume execution.")
    @ApiResponse(responseCode = "200", description = "Read integration operation history response")
    public List<Event> events(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation) { return service.events(resolver.resolveCurrentRepositoryContext(), connection, operation); }
    @GetMapping("/api/integrations/{connection}/identities") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read integration identity mappings",
            description = "Lists recorded external-to-internal identity mappings for the authorized connection. Reading mappings does not merge or rebind identities.")
    @ApiResponse(responseCode = "200", description = "Read integration identity mappings response")
    public List<Identity> identities(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection) { return service.identities(resolver.resolveCurrentRepositoryContext(), connection); }
    @PostMapping("/api/integrations/{connection}/apply") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Apply a reviewed integration change set",
            description = "Applies only the explicitly reviewed change set to the authorized local architecture. The stored preview and exact source state are revalidated; reading or creating a preview never implies acceptance.")
    @ApiResponse(responseCode = "200", description = "Apply a reviewed integration change set response")
    public Operation apply(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestBody ReviewedChangeSet request) { return service.apply(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @PostMapping("/api/integrations/{connection}/files") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Prepare a reviewed integration file",
            description = "Prepares an exchange file from the explicitly reviewed change set in the authorized repository context. The file is retrieved through the operation download endpoint; preparation does not publish it remotely.")
    @ApiResponse(responseCode = "200", description = "Prepare a reviewed integration file response")
    public Operation prepareFile(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestBody ReviewedChangeSet request) { return service.prepareFile(resolver.resolveCurrentRepositoryContext(), connection, request); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/file") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Download a prepared integration file",
            description = "Downloads the operation's retained exchange file with its format-specific media type, attachment filename, semantic revision and checkpoint headers. No model or remote publication is started by this GET.")
    @ApiResponse(responseCode = "200", description = "Download a prepared integration file response")
    public ResponseEntity<byte[]> file(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation) {
        var context = resolver.resolveCurrentRepositoryContext();
        var file = service.file(context, connection, operation); var authority = service.operation(context, connection, operation).context().internalState();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment().filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store").header("X-Content-Type-Options", "nosniff")
                .header("X-Taxonomy-Semantic-Revision", Long.toString(authority.semanticRevision()))
                .header("X-Taxonomy-Checkpoint", authority.commitId() == null ? "none" : authority.commitId()).body(file.content());
    }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/endpoint-options") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read integration endpoint choices",
            description = "Returns endpoint options for the retained operation in its authorized repository context. Publication operations additionally require the requested branch to match the active authorized branch.")
    @ApiResponse(responseCode = "200", description = "Read integration endpoint choices response")
    public com.taxonomy.interop.IntegrationDomainAdapter.EndpointIndex endpointOptions(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) {
        return service.operationEndpointOptions(operationContext(connection, operation, branch), connection, operation);
    }
    @PostMapping("/api/integrations/{connection}/operations/{operation}/retry") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Retry an eligible integration operation",
            description = "Explicitly retries eligible work using the retained operation and authorized context. Publication operations retain their exact-branch checks; uncertain remote writes are not treated as automatically safe to repeat.")
    @ApiResponse(responseCode = "200", description = "Retry an eligible integration operation response")
    public Object retry(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) { return service.retryOperation(operationContext(connection, operation, branch), connection, operation); }
    public record Cancel(String rationale) {}
    @PostMapping("/api/integrations/{connection}/operations/{operation}/cancel") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Cancel an integration operation",
            description = "Requests cancellation of the specified operation with a rationale in its authorized context. Publication branch pins are checked; cancellation cannot undo a remote write that has already committed.")
    @ApiResponse(responseCode = "200", description = "Cancel an integration operation response")
    public Object cancel(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation, @RequestBody Cancel request, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) { return service.cancelOperation(operationContext(connection, operation, branch), connection, operation, request.rationale()); }

    /** Nullable wire state is checked before constructing the strict bounded contract. */
    public record PublicationInput(UUID operationId, InternalState expected, PublicationMode mode, PublicationScope scope, String expectedExternalRevision) {
        PublicationPreviewRequest request() {
            if (expected == null || expectedExternalRevision == null || expectedExternalRevision.isBlank()) throw new IntegrationProblem("EXACT_STATE_REQUIRED", 428, "An exact local state and remote revision are required");
            if (operationId == null || mode == null || scope == null) throw new IllegalArgumentException("Publication identity, mode and scope are required");
            return new PublicationPreviewRequest(operationId, expected, mode, scope, expectedExternalRevision);
        }
    }
    @PostMapping("/api/integrations/{connection}/publication-previews") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview an external publication",
            description = "Captures review material using the exact local state, publication scope and expected external revision. The optional branch must match the authorized workspace. A preview is not permission to publish; missing exact state returns 428.")
    @ApiResponse(responseCode = "200", description = "Preview an external publication response")
    public PublicationOperation previewPublication(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestBody PublicationInput request, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) { return service.previewPublication(publicationContext(branch), connection, request.request()); }
    @PostMapping("/api/integrations/{connection}/publish") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Publish an explicitly reviewed change set",
            description = "Performs the explicit reviewed publication through the configured provider, after checking the retained preview and exact local/remote preconditions. This operation can mutate the remote system and is not a read-only export.")
    @ApiResponse(responseCode = "200", description = "Publish an explicitly reviewed change set response")
    public PublicationOperation publish(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @RequestBody PublicationReview request, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) { return service.publish(publicationContext(branch), connection, request); }
    @GetMapping("/api/integrations/{connection}/operations/{operation}/publication") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Read publication operation state",
            description = "Reads the retained publication receipt/state in the exact authorized branch. This read does not retry uncertain remote work or assume that a timed-out publication failed.")
    @ApiResponse(responseCode = "200", description = "Read publication operation state response")
    public PublicationOperation publication(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) { return service.publication(publicationContext(branch), connection, operation); }
    public record ReconciliationInput(UUID predecessorOperationId, PublicationInput request, String rationale) {
        ReconciliationPreviewRequest checked() {
            if (request == null) throw new IntegrationProblem("EXACT_STATE_REQUIRED", 428, "An exact local state and remote revision are required");
            return new ReconciliationPreviewRequest(predecessorOperationId, request.request(), rationale);
        }
    }
    @PostMapping("/api/integrations/{connection}/operations/{operation}/reconciliation-previews") @ResponseBody
    @io.swagger.v3.oas.annotations.Operation(summary = "Preview reconciliation after publication",
            description = "Creates reconciliation review material for the predecessor publication using a fresh exact local state, expected external revision and rationale. It does not blindly repeat an uncertain remote write.")
    @ApiResponse(responseCode = "200", description = "Preview reconciliation after publication response")
    public PublicationOperation reconcile(@Parameter(description = "Connection UUID within the authenticated repository context")
            @PathVariable UUID connection, @Parameter(description = "Retained operation UUID belonging to this connection")
            @PathVariable UUID operation, @RequestBody ReconciliationInput request, @Parameter(description = "Optional branch pin; publication operations require it to match the authorized workspace branch")
            @RequestParam(required=false) String branch) { return service.reconcilePublication(publicationContext(branch), connection, operation, request.checked()); }

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
