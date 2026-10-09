package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.taxonomy.composition.backup.ApplicationBackupComponent;
import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.PropertySource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class ApplicationBackupComponentTest {
    @TempDir Path temporary;

    @Test void assemblesEveryReviewedApplicationCategoryWithoutInspectingLiveSources() throws Exception {
        var database = new AbstractDataSource() {
            @Override public Connection getConnection() { throw new AssertionError("Assembly read database"); }
            @Override public Connection getConnection(String username, String password) { throw new AssertionError("Assembly read database"); }
        };
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new PropertySource<Object>("guard") {
            @Override public Object getProperty(String name) { throw new AssertionError("Assembly read deployment"); }
        });
        Path content = temporary.resolve("unopened-content");
        try (var preferences = new PreferencesGitRepository() {
            @Override public Repository getGitRepository() { throw new AssertionError("Assembly read Git"); }
        }) {
            var component = ApplicationBackupComponent.create(database,
                    snapshot -> { throw new AssertionError("Assembly selected source references"); },
                    (snapshot, checkpoint) -> { throw new AssertionError("Assembly selected principal references"); },
                    content, preferences, environment);
            var id = new BackupComponentId("application");
            Set<String> required = BackupCoverageInventory.load().categories().stream()
                    .filter(category -> category.owner().equals(id))
                    .filter(category -> switch (category.rule()) {
                        case PORTABLE_PRIMARY, GIT_PRIMARY, EXTERNAL_DEPENDENCY -> true;
                        case REBUILDABLE, TRANSIENT -> false;
                    }).map(BackupInventory.Category::id).collect(Collectors.toSet());
            assertThat(component.componentId()).isEqualTo(id);
            assertThat(component.schemaVersion()).isEqualTo(1);
            assertThat(required).hasSize(16);
            assertThat(component.categories()).containsExactlyInAnyOrderElementsOf(required);
            assertThat(Files.exists(content)).isFalse();
            assertThat(component.omissions(BackupProfile.INSTALLATION_CURRENT))
                    .anyMatch(value -> value.contains("storage.schema-migrations") && value.contains("REBUILDABLE"))
                    .anyMatch(value -> value.contains("runtime.backup-jobs") && value.contains("TRANSIENT"))
                    .anyMatch(value -> value.startsWith("application.sources:"))
                    .anyMatch(value -> value.startsWith("identities:"))
                    .anyMatch(value -> value.startsWith("preferences:"))
                    .anyMatch(value -> value.startsWith("configuration:"));
            assertThat(component.omissions(BackupProfile.CURRENT_STATE))
                    .anyMatch(value -> value.startsWith("preferences:") && value.contains("outside"))
                    .anyMatch(value -> value.startsWith("configuration:") && value.contains("outside"));
        }
    }
}
