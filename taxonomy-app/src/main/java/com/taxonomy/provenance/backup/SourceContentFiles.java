package com.taxonomy.provenance.backup;

import com.taxonomy.backup.BackupEntry;
import com.taxonomy.backup.ComponentSink;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** The configured, operator-owned content root must remain stable for the fenced capture. */
final class SourceContentFiles {
    private final Path root;
    SourceContentFiles(Path root) { this.root=Objects.requireNonNull(root).toAbsolutePath().normalize(); }
    BackupEntry capture(String location,String entry,String expectedHash,boolean original,ComponentSink sink) throws IOException {
        if (location==null || location.isBlank()) return null;
        if (original && (expectedHash==null || !expectedHash.matches("(?i)[0-9a-f]{64}"))) throw new IOException("Original source digest is unavailable");
        try {
            Path supplied=Path.of(location); Path path=(supplied.isAbsolute()?supplied:root.resolve(supplied)).normalize();
            if (!path.startsWith(root) || path.equals(root) || !root.toRealPath().equals(root)
                    || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid source content path");
            Path relative=root.relativize(path);
            if (relative.getNameCount()>32) throw new IOException("Source path depth limit exceeded");
            BackupEntry result;
            try (var directory=Files.newDirectoryStream(root)) {
                if (directory instanceof SecureDirectoryStream<Path> secure) result=secureCopy(secure,relative,entry,sink);
                else result=checkedCopy(path,entry,sink);
            }
            if (original && !result.sha256().equalsIgnoreCase(expectedHash)) throw new IOException("Source content digest differs from recorded original");
            return result;
        } catch (InvalidPathException | UnsupportedOperationException failure) {
            throw new IOException("Source content cannot be captured on this storage provider");
        } catch (IOException failure) {
            // Never disclose installation paths or provider diagnostics through job failures.
            throw new IOException("Referenced source content is unavailable, changed or unsafe");
        }
    }
    private static BackupEntry secureCopy(SecureDirectoryStream<Path> directory,Path relative,String entry,ComponentSink sink) throws IOException {
        if (relative.getNameCount()>1) {
            try (var child=directory.newDirectoryStream(relative.getName(0),LinkOption.NOFOLLOW_LINKS)) {
                return secureCopy(child,relative.subpath(1,relative.getNameCount()),entry,sink);
            }
        }
        var view=directory.getFileAttributeView(relative,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        var before=view.readAttributes(); requireFile(before);
        try (var channel=directory.newByteChannel(relative,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS));
             var input=Channels.newInputStream(channel)) {
            BackupEntry result=sink.write(entry,input); unchanged(before,view.readAttributes(),result); return result;
        }
    }
    /** Windows providers lack SecureDirectoryStream; reject links at every level and detect path replacement. */
    private BackupEntry checkedCopy(Path path,String entry,ComponentSink sink) throws IOException {
        var paths=new ArrayList<Path>(); var before=new ArrayList<BasicFileAttributes>();
        for (Path current=root; ; ) {
            if (!current.toRealPath().equals(current)) throw new IOException("Source links are not supported");
            paths.add(current); before.add(Files.readAttributes(current,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS));
            if (current.equals(path)) break;
            current=current.resolve(root.relativize(path).getName(paths.size()-1));
        }
        requireFile(before.getLast());
        try (var input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            for (int i=0;i<paths.size();i++) {
                var current=Files.readAttributes(paths.get(i),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if (current.isSymbolicLink() || !Objects.equals(before.get(i).fileKey(),current.fileKey())
                        || !paths.get(i).toRealPath().equals(paths.get(i))) throw new IOException("Source path changed");
            }
            BackupEntry result=sink.write(entry,input);
            unchanged(before.getLast(),Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS),result);
            return result;
        }
    }
    private static void requireFile(BasicFileAttributes attributes) throws IOException {
        if (!attributes.isRegularFile()) throw new IOException("Source content must be a regular file");
    }
    private static void unchanged(BasicFileAttributes before,BasicFileAttributes after,BackupEntry copied) throws IOException {
        requireFile(after);
        if (!Objects.equals(before.fileKey(),after.fileKey()) || before.size()!=copied.length() || after.size()!=copied.length()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("Source changed during capture");
    }
}
