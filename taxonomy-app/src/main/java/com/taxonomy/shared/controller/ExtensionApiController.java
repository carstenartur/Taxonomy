package com.taxonomy.shared.controller;

import com.taxonomy.shared.extension.ExtensionDescriptor;
import com.taxonomy.shared.extension.ExtensionKind;
import com.taxonomy.shared.extension.runtime.ExtensionRegistry;
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

/** Read-only REST API exposing extension descriptors, never implementation types. */
@RestController
@RequestMapping("/api/extensions")
@Tag(name = "Extensions")
public class ExtensionApiController {

    private final ExtensionRegistry extensionRegistry;

    public ExtensionApiController(ExtensionRegistry extensionRegistry) {
        this.extensionRegistry = extensionRegistry;
    }

    @Operation(
            summary = "List all registered extensions",
            description = "Returns descriptor metadata for every registered internal extension point.",
            tags = {"Extensions"})
    @ApiResponse(responseCode = "200", description = "List of extension descriptors")
    @GetMapping
    public ResponseEntity<List<ExtensionDescriptor>> listAll() {
        return ResponseEntity.ok(extensionRegistry.listAll());
    }

    @Operation(
            summary = "List extensions by kind",
            description = "Returns descriptor metadata for the specified extension kind.",
            tags = {"Extensions"})
    @ApiResponse(responseCode = "200", description = "List of extension descriptors")
    @ApiResponse(responseCode = "404", description = "Unknown extension kind")
    @GetMapping("/{kind}")
    public ResponseEntity<List<ExtensionDescriptor>> listByKind(
            @Parameter(description = "Extension kind, e.g. EXPORT_FORMAT")
            @PathVariable String kind) {
        try {
            ExtensionKind extensionKind =
                    ExtensionKind.valueOf(kind.trim().toUpperCase(Locale.ROOT));
            return ResponseEntity.ok(extensionRegistry.listByKind(extensionKind));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
