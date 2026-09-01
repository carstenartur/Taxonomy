package com.taxonomy.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delivers a generated local-administrator bootstrap credential outside the
 * application log stream in an owner-only temporary file.
 */
@Service
@Profile("!keycloak")
public class BootstrapAdminCredentialStore {

    private static final Logger log =
            LoggerFactory.getLogger(BootstrapAdminCredentialStore.class);
    private static final String FILE_PREFIX = "taxonomy-admin-bootstrap-";
    private static final String FILE_SUFFIX = ".txt";

    private final Path directory;
    private final AtomicReference<Path> publishedFile = new AtomicReference<>();

    public BootstrapAdminCredentialStore() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    BootstrapAdminCredentialStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Writes the credential to a newly created owner-only file and returns its
     * absolute path. A second publication replaces and removes the first file.
     */
    public Path publish(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("Bootstrap credential must not be blank");
        }

        Path file = null;
        try {
            Files.createDirectories(directory);
            file = Files.createTempFile(directory, FILE_PREFIX, FILE_SUFFIX);
            restrictToOwner(file);
            Files.writeString(file,
                    credential + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictToOwner(file);

            Path previous = publishedFile.getAndSet(file);
            if (previous != null && !previous.equals(file)) {
                deleteQuietly(previous);
            }

            Path absoluteFile = file.toAbsolutePath().normalize();
            log.warn("BOOTSTRAP_ADMIN_CREDENTIAL_FILE path={} "
                            + "Read this owner-only file once, sign in as 'admin', "
                            + "and replace the password immediately. The file is "
                            + "removed after a successful administrator password change.",
                    absoluteFile);
            return absoluteFile;
        } catch (IOException | RuntimeException exception) {
            if (file != null) {
                deleteQuietly(file);
            }
            throw new IllegalStateException(
                    "Unable to create an owner-only administrator bootstrap credential file",
                    exception);
        }
    }

    /** Removes the file published by this process, if one exists. */
    public void deletePublishedCredential() {
        Path file = publishedFile.getAndSet(null);
        if (file == null) {
            return;
        }
        try {
            if (Files.deleteIfExists(file)) {
                log.info("BOOTSTRAP_ADMIN_CREDENTIAL_FILE_REMOVED path={}",
                        file.toAbsolutePath().normalize());
            }
        } catch (IOException exception) {
            log.error("BOOTSTRAP_ADMIN_CREDENTIAL_FILE_REMOVE_FAILED path={} "
                            + "Delete this owner-only file manually.",
                    file.toAbsolutePath().normalize());
        }
    }

    private static void restrictToOwner(Path file) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(
                file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView != null) {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            return;
        }

        AclFileAttributeView aclView = Files.getFileAttributeView(
                file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (aclView != null) {
            AclEntry ownerOnly = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            aclView.setAcl(List.of(ownerOnly));
            return;
        }

        throw new IOException(
                "Filesystem does not expose POSIX permissions or an ACL view");
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The primary operation reports its own failure. Never log credential data.
        }
    }
}
