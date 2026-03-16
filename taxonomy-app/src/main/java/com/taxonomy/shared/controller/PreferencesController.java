package com.taxonomy.shared.controller;

import com.taxonomy.preferences.PreferencesService;
import com.taxonomy.preferences.storage.PreferencesCommit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST API for managing application preferences.
 *
 * <p>Preferences are persisted in a dedicated JGit repository ({@code "taxonomy-preferences"})
 * separate from the Architecture DSL history. Every {@code PUT} creates a new Git commit,
 * giving a full audit trail of all preference changes.
 *
 * <p>All endpoints require the {@code ADMIN} role.
 */
@RestController
@RequestMapping("/api/preferences")
@Tag(name = "Preferences")
public class PreferencesController {

    private static final Logger log = LoggerFactory.getLogger(PreferencesController.class);

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    /**
     * Returns all current preferences. The {@code dsl.remote.token} value is masked.
     */
    @GetMapping
    @Operation(summary = "Get all preferences (token is masked)")
    public ResponseEntity<Map<String, Object>> getAll() {
        return ResponseEntity.ok(preferencesService.getAll());
    }

    /**
     * Updates one or more preference values. Creates a new Git commit with the full
     * preferences JSON so the change is auditable.
     *
     * @param changes       partial map of settings to update
     * @param authentication the current authenticated user (used as commit author)
     */
    @PutMapping
    @Operation(summary = "Update preferences (creates a new Git commit)")
    public ResponseEntity<Map<String, Object>> update(
            @RequestBody Map<String, Object> changes,
            Authentication authentication) {
        try {
            String author = authentication != null ? authentication.getName() : "unknown";
            preferencesService.update(changes, author);
            return ResponseEntity.ok(preferencesService.getAll());
        } catch (IOException e) {
            log.error("Failed to persist preferences update", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Resets all preferences to their application.properties defaults and commits to JGit.
     */
    @PostMapping("/reset")
    @Operation(summary = "Reset preferences to defaults")
    public ResponseEntity<Map<String, Object>> reset(Authentication authentication) {
        try {
            String author = authentication != null ? authentication.getName() : "unknown";
            preferencesService.resetToDefaults(author);
            return ResponseEntity.ok(preferencesService.getAll());
        } catch (IOException e) {
            log.error("Failed to reset preferences", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Returns the commit history of the preferences repository, newest first.
     */
    @GetMapping("/history")
    @Operation(summary = "Get preferences change history")
    public ResponseEntity<List<PreferencesCommit>> getHistory() {
        try {
            return ResponseEntity.ok(preferencesService.getHistory());
        } catch (IOException e) {
            log.error("Failed to retrieve preferences history", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
