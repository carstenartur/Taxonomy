package com.taxonomy.templates;

import com.taxonomy.templates.DocumentTemplateWebDavLockManager.LockedWriteResult;
import com.taxonomy.templates.DocumentTemplateWebDavLockManager.TemplateLock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTemplateWebDavLockManagerTest {

    @Test
    void successfulCommitCannotBeReportedAsFailedWhenTheOriginalLeaseExpires()
            throws Exception {
        MutableClock clock = new MutableClock(
                Instant.parse("2026-08-22T12:00:00Z"));
        DocumentTemplateWebDavLockManager manager =
                new DocumentTemplateWebDavLockManager(clock);
        TemplateLock lock = manager.acquire(
                "decision-report",
                "admin",
                "version-a",
                Duration.ofSeconds(1),
                null);

        String value = manager.executeWrite(
                "decision-report",
                lock.token(),
                "admin",
                null,
                expected -> {
                    assertThat(expected).isEqualTo("version-a");
                    clock.advance(Duration.ofMinutes(30));
                    return new LockedWriteResult<>("saved", "version-b");
                });

        assertThat(value).isEqualTo("saved");
        TemplateLock refreshed = manager.require(
                "decision-report", lock.token(), "admin");
        assertThat(refreshed.currentCommit()).isEqualTo("version-b");
        assertThat(refreshed.expiresAt())
                .isEqualTo(Instant.parse("2026-08-22T12:45:00Z"));
    }

    @Test
    void acquireRejectsMissingOrBlankResourceBeforePersistingLockState() {
        DocumentTemplateWebDavLockManager manager =
                new DocumentTemplateWebDavLockManager();

        for (String resource : java.util.Arrays.asList(null, "", " ", "\t")) {
            assertThatThrownBy(() -> manager.acquire(
                    resource, "admin", "version-a", Duration.ofMinutes(5), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("resource");
        }

        assertThat(manager.find("decision-report")).isNull();
    }

    @Test
    void acquireRejectsMissingOrBlankOwnerBeforePersistingLockState() {
        DocumentTemplateWebDavLockManager manager =
                new DocumentTemplateWebDavLockManager();

        for (String owner : java.util.Arrays.asList(null, "", " ", "\t")) {
            assertThatThrownBy(() -> manager.acquire(
                    "decision-report", owner, "version-a", Duration.ofMinutes(5), null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("owner");
        }

        assertThat(manager.find("decision-report")).isNull();
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
