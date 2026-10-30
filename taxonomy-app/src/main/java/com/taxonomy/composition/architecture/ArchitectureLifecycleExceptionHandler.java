package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.editor.persistence.EditorJournal.RevisionConflict;
import com.taxonomy.portfolio.service.PortfolioException;
import com.taxonomy.workspace.service.BranchHeadConflictException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

@com.taxonomy.shared.features.ConditionalOnFeature("portfolio")
@RestControllerAdvice(assignableTypes={ArchitectureLifecycleController.class,ArchitectureRequirementController.class,
        ArchitectureRequirementHistoryController.class,ArchitectureLifecycleCreationController.class})
public class ArchitectureLifecycleExceptionHandler {
    @ExceptionHandler(LifecycleHttpContext.PreconditionRequired.class)
    public ResponseEntity<?> missing(){return ResponseEntity.status(428).body(Map.of("code","PRECONDITION_REQUIRED","detail","An exact semantic revision If-Match is required"));}
    @ExceptionHandler(RevisionConflict.class)
    public ResponseEntity<?> revision(RevisionConflict failure){return ResponseEntity.status(412).body(Map.of("code","REVISION_MOVED","detail","Refresh and compare the current architecture stand","expectedRevision",failure.expected(),"currentRevision",failure.actual()));}
    @ExceptionHandler(CommandProblem.class)
    public ResponseEntity<?> command(CommandProblem failure) {
        int status="NOT_FOUND".equals(failure.code())?404:"READ_ONLY".equals(failure.code())?403:
                Set.of("CONTEXT_CHANGED","PREVIEW_CHANGED","REQUIREMENT_VERSION_CHANGED","COMMAND_ID_REUSED","CHECKPOINT_PENDING","CHECKPOINT_CONFLICT","VERSION_CHANGED").contains(failure.code())?409:422;
        return ResponseEntity.status(status).body(Map.of("code",failure.code(),"field",failure.field(),"detail",failure.getMessage(),"dependencies",failure.dependencies()));
    }
    @ExceptionHandler(PortfolioException.class)
    public ResponseEntity<ProblemDetail> portfolio(PortfolioException failure){return new com.taxonomy.portfolio.controller.PortfolioExceptionHandler().handlePortfolioException(failure);}
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalid(IllegalArgumentException failure){return ResponseEntity.badRequest().body(Map.of("code","INVALID_COMMAND","detail",String.valueOf(failure.getMessage())));}
    @ExceptionHandler(BranchHeadConflictException.class)
    public ResponseEntity<?> historyHeadChanged(BranchHeadConflictException failure) {
        var problem = new java.util.LinkedHashMap<String, Object>();
        problem.put("code", "HISTORY_HEAD_CHANGED");
        problem.put("detail", "The branch changed during history search; restart from the first page");
        problem.put("branch", failure.getBranch());
        problem.put("expectedHead", failure.getExpectedHeadCommit());
        problem.put("currentHead", failure.getActualHeadCommit());
        return ResponseEntity.status(409).body(problem);
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<?> unavailable(){return ResponseEntity.status(503).body(Map.of("code","GIT_UNAVAILABLE","detail","Storage is unavailable; retry the same command identity"));}
}
