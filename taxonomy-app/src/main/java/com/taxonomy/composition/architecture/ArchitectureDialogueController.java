package com.taxonomy.composition.architecture;

import com.taxonomy.dsl.command.ArchitectureDslCommands.CommandProblem;
import com.taxonomy.shared.features.ConditionalOnFeature;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.Map;

/** Answers only against a server-resolved architecture. There is no command/write capability. */
@RestController
@ConditionalOnFeature({"architecture", "analysis"})
public class ArchitectureDialogueController {
    private final WorkspaceResolver resolver;
    private final ArchitectureDialogueService dialogue;
    ArchitectureDialogueController(WorkspaceResolver resolver, ArchitectureDialogueService dialogue) {
        this.resolver = resolver; this.dialogue = dialogue;
    }

    @PostMapping("/api/architecture/dialogue")
    @Operation(summary = "Ask about the displayed architecture and its evidence",
            description = "Answers follow-up questions on a pinned working revision, Git checkpoint, analysis draft or snapshot using the configured generative provider. Each statement cites supplied model facts.")
    public ResponseEntity<ArchitectureDialogue.Answer> ask(@RequestBody ArchitectureDialogue.Request request) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dialogue.ask(resolver.resolveCurrentRepositoryContextForRead(), request));
    }

    @ExceptionHandler(CommandProblem.class)
    ResponseEntity<Map<String,String>> sourceProblem(CommandProblem error) {
        int status = switch (error.code()) { case "NOT_FOUND" -> 404; case "READ_ONLY" -> 403; default -> 409; };
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", error.code(), "detail", error.getMessage()));
    }
}
