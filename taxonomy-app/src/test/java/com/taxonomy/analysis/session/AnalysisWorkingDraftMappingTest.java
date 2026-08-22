package com.taxonomy.analysis.session;

import jakarta.persistence.Lob;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/** Locks the high-frequency draft payload to a materialized, dialect-sized string. */
class AnalysisWorkingDraftMappingTest {

    @Test
    void payloadUsesLongTextInsteadOfAJdbcLobLocator() throws Exception {
        Field payload = AnalysisWorkingDraft.class.getDeclaredField("payloadJson");

        assertThat(payload.getAnnotation(Lob.class)).isNull();
        JdbcTypeCode jdbcType = payload.getAnnotation(JdbcTypeCode.class);
        assertThat(jdbcType).isNotNull();
        assertThat(jdbcType.value()).isEqualTo(SqlTypes.LONG32VARCHAR);
    }
}
