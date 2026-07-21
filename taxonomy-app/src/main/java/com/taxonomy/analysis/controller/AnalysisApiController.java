package com.taxonomy.analysis.controller;

import com.taxonomy.analysis.usecase.AnalysisStreamEvent;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenCommand;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenResult;
import com.taxonomy.analysis.usecase.AnalyzeNodeChildrenUseCase;
import com.taxonomy.analysis.usecase.AnalyzeRequirementCommand;
import com.taxonomy.analysis.usecase.AnalyzeRequirementResult;
import com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase;
import com.taxonomy.analysis.usecase.JustifyLeafCommand;
import com.taxonomy.analysis.usecase.JustifyLeafResult;
import com.taxonomy.analysis.usecase.JustifyLeafUseCase;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisCommand;
import com.taxonomy.analysis.usecase.StreamRequirementAnalysisUseCase;
import com.taxonomy.analysis.usecase.UnknownAnalysisProviderException;
import com.taxonomy.catalog.service.TaxonomyService;
import com.taxonomy.dto.AnalysisRequest;
import com.taxonomy.dto.AnalysisResult;
import com.taxonomy.versioning.service.RepositoryStateService;
import com.taxonomy.workspace.service.WorkspaceContext;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * HTTP/SSE transport adapter for requirement analysis. Business orchestration is
 * delegated to typed application use cases.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Analysis")
