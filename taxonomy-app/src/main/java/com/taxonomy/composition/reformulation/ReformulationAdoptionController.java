package com.taxonomy.composition.reformulation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.portfolio.reformulation.ReformulationAdoptionService;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionDtos.*;
import com.taxonomy.portfolio.reformulation.ReformulationPreconditionException;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Dedicated two-stage command boundary, separate from proposal editing and generation. */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio"})
@RestController
@Tag(name = "Reformulation adoption", description = "Explicit preview and confirmed adoption; never automatic acceptance")
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}")
public class ReformulationAdoptionController {
    private final ReformulationAdoptionService service;
    private final WorkspaceResolver resolver;
    public ReformulationAdoptionController(ReformulationAdoptionService service,WorkspaceResolver resolver){this.service=service;this.resolver=resolver;}
    @Operation(summary = "Prepare an immutable reformulation adoption preview",
            description = "Creates review material for the current proposal revision and requirement version in the authenticated workspace. Does not adopt the text or approve the architecture. If-Match must contain the quoted positive proposal revision, for example \"3\". Review the returned final text, warnings, blocking reasons and preview hash before confirmation.")
    @ApiResponse(responseCode = "201", description = "Immutable adoption preview")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Proposal or preview absent or outside the authorized scope", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "Invalid revision format or confirmation request", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "412", description = "Expected proposal revision is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "428", description = "Required If-Match header is missing", content = @Content)
    @ApiResponse(responseCode = "403", description = "Required authority or CSRF protection is missing", content = @Content)
    @PostMapping("/adoption-previews") public ResponseEntity<Preview> preview(@Parameter(description = "Project containing the requirement") @PathVariable Long projectId,@Parameter(description = "Requirement whose text is being reviewed") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,@Parameter(description = "Required quoted positive proposal revision, for example \"3\"",
                    required = true, schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.preview(projectId,requirementId,proposalId,
                expected(expected),resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @Operation(summary = "Read an existing adoption preview",
            description = "Returns the previously captured immutable preview for this proposal after owner and workspace authorization. Does not refresh the preview or change requirement text.")
    @ApiResponse(responseCode = "200", description = "Stored adoption preview")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Proposal or preview absent or outside the authorized scope", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/adoption-previews/{previewId}") public ResponseEntity<Preview> read(@Parameter(description = "Project containing the requirement") @PathVariable Long projectId,@Parameter(description = "Requirement whose text is being reviewed") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,@Parameter(description = "Immutable preview identifier from the preview response") @PathVariable String previewId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.readPreview(projectId,requirementId,proposalId,previewId,
                resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @Operation(summary = "Confirm adoption of reviewed requirement text",
            description = "Applies an explicitly confirmed preview using its id and hash, an idempotent commandId, and the quoted proposal revision in If-Match. Stale proposal or requirement versions are rejected. Required warning acknowledgement and rationale are validated. Creates the requirement version and adoption receipt atomically; does not approve the architecture or launch a new analysis. Exact command replays return the recorded result.")
    @ApiResponse(responseCode = "200", description = "Recorded adoption receipt")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Proposal or preview absent or outside the authorized scope", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "400", description = "Invalid revision format or confirmation request", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "412", description = "Expected proposal revision is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "428", description = "Required If-Match header is missing", content = @Content)
    @ApiResponse(responseCode = "403", description = "Required authority or CSRF protection is missing", content = @Content)
    @ApiResponse(responseCode = "409", description = "Confirmation conflicts with the stored preview, command or requirement version",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/adoptions") public ResponseEntity<Result> adopt(@Parameter(description = "Project containing the requirement") @PathVariable Long projectId,@Parameter(description = "Requirement whose text is being reviewed") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,@Parameter(description = "Required quoted positive proposal revision, for example \"3\"",
                    required = true, schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected,@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    description = "Exact preview confirmation; all acknowledgement fields are deliberate user decisions",
                    content = @Content(schema = @Schema(implementation = ConfirmRequest.class)))
            @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.adopt(projectId,requirementId,proposalId,expected(expected),request,
                resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @Operation(summary = "List recorded reformulation adoptions",
            description = "Returns the existing adoption receipts for this proposal in the authenticated owner and workspace scope. Historical receipts are not previews and reading them never applies requirement changes.")
    @ApiResponse(responseCode = "200", description = "Recorded adoption history")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "404", description = "Proposal or preview absent or outside the authorized scope", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping("/adoptions") public ResponseEntity<List<Result>> history(@Parameter(description = "Project containing the requirement") @PathVariable Long projectId,@Parameter(description = "Requirement whose text is being reviewed") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(projectId,requirementId,proposalId,
                resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }

    private static long expected(String value) {
        if(value==null)throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match is required");
        if(!value.matches("\"[1-9][0-9]*\""))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected quoted proposal revision");
        try{return Long.parseLong(value.substring(1,value.length()-1));}
        catch(NumberFormatException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid proposal revision");}
    }
    @ExceptionHandler(ReformulationPreconditionException.class) public ResponseEntity<ProblemDetail> stale(ReformulationPreconditionException e) {
        return ResponseEntity.status(412).cacheControl(CacheControl.noStore()).body(ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED,e.getMessage()));
    }
}
