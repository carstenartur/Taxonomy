package com.taxonomy.backup.restore;

import com.taxonomy.backup.*;
import com.taxonomy.security.backup.IdentityBackupContributor.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RestoreArchiveRecordsTest {
    private static final String HEADER = "{\"schemaVersion\":1,\"kind\":\"principals\",\"profile\":\"CURRENT_STATE\"}\n";
    private final String principal = "{\"id\":{\"value\":\"00000000-0000-0000-0000-000000000001\"},\"sourceScope\":\"private-source\",\"registryState\":\"REGISTERED\",\"sourceEnabled\":true,\"displayName\":\"Alice\",\"email\":null,\"restorePolicy\":\"EXPLICIT_MAPPING\"}";

    @Test void exactTypedOwnerRecordsRetainEvidenceWithoutInventingTargetAuthority() throws Exception {
        var rows = new ArrayList<PrincipalRecord>();
        RestoreArchiveRecords.read(input(HEADER + principal + "\n"), "principals", BackupProfile.CURRENT_STATE, PrincipalRecord.class, 10, () -> {}, rows::add);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().id()).isEqualTo(new PrincipalId(UUID.fromString("00000000-0000-0000-0000-000000000001")));
        assertThat(rows.getFirst().restorePolicy()).isEqualTo(RestorePolicy.EXPLICIT_MAPPING);
        assertThat(rows.getFirst().sourceScope()).isEqualTo("private-source");
    }

    @Test void malformedHeadersDuplicateFieldsAndInputSuppliedClassNamesFailClosed() {
        for (String document : List.of(
                HEADER.replace("CURRENT_STATE", "INSTALLATION_CURRENT") + principal + "\n",
                HEADER.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1") + principal + "\n",
                HEADER.replace("\"kind\":\"principals\"", "\"kind\":\"bindings\"") + principal + "\n",
                HEADER + principal.replace("\"email\":null", "\"email\":null,\"@class\":\"java.lang.Runtime\"") + "\n",
                HEADER + principal + " {}\n", HEADER + principal, HEADER + "\n")) {
            assertThatThrownBy(() -> RestoreArchiveRecords.read(input(document), "principals", BackupProfile.CURRENT_STATE, PrincipalRecord.class, 10, () -> {}, ignored -> {}))
                    .isInstanceOf(IOException.class).hasMessage("Invalid or unsupported portable restore dataset");
        }
    }

    @Test void actualRowBoundaryRejectsOverflowAndChecksCancellationDuringInput() throws Exception {
        var rows = new ArrayList<PrincipalRecord>();
        assertThatThrownBy(() -> RestoreArchiveRecords.read(input(HEADER + principal + "\n" + principal + "\n"), "principals", BackupProfile.CURRENT_STATE, PrincipalRecord.class, 1, () -> {}, rows::add))
                .isInstanceOf(IOException.class).hasMessageContaining("limit");
        assertThat(rows).hasSize(1);
        try {
            assertThatThrownBy(() -> RestoreArchiveRecords.read(input(HEADER + principal + "\n"), "principals", BackupProfile.CURRENT_STATE, PrincipalRecord.class, 10,
                    () -> { throw new InterruptedIOException("private cancellation payload"); }, ignored -> {}))
                    .isInstanceOf(InterruptedIOException.class).hasMessage("Portable restore inspection was cancelled");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
    @Test void identityUuidEvidenceRequiresCanonicalSourceSpellingRatherThanCoercion() {
        for(String uuid:List.of("1-1-1-1-1","00000000-0000-0000-0000-0000000000AB","AAAAAAAAAAAAAAAAAAAAAQ==")) {
            String document=HEADER+principal.replace("00000000-0000-0000-0000-000000000001",uuid)+"\n";
            assertThatThrownBy(()->RestoreArchiveRecords.read(input(document),"principals",BackupProfile.CURRENT_STATE,PrincipalRecord.class,10,()->{},ignored->{}))
                    .as(uuid).isInstanceOf(IOException.class).hasMessage("Invalid or unsupported portable restore dataset");
        }
    }
    private static InputStream input(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
}
