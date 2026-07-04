package com.taxonomy.shared.controller;

import com.taxonomy.shared.extension.ExtensionDescriptor;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.ExtensionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;

/**
 * Read-only REST API for registered extension descriptors.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/extensions} — List all registered extensions ordered by kind (enum order) then by ID</li>
 *   <li>{@code GET /api/extensions/{kind}} — List extensions for a specific kind</li>
 * </ul>
 *
 * <p>Only descriptor metadata is exposed; implementation classes, bean names,
 * and internal object references are never included in the response.
 */
@RestController
@RequestMapping("/api/extensions")
@Tag(name = "Extensions")
public class ExtensionApiController {

    private final ExtensionRegistry extensionRegistry;

    public ExtensionApiController(ExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    /**
     * Lists all registered extension descriptors across every {@link ExtensionKind}.
     *
     * @return flat list of descriptors ordered by kind (enum order) then by ID
     */
    @Operation(
            summary = "List all registered extensions",
            description = "Returns descriptor metadata for every registered internal extension point. " +
                          "Results are ordered by kind (enum declaration order) and then by ID. " +
                          "Implementation class names and internal bean references are never exposed.",
            tags = {"Extensions"})
    @ApiResponse(responseCode = "200", description = "List of extension descriptors")
    @GetMapping
    public ResponseEntity<List<ExtensionDescriptor>> listAll() {
        return ResponseEntity.ok(extensionRegistry.listAll());
    }

    /**
     * Lists all registered extension descriptors for the given {@link ExtensionKind}.
     *
     * @param kind the extension kind name (case-insensitive), e.g. {@code EXPORT_FORMAT}
     * @return list of descriptors for the requested kind, sorted by ID
     */
    @Operation(
            summary = "List extensions by kind",
            description = "Returns descriptor metadata for all registered extensions of the specified kind. " +
                          "The kind parameter is matched case-insensitively.",
            tags = {"Extensions"})
    @ApiResponse(responseCode = "200", description = "List of extension descriptors for the requested kind")
    @ApiResponse(responseCode = "404", description = "Unknown extension kind")
    @GetMapping("/{kind}")
    public ResponseEntity<List<ExtensionDescriptor>> listByKind(
            @Parameter(description = "Extension kind, e.g. EXPORT_FORMAT") @PathVariable String kind) {
        ExtensionKind extensionKind;
        try {
            extensionKind = ExtensionKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(extensionRegistry.listByKind(extensionKind));
    }
}
