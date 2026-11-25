package com.taxonomy.preferences.storage;

import com.taxonomy.analysis.service.PromptTemplateOverrideStore;
import io.github.carstenartur.jgit.storage.hibernate.HibernateRepositoryFactory;
import org.eclipse.jgit.lib.ObjectId;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Shared prompt overrides in a repository separate from the public preferences API. */
public final class GitPromptOverrideStore implements PromptTemplateOverrideStore, AutoCloseable {
    private static final String REPOSITORY_NAME = "taxonomy-prompt-overrides";
    private static final int MAX_UPDATE_ATTEMPTS = 8;
    private static final TypeReference<Map<String, String>> OVERRIDES = new TypeReference<>() { };

    private final PreferencesGitRepository repository;
    private final ObjectMapper json;
    private volatile Snapshot cached = new Snapshot(null, Map.of());

    public GitPromptOverrideStore(HibernateRepositoryFactory factory, ObjectMapper json) {
        this(new PreferencesGitRepository(factory, REPOSITORY_NAME), json);
    }

    GitPromptOverrideStore(PreferencesGitRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    @Override
    public Map<String, String> snapshot() {
        try {
            return load().values();
        } catch (IOException | RuntimeException failure) {
            throw new UnavailableException(failure);
        }
    }

    @Override
    public void save(String code, String template) {
        update(code, Objects.requireNonNull(template));
    }

    @Override
    public void reset(String code) {
        update(code, null);
    }

    private Snapshot load() throws IOException {
        ObjectId revision = repository.sharedRevision();
        Snapshot current = cached;
        if (Objects.equals(revision, current.revision())) return current;
        Map<String, String> values = revision == null ? Map.of()
                : Map.copyOf(json.readValue(repository.readRevision(revision), OVERRIDES));
        Snapshot loaded = new Snapshot(revision, values);
        cached = loaded;
        return loaded;
    }

    private void update(String code, String template) {
        try {
            for (int attempt = 0; attempt < MAX_UPDATE_ATTEMPTS; attempt++) {
                Snapshot current = load();
                if (Objects.equals(current.values().get(code), template)) return;
                var updated = new TreeMap<>(current.values());
                if (template == null) updated.remove(code);
                else updated.put(code, template);
                String committed = repository.commitIfUnchanged(json.writeValueAsString(updated), current.revision(),
                        "taxonomy", template == null ? "Reset prompt template override" : "Save prompt template override");
                if (committed != null) {
                    cached = new Snapshot(ObjectId.fromString(committed), Map.copyOf(updated));
                    return;
                }
            }
            throw new IOException("The prompt overrides branch changed during each update attempt");
        } catch (IOException | RuntimeException failure) {
            throw new UnavailableException(failure);
        }
    }

    @Override
    public void close() {
        repository.close();
    }

    private record Snapshot(ObjectId revision, Map<String, String> values) { }
}
