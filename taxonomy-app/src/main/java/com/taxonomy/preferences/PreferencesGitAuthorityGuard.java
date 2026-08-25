package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * Fails startup when the runtime preferences are not backed by one valid Git snapshot.
 *
 * <p>{@link PreferencesService} remains an eager dependency so first-start seeding has
 * completed before this guard runs. The guard executes before document-template seeding;
 * no HTTP-ready application may silently continue with defaults after a repository read,
 * parse, object-write or ref-update failure.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public final class PreferencesGitAuthorityGuard implements ApplicationRunner {

    private final PreferencesService preferences;
    private final PreferencesGitRepository repository;
    private final ObjectMapper objectMapper;
    private final PreferencesSchema schema;

    public PreferencesGitAuthorityGuard(
            PreferencesService preferences,
            PreferencesGitRepository repository,
            ObjectMapper objectMapper,
            PreferencesSchema schema) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    @Override
    public void run(ApplicationArguments arguments) {
        validateAuthority();
    }

    void validateAuthority() {
        // Accessing the eager service documents and enforces the initialization
        // dependency even if the global lazy-initialization policy changes later.
        preferences.getAll();
        try {
            String json = repository.readHead();
            if (json == null || json.isBlank()) {
                throw invalid(null);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = objectMapper.readValue(json, Map.class);
            schema.validateSnapshot(snapshot);
        } catch (IOException | IllegalArgumentException exception) {
            throw invalid(exception);
        }
    }

    private static IllegalStateException invalid(Exception cause) {
        String message = "Application startup refused: Git-authoritative preferences "
                + "are missing, unreadable or invalid.";
        return cause == null
                ? new IllegalStateException(message)
                : new IllegalStateException(message, cause);
    }
}
