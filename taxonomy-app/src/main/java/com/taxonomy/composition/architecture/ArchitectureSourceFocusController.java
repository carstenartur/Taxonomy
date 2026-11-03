package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Read-only investigation of the explicitly pinned retained source. */
@com.taxonomy.shared.features.ConditionalOnFeature({"architecture", "analysis"})
@RestController
public class ArchitectureSourceFocusController {
    private final ArchitectureSourceFocusService service;
    private final WorkspaceResolver resolver;
    public ArchitectureSourceFocusController(ArchitectureSourceFocusService service, WorkspaceResolver resolver) {
        this.service=service; this.resolver=resolver;
    }
    @PostMapping("/api/architecture/focus/suggestions")
    @Operation(summary = "Investigate retained architecture evidence",
            description = "Returns bounded suggestions for an authorized analysis draft or saved snapshot. "
                    + "The exact source identity and retained version are validated before investigation. "
                    + "The operation does not modify the source evidence or the working architecture.")
    public ResponseEntity<ArchitectureSourceFocusDtos.Response> investigate(@RequestBody ArchitectureSourceFocusDtos.Request request) {
        var result = service.investigate(resolver.resolveCurrentRepositoryContext(),request);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(result);
    }
    @ExceptionHandler(CommandProblem.class)
    public ResponseEntity<Map<String,String>> problem(CommandProblem error) {
        int status = switch (error.code()) { case "NOT_FOUND" -> 404; case "READ_ONLY" -> 403; default -> 409; };
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(Map.of("code",error.code(),"field",error.field(),"detail",error.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL,"no-store")
                .body(Map.of("code","INVALID_FOCUS_REQUEST","detail",error.getMessage() == null ? "Invalid focus request" : error.getMessage()));
    }
}
