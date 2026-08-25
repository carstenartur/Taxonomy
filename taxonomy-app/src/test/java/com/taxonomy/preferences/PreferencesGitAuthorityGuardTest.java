package com.taxonomy.preferences;

import com.taxonomy.preferences.storage.PreferencesGitRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PreferencesGitAuthorityGuardTest {

    @Test
    void acceptsOneReadableValidatedGitSnapshot() throws Exception {
        PreferencesService service = mock(PreferencesService.class);
        when(service.getAll()).thenReturn(java.util.Map.of("llm.rpm", 5));
        PreferencesGitAuthorityGuard guard = guard(service, new SnapshotRepository(
                "{\"llm.rpm\":5,\"dsl.default-branch\":\"draft\"}"));

        assertThatCode(guard::validateAuthority).doesNotThrowAnyException();
    }

    @Test
    void refusesStartupWhenFirstCommitDidNotBecomeAuthoritative() throws Exception {
        PreferencesService service = mock(PreferencesService.class);
        when(service.getAll()).thenReturn(java.util.Map.of("llm.rpm", 5));
        PreferencesGitAuthorityGuard guard = guard(service, new SnapshotRepository(null));

        assertThatThrownBy(guard::validateAuthority)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup refused")
                .hasMessageContaining("Git-authoritative preferences");
    }

    @Test
    void refusesUnreadableMalformedOrUnknownSnapshots() throws Exception {
        PreferencesService service = mock(PreferencesService.class);
        when(service.getAll()).thenReturn(java.util.Map.of("llm.rpm", 5));

        assertThatThrownBy(() -> guard(service,
                new FailingReadRepository()).validateAuthority())
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(() -> guard(service,
                new SnapshotRepository("not-json")).validateAuthority())
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard(service,
                new SnapshotRepository("{\"unknown.setting\":true}"))
                .validateAuthority())
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private static PreferencesGitAuthorityGuard guard(
            PreferencesService service,
            PreferencesGitRepository repository) {
        return new PreferencesGitAuthorityGuard(
                service,
                repository,
                JsonMapper.builder().build(),
                new PreferencesSchema());
    }

    private static final class SnapshotRepository extends PreferencesGitRepository {
        private final String snapshot;

        private SnapshotRepository(String snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public String readHead() {
            return snapshot;
        }
    }

    private static final class FailingReadRepository extends PreferencesGitRepository {
        @Override
        public String readHead() throws IOException {
            throw new IOException("simulated repository read failure");
        }
    }
}
