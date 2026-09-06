package com.taxonomy.shared.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Filesystem-only tests: no database, Spring context or container is started. */
class SystemInformationDiskScopeTest {
    @TempDir Path temporary;

    @Test
    void theSameResolvedDirectoryCombinesPurposesWithoutFileStoreEquality() {
        Path index = Path.of(System.getProperty("java.io.tmpdir")).resolve(".");
        var env = new MockEnvironment().withProperty(
                "spring.jpa.properties.hibernate.search.backend.directory.root", index.toString());
        var disks = new SystemInformationService(null, env).disks("local-filesystem");
        assertThat(disks).hasSize(1);
        assertThat(disks.getFirst().purposes()).containsExactly("TEMPORARY_FILES", "SEARCH_INDEX");
        assertThat(disks.getFirst().status()).isEqualTo("AVAILABLE");
        assertThat(disks.toString()).doesNotContain(index.toString());
    }

    @Test
    void distinctDirectoriesAreSeparateMeasurementsEvenIfTheirFilesystemIsShared() throws Exception {
        Path index = Files.createDirectory(temporary.resolve("index"));
        var env = new MockEnvironment().withProperty(
                "spring.jpa.properties.hibernate.search.backend.directory.root", index.toString());
        var disks = new SystemInformationService(null, env).disks("local-filesystem");
        assertThat(disks).hasSize(2);
        assertThat(disks.get(0).purposes()).containsExactly("TEMPORARY_FILES");
        assertThat(disks.get(1).purposes()).containsExactly("SEARCH_INDEX");
        assertThat(disks.get(1).totalBytes()).isEqualTo(Files.getFileStore(index).getTotalSpace());
        assertThat(disks.get(1).usableBytes()).isNotNull().isNotNegative();
        assertThat(disks.toString()).doesNotContain(index.toString());
    }
}
