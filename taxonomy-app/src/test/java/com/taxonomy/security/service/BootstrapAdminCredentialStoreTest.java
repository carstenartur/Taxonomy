package com.taxonomy.security.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BootstrapAdminCredentialStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsPosixStagingFileWithOwnerOnlyPermissionsBeforeCredentialWrite()
            throws Exception {
        PosixFileAttributeView directoryView = Files.getFileAttributeView(
                temporaryDirectory, PosixFileAttributeView.class);
        assumeTrue(directoryView != null);
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);

        Path stagingFile = store.createOwnerOnlyStagingFile();
        try {
            assertThat(Files.size(stagingFile)).isZero();
            assertThat(Files.getPosixFilePermissions(stagingFile))
                    .isEqualTo(EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        } finally {
            Files.deleteIfExists(stagingFile);
        }
    }

    @Test
    void publishesOwnerOnlyCredentialAndDeletesIt() throws Exception {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);

        Path file = store.publish("generated-bootstrap-secret");

        assertThat(file.getParent()).isEqualTo(temporaryDirectory.toAbsolutePath());
        assertThat(Files.readString(file, StandardCharsets.UTF_8).strip())
                .isEqualTo("generated-bootstrap-secret");

        PosixFileAttributeView posixView = Files.getFileAttributeView(
                file, PosixFileAttributeView.class);
        if (posixView != null) {
            assertThat(Files.getPosixFilePermissions(file))
                    .isEqualTo(EnumSet.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE));
        }

        store.deletePublishedCredential();

        assertThat(file).doesNotExist();
    }

    @Test
    void secondPublicationAtomicallyReplacesCredentialAtTheSamePath()
            throws Exception {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);

        Path first = store.publish("first-bootstrap-secret");
        Path second = store.publish("second-bootstrap-secret");

        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second, StandardCharsets.UTF_8).strip())
                .isEqualTo("second-bootstrap-secret");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).containsExactly(second);
        }
    }

    @Test
    void restartedStoreDeletesCredentialPublishedForTheSameDataSource() {
        BootstrapAdminCredentialStore firstProcess =
                new BootstrapAdminCredentialStore(
                        temporaryDirectory,
                        "jdbc:hsqldb:file:restart-test");
        Path published = firstProcess.publish("restart-bootstrap-secret");

        BootstrapAdminCredentialStore restartedProcess =
                new BootstrapAdminCredentialStore(
                        temporaryDirectory,
                        "jdbc:hsqldb:file:restart-test");
        restartedProcess.deletePublishedCredential();

        assertThat(published).doesNotExist();
    }

    @Test
    void independentDataSourcesUseIndependentCredentialPaths() throws Exception {
        BootstrapAdminCredentialStore first =
                new BootstrapAdminCredentialStore(temporaryDirectory, "jdbc:test:first");
        BootstrapAdminCredentialStore second =
                new BootstrapAdminCredentialStore(temporaryDirectory, "jdbc:test:second");

        Path firstFile = first.publish("first-data-source-secret");
        Path secondFile = second.publish("second-data-source-secret");

        assertThat(firstFile).isNotEqualTo(secondFile);
        assertThat(Files.readString(firstFile, StandardCharsets.UTF_8).strip())
                .isEqualTo("first-data-source-secret");
        assertThat(Files.readString(secondFile, StandardCharsets.UTF_8).strip())
                .isEqualTo("second-data-source-secret");
    }

    @Test
    void nullAndBlankStorageIdentityUseTheSameStableDefaultFileName() {
        BootstrapAdminCredentialStore nullIdentity =
                new BootstrapAdminCredentialStore(
                        temporaryDirectory.resolve("null"), null);
        BootstrapAdminCredentialStore blankIdentity =
                new BootstrapAdminCredentialStore(
                        temporaryDirectory.resolve("blank"), "   ");

        Path nullFile = nullIdentity.publish("null-identity-secret");
        Path blankFile = blankIdentity.publish("blank-identity-secret");

        assertThat(nullFile.getFileName())
                .isEqualTo(blankFile.getFileName());
    }

    @Test
    void removesPublishedFileWhenFinalPermissionRestrictionFails()
            throws Exception {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory) {
                    private int restrictionCount;

                    @Override
                    Path createOwnerOnlyStagingFile() throws IOException {
                        Files.createDirectories(temporaryDirectory);
                        return Files.createTempFile(
                                temporaryDirectory,
                                "controlled-",
                                ".pending");
                    }

                    @Override
                    void restrictToOwner(Path file) throws IOException {
                        restrictionCount++;
                        if (restrictionCount == 2) {
                            throw new IOException(
                                    "simulated final permission failure");
                        }
                        super.restrictToOwner(file);
                    }
                };

        assertThatIllegalStateException()
                .isThrownBy(() -> store.publish("cleanup-published-secret"))
                .withMessageContaining("owner-only")
                .withCauseInstanceOf(IOException.class);

        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void preservesPrimaryFailureWhenStagingCleanupAlsoFails()
            throws Exception {
        Path nonEmptyStaging = temporaryDirectory.resolve("blocked.pending");
        Files.createDirectory(nonEmptyStaging);
        Files.writeString(nonEmptyStaging.resolve("blocker"), "block");

        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory) {
                    @Override
                    Path createOwnerOnlyStagingFile() {
                        return nonEmptyStaging;
                    }
                };

        assertThatIllegalStateException()
                .isThrownBy(() -> store.publish("cleanup-failure-secret"))
                .withMessageContaining("owner-only")
                .withCauseInstanceOf(IOException.class);

        assertThat(nonEmptyStaging).isDirectory();
        assertThat(nonEmptyStaging.resolve("blocker")).exists();
    }

    @Test
    void fallbackRestrictsTheEmptyStagingFileBeforeReturningIt()
            throws Exception {
        boolean[] restrictionObserved = {false};
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory) {
                    @Override
                    boolean supportsPosixCreationAttributes() {
                        return false;
                    }

                    @Override
                    void restrictToOwner(Path file) throws IOException {
                        assertThat(Files.size(file)).isZero();
                        restrictionObserved[0] = true;
                    }
                };

        Path stagingFile = store.createOwnerOnlyStagingFile();
        try {
            assertThat(restrictionObserved[0]).isTrue();
            assertThat(Files.size(stagingFile)).isZero();
        } finally {
            Files.deleteIfExists(stagingFile);
        }
    }

    @Test
    void rejectsNullAndBlankCredentialWithoutCreatingAFile() throws Exception {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.publish(null))
                .withMessageContaining("must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> store.publish("   "))
                .withMessageContaining("must not be blank");
        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void deletingBeforePublicationIsAnIdempotentNoOp() throws Exception {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);

        store.deletePublishedCredential();
        store.deletePublishedCredential();

        try (var files = Files.list(temporaryDirectory)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void refusesFilesystemWithoutOwnerPermissionModelAndRemovesPartialFile()
            throws Exception {
        Path archive = temporaryDirectory.resolve("credential-filesystem.zip");
        URI uri = URI.create("jar:" + archive.toUri());

        try (FileSystem fileSystem = FileSystems.newFileSystem(
                uri, Map.of("create", "true"))) {
            Path credentialDirectory = fileSystem.getPath("/credentials");
            BootstrapAdminCredentialStore store =
                    new BootstrapAdminCredentialStore(credentialDirectory);

            assertThatIllegalStateException()
                    .isThrownBy(() -> store.publish("generated-bootstrap-secret"))
                    .withMessageContaining("owner-only")
                    .withCauseInstanceOf(java.io.IOException.class);

            try (var files = Files.list(credentialDirectory)) {
                assertThat(files).isEmpty();
            }
        }
    }

    @Test
    void deletionFailureDoesNotEscapeOrExposeCredentialData() throws Exception {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);
        Path published = store.publish("credential-that-must-not-be-logged");
        Files.delete(published);
        Files.createDirectory(published);
        Files.writeString(published.resolve("deletion-blocker"), "block");

        store.deletePublishedCredential();

        assertThat(published).isDirectory();
        assertThat(published.resolve("deletion-blocker")).exists();
    }
}
