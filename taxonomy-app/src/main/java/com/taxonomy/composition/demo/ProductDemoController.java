package com.taxonomy.composition.demo;

import com.taxonomy.composition.demo.ProductDemoService.DemoComparison;
import com.taxonomy.composition.demo.ProductDemoService.DemoRunView;
import com.taxonomy.workspace.service.WorkspaceResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

/** Isolated, labelled product demo without external AI calls (issue #1184). */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@RestController
@RequestMapping("/api/product-demo")
@Tag(name = "Product Demo")
public class ProductDemoController {

    /** Optional scenario selection; the default scenario is used when absent. */
    public record StartDemoRequest(String scenarioId) { }

    public record DemoScenarioInfo(String command, String defaultScenario, List<Map<String, Object>> scenarios) { }

    private final ProductDemoService demos;
    private final WorkspaceResolver workspaceResolver;

    public ProductDemoController(ProductDemoService demos, WorkspaceResolver workspaceResolver) {
        this.demos = demos;
        this.workspaceResolver = workspaceResolver;
    }

    @GetMapping("/scenarios")
    @Operation(summary = "List product-demo scenarios",
            description = "Lists the versioned, classpath-owned demo scenarios and the exact start command. Reading the list never creates projects and never calls an AI provider.")
    public DemoScenarioInfo scenarios() {
        var catalog = demos.catalog();
        return new DemoScenarioInfo(catalog.command(), catalog.defaultScenario().id(), catalog.scenarios().stream()
                .map(s -> Map.<String, Object>of("id", s.id(), "version", s.version(), "title", s.title(),
                        "banner", s.banner(), "requirementText", s.r0Text()))
                .toList());
    }

    @PostMapping("/runs")
    @Operation(summary = "Start an isolated product demo",
            description = "Validates the scenario against the live catalogue and creates a new labelled demo project with the scenario requirement R0. Existing drafts, projects and requirements are never modified. All AI replies of the run come only from the strict scenario replay; there is no live fallback.")
    public ResponseEntity<DemoRunView> start(@RequestBody(required = false) StartDemoRequest request) {
        var run = demos.start(request == null ? null : request.scenarioId(),
                workspaceResolver.resolveCurrentUsername(), workspaceResolver.resolveCurrentContext());
        return ResponseEntity.created(URI.create("/api/product-demo/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs")
    @Operation(summary = "List product demos in the current workspace",
            description = "Lists the caller's demo runs in the current workspace, including the replay provider currently bound to each demo requirement version. With projectId, returns at most the one demo run of that project (empty for ordinary projects).")
    public List<DemoRunView> list(@RequestParam(required = false) Long projectId) {
        return demos.list(projectId, workspaceResolver.resolveCurrentUsername(), workspaceResolver.resolveCurrentContext());
    }

    @GetMapping("/runs/{runId}")
    @Operation(summary = "Read one product demo",
            description = "Returns the demo banner, stations, visible demo answers and review decisions, and the replay iteration bound to the current requirement version. The read never starts work.")
    public DemoRunView get(@PathVariable String runId) {
        return demos.get(runId, workspaceResolver.resolveCurrentUsername(), workspaceResolver.resolveCurrentContext());
    }

    @GetMapping("/runs/{runId}/comparison")
    @Operation(summary = "Evaluate a product demo against its independent reference",
            description = "Compares the latest successful A0 and A1 demo analyses with the separately versioned reference annotation. Analyses that have not run are reported as NOT_EXECUTED and never as negative findings.")
    public DemoComparison comparison(@PathVariable String runId) {
        return demos.compare(runId, workspaceResolver.resolveCurrentUsername(), workspaceResolver.resolveCurrentContext());
    }

    @PostMapping("/runs/{runId}/close")
    @Operation(summary = "Close a product demo",
            description = "Marks the demo run as closed so no further replay analyses are admitted. The demo project and its evidence remain available and unchanged.")
    public DemoRunView close(@PathVariable String runId) {
        return demos.close(runId, workspaceResolver.resolveCurrentUsername(), workspaceResolver.resolveCurrentContext());
    }
}
