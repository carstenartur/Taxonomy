package com.taxonomy.shared.config;

import org.junit.jupiter.api.Test;

import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OracleHypothesisSessionColumnMigratorTest {

    @Test
    void canonicalizesUnquotedLogicalNamesToOracleMetadataCase() throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(metadata.storesUpperCaseIdentifiers()).thenReturn(true);

        assertThat(OracleHypothesisSessionColumnMigrator.canonicalIdentifier(
                metadata, "analysis_session_id"))
                .isEqualTo("ANALYSIS_SESSION_ID");
        assertThat(OracleHypothesisSessionColumnMigrator.canonicalIdentifier(
                metadata, "idx_hyp_session"))
                .isEqualTo("IDX_HYP_SESSION");
    }

    @Test
    void preservesIdentifierCaseWhenDatabaseDoesNotFoldUnquotedNames()
            throws Exception {
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);

        assertThat(OracleHypothesisSessionColumnMigrator.canonicalIdentifier(
                metadata, "analysis_session_id"))
                .isEqualTo("analysis_session_id");
    }

    @Test
    void recognizesOnlyNationalCharacterSourceTypes() {
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType("NVARCHAR2")).isTrue();
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType("NCHAR")).isTrue();
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType("VARCHAR2")).isFalse();
        assertThat(OracleHypothesisSessionColumnMigrator
                .isNationalCharacterType(null)).isFalse();
    }
}
