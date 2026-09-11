package com.taxonomy.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHttpPreconditionTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void parsesOneStrongQuotedCommitEtag() {
        assertEquals(COMMIT, GitHttpPrecondition.expectedHead('"' + COMMIT + '"', null));
        assertEquals('"' + COMMIT + '"', GitHttpPrecondition.etag(COMMIT));
    }

    @Test
    void normalizesUppercaseObjectIdsLikeJgit() {
        String uppercase = COMMIT.toUpperCase(java.util.Locale.ROOT);
        assertEquals(COMMIT, GitHttpPrecondition.expectedHead('"' + uppercase + '"', null));
        assertEquals('"' + COMMIT + '"', GitHttpPrecondition.etag(uppercase));
    }

    @Test
    void mapsIfNoneMatchStarToExpectedAbsentBranch() {
        assertNull(GitHttpPrecondition.expectedHead(null, "*"));
    }

    @Test
    void requiresExactlyOneSupportedPrecondition() {
        assertThrows(GitHttpPrecondition.PreconditionRequiredException.class,
                () -> GitHttpPrecondition.expectedHead(null, null));
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead('"' + COMMIT + '"', "*"));
    }

    @Test
    void rejectsWeakWildcardMultipleAndMalformedEtags() {
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead("W/\"" + COMMIT + "\"", null));
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead("*", null));
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead('"' + COMMIT + "\", \"" + "f".repeat(40) + '"', null));
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead(COMMIT, null));
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead(null, '"' + COMMIT + '"'));
        assertThrows(GitHttpPrecondition.InvalidPreconditionException.class,
                () -> GitHttpPrecondition.expectedHead('"' + "g".repeat(40) + '"', null));
    }
}
