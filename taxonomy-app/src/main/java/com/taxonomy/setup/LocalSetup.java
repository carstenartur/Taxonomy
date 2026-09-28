package com.taxonomy.setup;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Creates an explicit local installation; never called as a fallback after startup failure. */
public final class LocalSetup {
    private LocalSetup() { }

    public static Properties localProperties(Path dataDirectory) {
        Path data = dataDirectory.toAbsolutePath().normalize();
        String dbPath = data.resolve("taxonomydb").toString().replace('\\', '/');
        if (dbPath.contains(";") || dbPath.contains("\n") || dbPath.contains("\r")) {
            throw new IllegalArgumentException("The data directory must not contain JDBC option separators.");
        }
        Properties result = new Properties();
        result.setProperty("spring.profiles.active", "hsqldb,local-user-management");
        result.setProperty("spring.datasource.url", "jdbc:hsqldb:file:" + dbPath
                + ";hsqldb.default_table_type=cached;hsqldb.write_delay_millis=0;shutdown=true");
        result.setProperty("spring.jpa.hibernate.ddl-auto", "update");
        result.setProperty("server.address", "127.0.0.1");
        result.setProperty("server.port", "8080");
        result.setProperty("taxonomy.init.reload-existing", "false");
        result.setProperty("embedding.enabled", "false");
        result.setProperty("embedding.allow-download", "false");
        result.setProperty("spring.jpa.properties.hibernate.search.backend.directory.type", "local-heap");
        return result;
    }

    /** Publish application.properties last; until then the installation is incomplete. */
    public static void write(Path directory, Properties settings, Map<String, String> secrets) throws IOException {
        for (String key : settings.stringPropertyNames()) {
            if (secretKey(key)) { throw new IOException("Credentials must be provided through the separate secrets map."); }
        }
        for (String key : secrets.keySet()) {
            if (!key.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) { throw new IOException("Invalid configtree key."); }
        }
        Path target = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(target.getParent())) { throw new IOException("The configuration parent directory must exist."); }
        createPrivateDirectory(target);
        createPrivateDirectory(target.resolve("data"));
        Path secretDirectory = target.resolve("secrets");
        createPrivateDirectory(secretDirectory);
        for (var entry : secrets.entrySet()) {
            Path file = secretDirectory.resolve(entry.getKey());
            createPrivateFile(file);
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8, StandardOpenOption.WRITE);
        }
        Properties copy = new Properties();
        copy.putAll(settings);
        // ConfigData configtree locations are filesystem paths, not file: URIs.
        copy.setProperty("spring.config.import", "configtree:" + secretDirectory.toString().replace('\\', '/') + "/");
        Path temporary = target.resolve(".application.properties.tmp");
        createPrivateFile(temporary);
        try (var stream = Files.newOutputStream(temporary, StandardOpenOption.WRITE)) {
            copy.store(stream, "Taxonomy configuration; environment and command-line overrides still apply.");
        }
        // The target directory was exclusively created above; no existing installation is replaced.
        Files.move(temporary, target.resolve("application.properties"), StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean secretKey(String key) {
        return key.endsWith("password") || key.endsWith("secret") || key.endsWith("token") || key.endsWith("api.key");
    }

    private static void createPrivateDirectory(Path path) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectory(path);
            restrictAcl(path);
        }
    }

    private static void createPrivateFile(Path path) throws IOException {
        if (Files.getFileStore(path.getParent()).supportsFileAttributeView("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(path);
            restrictAcl(path);
        }
    }

    private static void restrictAcl(Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) { throw new IOException("A private POSIX or Windows ACL directory is required."); }
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        view.setAcl(List.of(entry));
    }
}
