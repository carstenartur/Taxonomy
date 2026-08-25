package com.taxonomy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the optimistic-lock ordering of the shared role/state browser fixture. */
class UiRoleStateIsolationContractTest {

    @Test
    void loadsTheServerDraftRevisionBeforeDeletingSharedScenarioState()
            throws IOException {
        String source = Files.readString(findRepositoryFile(
                ".github/scripts/ui-role-state-isolation.mjs"));

        int reload = source.indexOf(
                "await window.TaxonomyAnalysisSession.reload()");
        int synchronizedRevision = source.indexOf(
                "state.conflict === false", reload);
        int invalidate = source.indexOf("session.invalidate({", synchronizedRevision);
        int persistedDeletion = source.indexOf("await session.saveNow()", invalidate);
        int finalConflictGuard = source.indexOf(
                "state?.conflict === false", persistedDeletion);

        assertThat(reload)
                .as("the fixture must load the exact server-side draft revision")
                .isGreaterThanOrEqualTo(0);
        assertThat(synchronizedRevision)
                .as("the fixture must wait until forced draft restoration is conflict-free")
                .isGreaterThan(reload);
        assertThat(invalidate)
                .as("draft invalidation must happen only after version synchronization")
                .isGreaterThan(synchronizedRevision);
        assertThat(persistedDeletion)
                .as("the isolated empty state must be persisted before the scenario starts")
                .isGreaterThan(invalidate);
        assertThat(finalConflictGuard)
                .as("the fixture must not hand a conflicted session to a browser scenario")
                .isGreaterThan(persistedDeletion);
    }

    private static Path findRepositoryFile(String relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "Repository file not found from the current working directory: "
                        + relativePath);
    }
}