public class AnalysisApiController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisApiController.class);

    private final TaxonomyService taxonomyService;
    private final ExecutorService analysisExecutor;
    private final ObjectMapper objectMapper;
    private final AnalyzeRequirementUseCase analyzeRequirementUseCase;
    private final StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase;
    private final AnalyzeNodeChildrenUseCase analyzeNodeChildrenUseCase;
    private final JustifyLeafUseCase justifyLeafUseCase;
    private final AnalysisSseEventMapper analysisSseEventMapper;
    private final RepositoryStateService repositoryStateService;
    private final WorkspaceResolver workspaceResolver;
    private final MessageSource messageSource;

    public AnalysisApiController(TaxonomyService taxonomyService,
                                 ExecutorService analysisExecutor,
                                 ObjectMapper objectMapper,
                                 AnalyzeRequirementUseCase analyzeRequirementUseCase,
                                 StreamRequirementAnalysisUseCase streamRequirementAnalysisUseCase,
                                 AnalyzeNodeChildrenUseCase analyzeNodeChildrenUseCase,
                                 JustifyLeafUseCase justifyLeafUseCase,
                                 AnalysisSseEventMapper analysisSseEventMapper,
                                 RepositoryStateService repositoryStateService,
                                 WorkspaceResolver workspaceResolver,
                                 MessageSource messageSource) {
        this.taxonomyService = taxonomyService;
        this.analysisExecutor = analysisExecutor;
        this.objectMapper = objectMapper;
        this.analyzeRequirementUseCase = analyzeRequirementUseCase;
        this.streamRequirementAnalysisUseCase = streamRequirementAnalysisUseCase;
        this.analyzeNodeChildrenUseCase = analyzeNodeChildrenUseCase;
        this.justifyLeafUseCase = justifyLeafUseCase;
        this.analysisSseEventMapper = analysisSseEventMapper;
        this.repositoryStateService = repositoryStateService;
        this.workspaceResolver = workspaceResolver;
        this.messageSource = messageSource;
    }

    @Operation(summary = "Analyze business requirement",
            description = "Analyzes a business requirement against the taxonomy using the configured LLM provider. Optionally includes an architecture view.",
            tags = {"Analysis"})
    @ApiResponse(responseCode = "200", description = "Analysis completed")
    @ApiResponse(responseCode = "400", description = "Business text is blank or the provider is unknown")
    @PostMapping("/analyze")
    public ResponseEntity<AnalysisResult> analyze(@RequestBody AnalysisRequest request) {
        ResponseEntity<AnalysisResult> guard = checkInitialized();
        if (guard != null) return guard;
        if (request == null || request.getBusinessText() == null
                || request.getBusinessText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            String username = workspaceResolver.resolveCurrentUsername();
            AnalyzeRequirementResult result = analyzeRequirementUseCase.analyze(
                    new AnalyzeRequirementCommand(
                            request.getBusinessText(),
                            request.isIncludeArchitectureView(),
                            request.getMaxArchitectureNodes(),
                            request.getProvider(),
                            username,
                            resolveWorkspaceContext(username)));
            return ResponseEntity.ok(result.analysisResult());
        } catch (UnknownAnalysisProviderException e) {
            @SuppressWarnings("unchecked")
            ResponseEntity<AnalysisResult> badProvider = (ResponseEntity<AnalysisResult>)
                    (ResponseEntity<?>) ResponseEntity.badRequest().body(Map.of(
                            "error", "Unknown provider: " + e.getProvider(),
                            "validProviders", e.getValidProviders()));
            return badProvider;
        }
    }

    @Operation(summary = "Streaming analysis (SSE)",
            description = "Emits phase, scores, expanding, complete, and error events as the taxonomy is processed.",
            tags = {"Analysis"})
    @GetMapping(value = "/analyze-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(
            @Parameter(description = "Business requirement text to analyze")
            @RequestParam String businessText,
            @Parameter(description = "LLM provider override")
            @RequestParam(required = false) String provider) {
        SseEmitter emitter = new SseEmitter(120_000L);

        if (!taxonomyService.isInitialized()) {
            sendEvent(emitter, "error", Map.of(
                    "status", "ERROR",
                    "errorMessage", messageSource.getMessage(
                            "error.loading", null,
                            "Taxonomy data is still loading. Please wait.",
                            LocaleContextHolder.getLocale()),
                    "initStatus", taxonomyService.getInitStatus()));
            emitter.complete();
            return emitter;
        }
        if (businessText == null || businessText.isBlank()) {
            sendEvent(emitter, "error", Map.of(
                    "status", "ERROR",
                    "errorMessage", "businessText must not be blank"));
            emitter.complete();
            return emitter;
        }

        StreamRequirementAnalysisCommand command = new StreamRequirementAnalysisCommand(
                businessText, provider, LocaleContextHolder.getLocale());
        analysisExecutor.execute(() -> {
            try {
                streamRequirementAnalysisUseCase.stream(command, event -> {
                    AnalysisSseEventMapper.MappedEvent mapped = analysisSseEventMapper.map(event);
                    sendEvent(emitter, mapped.name(), mapped.payload());
                    if (event instanceof AnalysisStreamEvent.Complete
                            || event instanceof AnalysisStreamEvent.Error) {
                        emitter.complete();
                    }
                });
            } catch (UnknownAnalysisProviderException e) {
                sendEvent(emitter, "error", Map.of(
                        "status", "ERROR",
                        "errorMessage", "Unknown provider: " + e.getProvider()));
                emitter.complete();
            } catch (Exception e) {
                log.error("Streaming analysis failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @Operation(summary = "Analyze single node children",
            description = "Analyzes the children of a taxonomy node against a requirement",
            tags = {"Analysis"})
    @GetMapping("/analyze-node")
    public ResponseEntity<AnalyzeNodeChildrenResult> analyzeNode(
            @RequestParam String parentCode,
            @RequestParam String businessText,
            @RequestParam(defaultValue = "100") int parentScore) {
        ResponseEntity<AnalyzeNodeChildrenResult> guard = checkInitialized();
        if (guard != null) return guard;
        try {
            return ResponseEntity.ok(analyzeNodeChildrenUseCase.analyze(
                    new AnalyzeNodeChildrenCommand(parentCode, businessText, parentScore)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    public record JustifyLeafRequest(
            String nodeCode,
            String businessText,
            Map<String, Integer> scores,
            Map<String, String> reasons) {
    }

    @Operation(summary = "Generate leaf justification",
            description = "Generates a traceable explanation for a leaf-node match",
            tags = {"Analysis"})
    @ApiResponse(responseCode = "200", description = "Justification generated")
    @ApiResponse(responseCode = "400", description = "Request is invalid")
    @ApiResponse(responseCode = "500", description = "Justification generation failed")
    @PostMapping("/justify-leaf")
    public ResponseEntity<?> justifyLeaf(@RequestBody JustifyLeafRequest request) {
        ResponseEntity<JustifyLeafResult> guard = checkInitialized();
        if (guard != null) return guard;
        try {
            JustifyLeafResult result = justifyLeafUseCase.justify(new JustifyLeafCommand(
                    request.nodeCode(), request.businessText(), request.scores(), request.reasons()));
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST, e.getMessage());
            problem.setTitle("Invalid leaf justification request");
            problem.setType(URI.create("urn:taxonomy:problem:invalid-justification-request"));
            return ResponseEntity.badRequest().body(problem);
        } catch (Exception e) {
            log.error("Leaf justification failed for node {}",
                    request != null ? request.nodeCode() : null, e);
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "The leaf justification could not be generated. Retry the operation or inspect the administrator diagnostics.");
            problem.setTitle("Leaf justification failed");
            problem.setType(URI.create("urn:taxonomy:problem:justification-failed"));
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
        }
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    private WorkspaceContext resolveWorkspaceContext(String username) {
        try {
            repositoryStateService.ensureWorkspaceState(username);
            return workspaceResolver.resolveCurrentContext();
        } catch (Exception e) {
            log.warn("Falling back to shared workspace context for user '{}' due to: {}",
                    username, e.toString(), e);
            return WorkspaceContext.SHARED;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ResponseEntity<T> checkInitialized() {
        if (!taxonomyService.isInitialized()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", messageSource.getMessage(
                    "error.loading", null,
                    "Taxonomy data is still loading. Please wait.",
                    LocaleContextHolder.getLocale()));
            body.put("status", taxonomyService.getInitStatus());
            return (ResponseEntity<T>) ResponseEntity.status(503).body(body);
        }
        return null;
    }
}
