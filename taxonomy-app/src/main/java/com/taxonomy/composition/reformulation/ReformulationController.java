package com.taxonomy.composition.reformulation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.taxonomy.portfolio.reformulation.ReformulationService;
import com.taxonomy.portfolio.reformulation.ProposalSummary;
import com.taxonomy.portfolio.reformulation.ReformulationPreconditionException;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.util.List;

/** Scope and actor always come from authenticated workspace resolution, never request JSON. */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio"})
@RestController
@Tag(name = "Reformulations")
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations")
public class ReformulationController {
    private final ReformulationService service;
    private final WorkspaceResolver resolver;
    private final ReformulationExecutionService execution;
    public ReformulationController(ReformulationService service,WorkspaceResolver resolver,ReformulationExecutionService execution){this.service=service;this.resolver=resolver;this.execution=execution;}
    @PostMapping @Operation(summary = "Create and start a reformulation proposal",
            description = "Freezes the source requirement and starts proposal synthesis in the authenticated workspace. Returns 202 with the proposal Location and revision ETag. The original requirement remains unchanged until a separate confirmed adoption.")
    @ApiResponse(responseCode = "202", description = "Accepted; observe the run separately")
    public ResponseEntity<Proposal> create(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@RequestBody CreateRequest request) {
        var actor=resolver.resolveCurrentUsername();var context=resolver.resolveCurrentContext();
        var proposal=service.create(projectId,requirementId,request,actor,context);
        execution.start(projectId,requirementId,proposal.id(),proposal.currentRevision().number(),actor,context);
        return ResponseEntity.accepted().location(ServletUriComponentsBuilder.fromCurrentRequestUri()
                        .pathSegment(proposal.id()).build().toUri())
                .eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    @GetMapping @Operation(summary = "List requirement reformulation proposals",
            description = "Returns proposal summaries for the selected requirement and authenticated workspace; listing does not start synthesis or adopt text.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public List<ProposalSummary> list(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId) {
        return service.list(projectId,requirementId,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
    }
    @GetMapping("/{proposalId}") @Operation(summary = "Read a reformulation proposal",
            description = "Returns the stored proposal, immutable baseline and current proposal revision. The ETag is the quoted proposal revision required by subsequent mutations.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public ResponseEntity<Proposal> get(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId) {
        var proposal=service.get(projectId,requirementId,proposalId,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
        return ResponseEntity.ok().eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    @GetMapping("/{proposalId}/revisions/{revision}") @Operation(summary = "Read an immutable proposal revision",
            description = "Returns the numbered historical revision of an authorized proposal without changing its current revision or the original requirement.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public Revision revision(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,@Parameter(description = "Exact retained revision number or commit, as required by this endpoint") @PathVariable long revision) {
        return service.revision(projectId,requirementId,proposalId,revision,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
    }
    @PostMapping("/{proposalId}/revisions") @Operation(summary = "Save one reviewed proposal revision",
            description = "Requires the quoted current proposal revision in If-Match and exactly one text, answer or statement operation. Saves a new proposal revision and ETag, not the active requirement. Invalid revision operations return 422 and stale proposal revisions return 412.")
    @ApiResponse(responseCode = "201", description = "Operation completed")
    public ResponseEntity<Proposal> draft(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,
            @Parameter(description = "Required quoted positive proposal revision", required = true,
                    schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected,@RequestBody SaveDraftRequest request) {
        if(expected==null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match is required");
        if(!expected.matches("\"[1-9][0-9]*\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected a quoted proposal revision");
        long revision;
        try {revision=Long.parseLong(expected.substring(1,expected.length()-1));}
        catch(NumberFormatException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid proposal revision");}
        int operations=(request.text()!=null?1:0)+(request.answer()!=null?1:0)+(request.statement()!=null?1:0);
        if(operations!=1)throw new com.taxonomy.portfolio.reformulation.ReformulationAnswerException("Exactly one revision operation is required");
        if(request.answer()!=null)return changed(service.answer(projectId,requirementId,proposalId,revision,request.answer(),resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
        if(request.statement()!=null)return changed(service.statement(projectId,requirementId,proposalId,revision,request.statementId(),request.statement(),resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
        var proposal=service.saveDraft(projectId,requirementId,proposalId,revision,request,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    @PostMapping("/{proposalId}/answers") @Operation(summary = "Record an answer to a proposal question",
            description = "Records the reviewed question answer as a new proposal revision. Requires the current quoted If-Match revision; an answer changes the proposal, not the original requirement.")
    @ApiResponse(responseCode = "201", description = "Operation completed")
    public ResponseEntity<Proposal> answer(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,
            @Parameter(description = "Required quoted positive proposal revision", required = true,
                    schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected,@RequestBody AnswerRequest request) {
        return changed(service.answer(projectId,requirementId,proposalId,expected(expected),request,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @PostMapping("/{proposalId}/statements/{statementId}") @Operation(summary = "Review a reformulation statement",
            description = "Applies the statement review identified by statementId under the current If-Match proposal revision. Returns the updated proposal with a new ETag; this is not requirement adoption.")
    @ApiResponse(responseCode = "201", description = "Operation completed")
    public ResponseEntity<Proposal> statement(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,@Parameter(description = "Statement identifier in the proposal revision") @PathVariable String statementId,
            @Parameter(description = "Required quoted positive proposal revision", required = true,
                    schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected,@RequestBody StatementRequest request) {
        return changed(service.statement(projectId,requirementId,proposalId,expected(expected),statementId,request,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @PostMapping("/{proposalId}/variants") @Operation(summary = "Create a reviewed proposal variant",
            description = "Creates a proposal variant from the supplied decision and current quoted If-Match revision. Returns a new proposal revision; source text remains unchanged until confirmed adoption.")
    @ApiResponse(responseCode = "201", description = "Operation completed")
    public ResponseEntity<Proposal> variant(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,
            @Parameter(description = "Required quoted positive proposal revision", required = true,
                    schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected,@RequestBody VariantRequest request) {
        return changed(service.variant(projectId,requirementId,proposalId,expected(expected),request,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    private static ResponseEntity<Proposal> changed(Proposal proposal) {
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    private static long expected(String value) {
        if(value==null)throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match is required");
        if(!value.matches("\\\"[1-9][0-9]*\\\""))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected a quoted proposal revision");
        try {return Long.parseLong(value.substring(1,value.length()-1));}
        catch(NumberFormatException failure){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid proposal revision");}
    }
    @PostMapping("/{proposalId}/synthesis-runs") @Operation(summary = "Start a proposal synthesis run",
            description = "Starts synthesis from the exact current proposal revision named by If-Match. Returns the accepted run, not a completed or adopted requirement. Observe its progress and usage through the synthesis-run APIs.")
    @ApiResponse(responseCode = "202", description = "Accepted; observe the run separately")
    public ResponseEntity<Run> synthesize(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,
            @Parameter(description = "Required quoted positive proposal revision", required = true,
                    schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected) {
        if(expected==null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match is required");
        if(!expected.matches("\"[1-9][0-9]*\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected a quoted proposal revision");
        long revision;
        try {revision=Long.parseLong(expected.substring(1,expected.length()-1));}
        catch(NumberFormatException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid proposal revision");}
        return ResponseEntity.accepted().body(execution.start(projectId,requirementId,proposalId,revision,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @PostMapping("/{proposalId}/synthesis-runs/{runId}/cancel") @Operation(summary = "Cancel a proposal synthesis run",
            description = "Requests cancellation of the identified synthesis run after workspace authorization and current If-Match validation. Preserves the proposal and already retained evidence; does not adopt generated text.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public Run cancel(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,
            @Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId,@Parameter(description = "Existing synthesis run identifier") @PathVariable String runId,@Parameter(description = "Required quoted positive proposal revision", required = true,
                    schema = @Schema(type = "string", pattern = "\"[1-9][0-9]*\""), example = "\"3\"")
            @RequestHeader(value="If-Match",required=false) String expected) {
        return service.cancelRun(projectId,requirementId,proposalId,runId,expected(expected),resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
    }
    @GetMapping("/{proposalId}/synthesis-runs") @Operation(summary = "List proposal synthesis runs",
            description = "Returns retained synthesis runs for the selected proposal and authenticated workspace. Historical failed or cancelled runs remain distinguishable from successful synthesis.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public List<Run> runs(@Parameter(description = "Project containing the scoped resource") @PathVariable Long projectId,@Parameter(description = "Requirement within the selected project") @PathVariable Long requirementId,@Parameter(description = "Existing reformulation proposal identifier") @PathVariable String proposalId) {
        return service.runs(projectId,requirementId,proposalId,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
    }
    @ExceptionHandler(com.taxonomy.portfolio.reformulation.ReformulationAnswerException.class)
    public ResponseEntity<ProblemDetail> invalidAnswer(com.taxonomy.portfolio.reformulation.ReformulationAnswerException failure) {
        return ResponseEntity.status(422).body(ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY,failure.getMessage()));
    }
    @ExceptionHandler(ReformulationPreconditionException.class) public ResponseEntity<ProblemDetail> stale(ReformulationPreconditionException failure) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED,failure.getMessage()));
    }
}
