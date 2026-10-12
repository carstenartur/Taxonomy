package com.taxonomy.workspace.backup;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PortableGitIndexTest {
    @TempDir Path temporary;

    @Test void anEmptyIndexKeepsTheStandardGitV2BytesAndChecksum() throws Exception {
        var output = new ByteArrayOutputStream();
        PortableGitIndex.write(List.of(), output, () -> { });
        assertThat(HexFormat.of().formatHex(output.toByteArray())).isEqualTo(
                "44495243000000020000000039d890139ee5356c7ef572216cebcd27aa41f9df");
    }

    @Test void theNativeIndexRetainsUtf8PathsModesAndBlobIdentities() throws Exception {
        try (var git = Git.init().setDirectory(temporary.toFile()).call();
             var objects = git.getRepository().newObjectInserter()) {
            byte[] first = "ordinary".getBytes(StandardCharsets.UTF_8);
            byte[] second = "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8);
            var ordinary = objects.insert(Constants.OBJ_BLOB, first);
            var executable = objects.insert(Constants.OBJ_BLOB, second);
            objects.flush();
            var output = new ByteArrayOutputStream();
            PortableGitIndex.write(List.of(
                    new GitTreeCapture.File("ä-file", executable.name(), FileMode.EXECUTABLE_FILE.getBits(), second.length, GitReflogProvenance.digest(second)),
                    new GitTreeCapture.File("a-file", ordinary.name(), FileMode.REGULAR_FILE.getBits(), first.length, GitReflogProvenance.digest(first))),
                    output, () -> { });
            Files.write(git.getRepository().getIndexFile().toPath(), output.toByteArray());
            var index = DirCache.read(git.getRepository());
            assertThat(index.getEntryCount()).isEqualTo(2);
            assertThat(index.getEntry(0).getPathString()).isEqualTo("a-file");
            assertThat(index.getEntry(0).getObjectId()).isEqualTo(ordinary);
            assertThat(index.getEntry(0).getFileMode()).isEqualTo(FileMode.REGULAR_FILE);
            assertThat(index.getEntry(1).getPathString()).isEqualTo("ä-file");
            assertThat(index.getEntry(1).getObjectId()).isEqualTo(executable);
            assertThat(index.getEntry(1).getFileMode()).isEqualTo(FileMode.EXECUTABLE_FILE);
        }
    }
}
