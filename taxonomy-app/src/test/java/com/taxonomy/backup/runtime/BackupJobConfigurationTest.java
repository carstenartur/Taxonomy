package com.taxonomy.backup.runtime;

import com.taxonomy.backup.*;

import com.google.crypto.tink.*;
import com.google.crypto.tink.streamingaead.*;
import com.taxonomy.backup.archive.*;
import com.taxonomy.backup.jobs.*;
import com.taxonomy.backup.snapshot.BackupMaintenanceLease;
import org.hsqldb.jdbc.JDBCDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BackupJobConfigurationTest {
    @TempDir Path root;
    @Test void deploymentKeySurvivesRestartAndCannotBeLoadedFromArchiveStorage() throws Exception {
        StreamingAeadConfig.register();
        var key = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES256_GCM_HKDF_1MB);
        var keyFile = root.resolve("mounted-key.json");
        Files.writeString(keyFile, TinkJsonProtoKeysetFormat.serializeKeyset(key, InsecureSecretKeyAccess.get()));
        var storage = root.resolve("backups");
        var first = DeploymentArchiveProtection.load(keyFile, storage); var next = DeploymentArchiveProtection.load(keyFile, storage);
        var bytes = new ByteArrayOutputStream();
        try (var protectedOutput = first.protect(bytes, new byte[]{4})) { protectedOutput.write("secret".getBytes()); }
        try (var input = next.unprotect(new ByteArrayInputStream(bytes.toByteArray()), new byte[]{4})) {
            assertThat(new String(input.readAllBytes())).isEqualTo("secret");
        }
        Files.createDirectories(storage); var unsafe = storage.resolve("key.json"); Files.copy(keyFile, unsafe);
        assertThatThrownBy(() -> DeploymentArchiveProtection.load(unsafe, storage)).isInstanceOf(IllegalStateException.class);
        Files.writeString(keyFile, "NEVER_ECHO_THIS");
        assertThatThrownBy(() -> DeploymentArchiveProtection.load(keyFile, storage)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Backup key configuration is invalid").hasNoCause();
    }
    @Test void oversizedAndMissingKeysetsFailClosedWithoutGeneratingReplacementKeys() throws Exception {
        var file = root.resolve("key.json");
        assertThatThrownBy(() -> DeploymentArchiveProtection.load(file, root.resolve("backups"))).isInstanceOf(IllegalStateException.class);
        assertThat(file).doesNotExist();
        Files.write(file, new byte[65537]);
        assertThatThrownBy(() -> DeploymentArchiveProtection.load(file, root.resolve("backups"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void jobCompositionRequiresFeatureFlagAndCompleteCaptureSource() {
        var database = new JDBCDataSource(); database.setUrl("jdbc:hsqldb:mem:config-" + UUID.randomUUID()); database.setUser("sa");
        BackupMaintenanceLease.initialize(database);
        var runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BackupJobsConfiguration.class))
                .withBean(BackupMaintenanceLease.class, () -> new BackupMaintenanceLease(database, Duration.ofSeconds(30)))
                .withBean(BackupFeaturePrerequisites.class, BackupFeaturePrerequisites::discover)
                .withBean(BackupAuthorizationService.class, () -> mock(BackupAuthorizationService.class))
                .withPropertyValues("taxonomy.backup.directory=" + root.resolve("backups"));
        runner.withPropertyValues("taxonomy.backup.enabled=true").run(context -> assertThat(context).doesNotHaveBean(BackupJobService.class));
        var complete = runner.withBean(BackupCaptureSource.class, () -> (creation, directory, progress) -> { throw new IOException("fixture"); });
        complete.run(context -> assertThat(context).doesNotHaveBean(BackupJobService.class));
        complete.withPropertyValues("taxonomy.backup.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(BackupJobService.class).hasSingleBean(BackupJobWorker.class);
            assertThat(context.getBean(ArchiveProtectionProvider.class).encrypted()).isFalse();
            assertThat(context.getBean(BackupJobLimits.class).heavyConcurrency()).isEqualTo(1);
            assertThat(context.getBean(BackupJobLimits.class).currentConcurrency()).isEqualTo(2);
        });
    }
}
