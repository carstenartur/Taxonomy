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
        assertThat(manager.require(
                "decision-report", lock.token(), "admin").currentCommit())
                .isEqualTo("version-b");
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
