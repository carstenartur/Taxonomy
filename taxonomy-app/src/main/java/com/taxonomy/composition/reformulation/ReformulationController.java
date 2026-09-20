package com.taxonomy.composition.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationService;
import com.taxonomy.portfolio.reformulation.ReformulationPreconditionException;
import com.taxonomy.portfolio.reformulation.ProposalSummary;

import com.taxonomy.portfolio.reformulation.ReformulationDtos.*;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.util.List;

/** Scope and actor always come from authenticated workspace resolution, never request JSON. */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations")
public class ReformulationController {
    private final ReformulationService service;
    private final WorkspaceResolver resolver;
    public ReformulationController(ReformulationService service,WorkspaceResolver resolver){this.service=service;this.resolver=resolver;}
    @PostMapping public ResponseEntity<Proposal> create(@PathVariable Long projectId,@PathVariable Long requirementId,@RequestBody CreateRequest request) {
        var proposal=service.create(projectId,requirementId,request,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
        return ResponseEntity.accepted().location(URI.create("/api/projects/"+projectId+"/requirements/"+requirementId+"/reformulations/"+proposal.id()))
                .eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    @GetMapping public List<ProposalSummary> list(@PathVariable Long projectId,@PathVariable Long requirementId) {
        return service.list(projectId,requirementId,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
    }
    @GetMapping("/{proposalId}") public ResponseEntity<Proposal> get(@PathVariable Long projectId,@PathVariable Long requirementId,@PathVariable String proposalId) {
        var proposal=service.get(projectId,requirementId,proposalId,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
        return ResponseEntity.ok().eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    @GetMapping("/{proposalId}/revisions/{revision}") public Revision revision(@PathVariable Long projectId,@PathVariable Long requirementId,@PathVariable String proposalId,@PathVariable long revision) {
        return service.revision(projectId,requirementId,proposalId,revision,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
    }
    @PostMapping("/{proposalId}/revisions") public ResponseEntity<Proposal> draft(@PathVariable Long projectId,@PathVariable Long requirementId,@PathVariable String proposalId,
            @RequestHeader(value="If-Match",required=false) String expected,@RequestBody SaveDraftRequest request) {
        if(expected==null) throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,"If-Match is required");
        if(!expected.matches("\"[1-9][0-9]*\"")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected a quoted proposal revision");
        long revision;
        try {revision=Long.parseLong(expected.substring(1,expected.length()-1));}
        catch(NumberFormatException invalid){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid proposal revision");}
        var proposal=service.saveDraft(projectId,requirementId,proposalId,revision,request,resolver.resolveCurrentUsername(),resolver.resolveCurrentContext());
        return ResponseEntity.status(HttpStatus.CREATED).eTag(Long.toString(proposal.currentRevision().number())).body(proposal);
    }
    @ExceptionHandler(ReformulationPreconditionException.class) public ResponseEntity<ProblemDetail> stale(ReformulationPreconditionException failure) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED,failure.getMessage()));
    }
}
