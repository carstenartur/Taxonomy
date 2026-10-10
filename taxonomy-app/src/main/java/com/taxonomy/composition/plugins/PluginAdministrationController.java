package com.taxonomy.composition.plugins;

import com.taxonomy.extension.api.plugin.PluginIdentity;
import com.taxonomy.extension.runtime.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;
import java.util.List;

/** Auth/CSRF are enforced by the existing shared /api/admin rules in both security modes. */
@RestController
@RequestMapping("/api/admin/plugins")
@Tag(name = "Plugin Administration")
public final class PluginAdministrationController {
    private final PluginLifecycleCoordinator lifecycle;
    public PluginAdministrationController(PluginLifecycleCoordinator lifecycle) { this.lifecycle = lifecycle; }

    @GetMapping
    @Operation(summary = "Inspect installed extension plugins", description = "ADMIN-only. Startup feature packages are not hot-loadable. Paths and provider configuration are never exposed.")
    @ApiResponse(responseCode = "200", description = "Current plugin identities and lifecycle policy")
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role required", content = @Content)
    public AdministrationView list() { return new AdministrationView(lifecycle.enabled(), lifecycle.clustered(), lifecycle.installed()); }
    public record AdministrationView(boolean dynamicEnabled, boolean clustered,
                                     List<PluginLifecycleCoordinator.InstalledPlugin> plugins) { }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate an operator-installed dynamic plugin", description = "Selects a manifest ID from the configured local directory. Does not accept uploads, URLs, classes or paths. Disabled by default and rejected in clustered deployments.")
    @ApiResponse(responseCode = "200", description = "Exact activated plugin identity")
    @ApiResponse(responseCode = "400", description = "Invalid ID", content = @Content(schema = @Schema(implementation = PluginOperationExceptionHandler.Problem.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role or browser CSRF token missing", content = @Content)
    @ApiResponse(responseCode = "409", description = "Policy, dependency, admission or lifecycle rejection", content = @Content(schema = @Schema(implementation = PluginOperationExceptionHandler.Problem.class)))
    public PluginIdentity activate(@Parameter(description = "Installed manifest ID", required = true,
            schema = @Schema(pattern = "[a-z][a-z0-9.-]{0,127}")) @PathVariable String id) { return lifecycle.activate(id); }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Drain and unload a dynamic plugin", description = "Stops admitting new invocations. Existing leases retain their exact version. A timeout leaves the plugin loaded and draining; repeat after calls finish. Startup plugins and active dependencies cannot be removed.")
    @ApiResponse(responseCode = "200", description = "Stopped and unloaded")
    @ApiResponse(responseCode = "202", description = "Still draining; resources remain available to admitted calls")
    @ApiResponse(responseCode = "400", description = "Invalid ID or timeout", content = @Content(schema = @Schema(implementation = PluginOperationResult.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role or browser CSRF token missing", content = @Content)
    @ApiResponse(responseCode = "409", description = "Policy, dependency or cleanup rejection", content = @Content(schema = @Schema(implementation = PluginOperationResult.class)))
    public ResponseEntity<PluginOperationResult> deactivate(@PathVariable String id,
            @Parameter(description = "Maximum drain wait in milliseconds; 0 returns immediately", schema = @Schema(minimum = "0", maximum = "30000"))
            @RequestParam(defaultValue = "1000") long timeoutMillis) {
        var result = lifecycle.deactivate(id, Duration.ofMillis(timeoutMillis));
        int status = switch (result.status()) {
            case STOPPED -> 200; case DRAINING -> 202;
            case REJECTED -> result.code().startsWith("INVALID_") ? 400 : 409;
        };
        return ResponseEntity.status(status).body(result);
    }
}
