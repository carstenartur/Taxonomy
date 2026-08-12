package com.taxonomy.relations.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHttpPreconditionTest {

    private static final String COMMIT =
            "0123456789abcdef0123456789abcdef01234567";

    @Test
    void parsesOneStrongQuotedCommitEtag() {
        assertThat(GitHttpPrecondition.expectedHead(
                '"' + COMMIT + '"', null))
                .isEqualTo(COMMIT);
        assertThat(GitHttpPrecondition.etag(COMMIT))
                .isEqualTo('"' + COMMIT + '"');
    }

    @Test
    void mapsIfNoneMatchStarToExpectedAbsentBranch() {
        assertThat(GitHttpPrecondition.expectedHead(null, "*"))
                .isNull();
    }

    @Test
    void requiresExactlyOneSupportedPrecondition() {
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(null, null))
                .isInstanceOf(
                        GitHttpPrecondition.PreconditionRequiredException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(
                '"' + COMMIT + '"', "*"))
                .isInstanceOf(
                        GitHttpPrecondition.InvalidPreconditionException.class);
    }

    @Test
    void rejectsWeakWildcardMultipleAndMalformedEtags() {
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(
                "W/\"" + COMMIT + "\"", null))
                .isInstanceOf(
                        GitHttpPrecondition.InvalidPreconditionException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead("*", null))
                .isInstanceOf(
                        GitHttpPrecondition.InvalidPreconditionException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(
                '"' + COMMIT + "\", \"" + "f".repeat(40) + '"', null))
                .isInstanceOf(
                        GitHttpPrecondition.InvalidPreconditionException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(COMMIT, null))
                .isInstanceOf(
                        GitHttpPrecondition.InvalidPreconditionException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(null, '"' + COMMIT + '"'))
                .isInstanceOf(
                        GitHttpPrecondition.InvalidPreconditionException.class);
    }
}
