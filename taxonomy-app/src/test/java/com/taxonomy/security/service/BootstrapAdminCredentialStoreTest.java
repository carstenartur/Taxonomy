package com.taxonomy.security.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

class BootstrapAdminCredentialStoreTest {

    @TempDir
    Path temporaryDirectory;

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
    void secondPublicationRemovesPreviousCredential() {
        BootstrapAdminCredentialStore store =
                new BootstrapAdminCredentialStore(temporaryDirectory);

        Path first = store.publish("first-bootstrap-secret");
        Path second = store.publish("second-bootstrap-secret");

        assertThat(first).doesNotExist();
        assertThat(second).exists();
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
