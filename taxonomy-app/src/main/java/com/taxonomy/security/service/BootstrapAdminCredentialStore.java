package com.taxonomy.security.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Delivers a generated local-administrator bootstrap credential outside the
 * application log stream in an owner-only temporary file.
 *
 * <p>The final file name is derived from a one-way fingerprint of the configured
 * data-source URL. That makes the deletion target stable across process restarts
 * without writing the URL or credential into the file name or log.</p>
 */
@Service
@Profile("!keycloak")
public class BootstrapAdminCredentialStore {

    private static final Logger log =
            LoggerFactory.getLogger(BootstrapAdminCredentialStore.class);
    private static final String FILE_PREFIX = "taxonomy-admin-bootstrap-";
    private static final String FILE_SUFFIX = ".txt";
    private static final String PENDING_SUFFIX = ".pending";
    private static final String DEFAULT_STORAGE_IDENTITY = "taxonomy-local";
    private static final Set<PosixFilePermission> OWNER_ONLY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    private final Path directory;
    private final Path credentialFile;

    @Autowired
    public BootstrapAdminCredentialStore(
            @Value("${spring.datasource.url:taxonomy-local}") String storageIdentity) {
        this(Path.of(System.getProperty("java.io.tmpdir")), storageIdentity);
    }

    BootstrapAdminCredentialStore(Path directory) {
        this(directory, DEFAULT_STORAGE_IDENTITY);
    }

    BootstrapAdminCredentialStore(Path directory, String storageIdentity) {
        this.directory = Objects.requireNonNull(directory, "directory")
                .toAbsolutePath()
                .normalize();
        this.credentialFile = this.directory.resolve(
                FILE_PREFIX + stableKey(storageIdentity) + FILE_SUFFIX);
    }

    /**
     * Writes the credential to an owner-only staging file and publishes it at the
     * restart-stable data-source-specific path.
     */
    public Path publish(String credential) {
        if (credential == null || credential.isBlank()) {
            throw new IllegalArgumentException("Bootstrap credential must not be blank");
        }

        Path stagingFile = null;
        boolean movedToPublishedPath = false;
        try {
            stagingFile = createOwnerOnlyStagingFile();
            Files.writeString(stagingFile,
                    credential + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictToOwner(stagingFile);

            moveReplacing(stagingFile, credentialFile);
            movedToPublishedPath = true;
            stagingFile = null;
            restrictToOwner(credentialFile);

            Path absoluteFile = credentialFile.toAbsolutePath().normalize();
            log.warn("BOOTSTRAP_ADMIN_CREDENTIAL_FILE path={} "
                            + "Read this owner-only file once, sign in as 'admin', "
                            + "and replace the password immediately. The file is "
                            + "removed after a successful administrator password change.",
                    absoluteFile);
            return absoluteFile;
        } catch (IOException | RuntimeException exception) {
            if (stagingFile != null) {
                deleteQuietly(stagingFile);
            }
            if (movedToPublishedPath) {
                deleteQuietly(credentialFile);
            }
            throw new IllegalStateException(
                    "Unable to create an owner-only administrator bootstrap credential file",
                    exception);
        }
    }

    /**
     * Removes this data source's published credential, including after a process
     * restart, if the file exists.
     */
    public void deletePublishedCredential() {
        try {
            if (Files.deleteIfExists(credentialFile)) {
                log.info("BOOTSTRAP_ADMIN_CREDENTIAL_FILE_REMOVED path={}",
                        credentialFile.toAbsolutePath().normalize());
            }
        } catch (IOException exception) {
            log.error("BOOTSTRAP_ADMIN_CREDENTIAL_FILE_REMOVE_FAILED path={} "
                            + "Delete this owner-only file manually.",
                    credentialFile.toAbsolutePath().normalize());
        }
    }

    /**
     * Creates an empty staging file with owner-only POSIX permissions as part of
     * the create operation. Providers without POSIX creation attributes fall back
     * to restricting the still-empty file before any credential bytes are written.
     */
    Path createOwnerOnlyStagingFile() throws IOException {
        Files.createDirectories(directory);
        if (supportsPosixCreationAttributes()) {
            try {
                return Files.createTempFile(
                        directory,
                        credentialFile.getFileName().toString() + "-",
                        PENDING_SUFFIX,
                        PosixFilePermissions.asFileAttribute(
                                OWNER_ONLY_PERMISSIONS));
            } catch (UnsupportedOperationException unsupportedCreationAttribute) {
                // Fall through only when the provider cannot apply POSIX attributes
                // during creation. The empty file is restricted before secret data.
            }
        }

        Path stagingFile = Files.createTempFile(
                directory,
                credentialFile.getFileName().toString() + "-",
                PENDING_SUFFIX);
        try {
            restrictToOwner(stagingFile);
            return stagingFile;
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(stagingFile);
            throw exception;
        }
    }

    boolean supportsPosixCreationAttributes() {
        return Files.getFileAttributeView(
                directory,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS) != null;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException
                | FileAlreadyExistsException
                | UnsupportedOperationException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String stableKey(String storageIdentity) {
        String normalized = storageIdentity == null || storageIdentity.isBlank()
                ? DEFAULT_STORAGE_IDENTITY
                : storageIdentity.strip();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    void restrictToOwner(Path file) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(
                file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView != null) {
            Files.setPosixFilePermissions(file, OWNER_ONLY_PERMISSIONS);
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
