package com.taxonomy.portfolio.reformulation;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.DriverManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReformulationRecoveryClockTest {
    @Test
    void recoveryAndUsageReadTheDatabaseInstantInAnOffsetSession() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:hsqldb:mem:reformulation_clock", "SA", "");
             var query = connection.createStatement()) {
            query.execute("SET TIME ZONE INTERVAL '+05:30' HOUR TO MINUTE");
            Instant before = Instant.now();
            try (var rows = query.executeQuery("VALUES (CURRENT_TIMESTAMP)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject(1, OffsetDateTime.class).getOffset()).isEqualTo(ZoneOffset.ofHoursMinutes(5, 30));
            }

            var entityManager = mock(EntityManager.class);
            var session = mock(Session.class);
            when(entityManager.unwrap(Session.class)).thenReturn(session);
            when(session.doReturningWork(any())).thenAnswer(call ->
                    call.<ReturningWork<Instant>>getArgument(0).execute(connection));
            var recovery = new ReformulationRecoveryService(null, null, null, null, 1000, 1);
            ReflectionTestUtils.setField(recovery, "em", entityManager);

            assertThat(recovery.databaseTime("run")).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        }
    }
}
