package com.taxonomy.composition.reformulation;

import com.taxonomy.portfolio.reformulation.ReformulationAdoptionService;
import com.taxonomy.portfolio.reformulation.ReformulationAdoptionDtos.*;
import com.taxonomy.portfolio.reformulation.ReformulationPreconditionException;
import com.taxonomy.workspace.service.WorkspaceResolver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;

/** Dedicated two-stage command boundary, separate from proposal editing and generation. */
@RestController
@RequestMapping("/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}")
public class ReformulationAdoptionController {
    private final ReformulationAdoptionService service;
    private final WorkspaceResolver resolver;
    public ReformulationAdoptionController(ReformulationAdoptionService service,WorkspaceResolver resolver){this.service=service;this.resolver=resolver;}
    @PostMapping("/adoption-previews") public ResponseEntity<Preview> preview(@PathVariable Long projectId,@PathVariable Long requirementId,
            @PathVariable String proposalId,@RequestHeader(value="If-Match",required=false) String expected) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(service.preview(projectId,requirementId,proposalId,
                expected(expected),resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @GetMapping("/adoption-previews/{previewId}") public ResponseEntity<Preview> read(@PathVariable Long projectId,@PathVariable Long requirementId,
            @PathVariable String proposalId,@PathVariable String previewId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.readPreview(projectId,requirementId,proposalId,previewId,
                resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @PostMapping("/adoptions") public ResponseEntity<Result> adopt(@PathVariable Long projectId,@PathVariable Long requirementId,
            @PathVariable String proposalId,@RequestHeader(value="If-Match",required=false) String expected,@RequestBody ConfirmRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.adopt(projectId,requirementId,proposalId,expected(expected),request,
                resolver.resolveCurrentUsername(),resolver.resolveCurrentContext()));
    }
    @GetMapping("/adoptions") public ResponseEntity<List<Result>> history(@PathVariable Long projectId,@PathVariable Long requirementId,@PathVariable String proposalId) {
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
