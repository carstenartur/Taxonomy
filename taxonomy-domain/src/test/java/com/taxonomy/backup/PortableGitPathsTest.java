package com.taxonomy.backup;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PortableGitPathsTest {
    @Test void rejectsPathsThatCannotBeSafelyCheckedOutOnSupportedFilesystems() {
        for (String path : List.of("../escape", "/absolute", "C:/drive", "a\\b", "a//b", "a/./b", "a/../b",
                "a/", "a/trailing.", "a/trailing ", "a/CON.txt", "a/NUL", "a/lpt¹.xml", "a/CLOCK$",
                ".git/config", "a/.GiT/config", "a/git~1/config", "a/.git\u200c/config", "a/q?.xml", "a/e\u0301.xml")) {
            assertThrows(IllegalArgumentException.class, () -> PortableGitPaths.requireFile(path), path);
        }
        assertEquals("templates/report/package/[Content_Types].xml",
                PortableGitPaths.requireFile("templates/report/package/[Content_Types].xml"));
        assertEquals("word/_rels/document.xml.rels", PortableGitPaths.requireFile("word/_rels/document.xml.rels"));
    }

    @Test void rejectsFileDirectoryAndCaseFoldedDirectoryCollisions() {
        for (var paths : List.of(List.of("word/a.xml", "WORD/b.xml"), List.of("a", "A/child"),
                List.of("a/b", "a/b"), List.of("data/straße", "data/STRASSE"))) {
            assertThrows(IllegalArgumentException.class, () -> PortableGitPaths.requireTree(paths), paths.toString());
        }
        assertDoesNotThrow(() -> PortableGitPaths.requireTree(List.of("word/document.xml", "word/styles.xml", "docProps/core.xml")));
    }
}
