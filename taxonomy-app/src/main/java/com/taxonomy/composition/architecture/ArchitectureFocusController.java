package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

/** Read-only suggestions from the authorized, current semantic editor source. */
@RestController
@Tag(name = "Architecture editor focus")
public class ArchitectureFocusController {
    private final ArchitectureFocusSuggestionService suggestions;
    private final WorkspaceResolver resolver;

    public ArchitectureFocusController(ArchitectureFocusSuggestionService suggestions, WorkspaceResolver resolver) {
        this.suggestions = suggestions; this.resolver = resolver;
    }

    @PostMapping("/api/architecture/editor/focus/suggestions")
    @Operation(summary = "Investigate a focused element at the exact current editor revision",
            description = "Returns bounded typed design hypotheses from the durable workspace DSL. All context pins and the semantic revision must match before provider access. No architecture changes are applied; local candidates remain unassessed.")
    public ResponseEntity<ArchitectureFocusSuggestionService.Response> investigate(
            @RequestBody ArchitectureFocusSuggestionService.Request body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws IOException {
        if (ifMatch == null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                "An exact semantic revision If-Match is required");
        String expected = "\"workspace-revision-" + body.context().revision() + "\"";
        if (ifNoneMatch != null || !expected.equals(ifMatch))
            throw new IllegalArgumentException("HTTP precondition must match the semantic workspace revision");
        var result = suggestions.investigate(resolver.resolveCurrentRepositoryContext(), body);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.ETAG, "\"workspace-revision-" + result.context().revision() + "\"")
                .header("X-Taxonomy-Source", "WORKSPACE_REVISION")
                .header("X-Taxonomy-Semantic-Revision", Long.toString(result.context().revision())).body(result);
    }

    @ExceptionHandler(CommandProblem.class)
    public ResponseEntity<Map<String, String>> problem(CommandProblem error) {
        int status = switch (error.code()) { case "NOT_FOUND" -> 404; case "READ_ONLY" -> 403; default -> 409; };
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of(
                "code", error.code(), "field", error.field(), "detail", error.getMessage()));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of(
                "code", "INVALID_FOCUS_REQUEST", "detail", error.getMessage() == null ? "Invalid focus request" : error.getMessage()));
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of(
                "code", "GIT_UNAVAILABLE", "detail", "Architecture storage is unavailable; refresh before investigating"));
    }
}
