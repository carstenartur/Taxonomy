package com.taxonomy.relations.controller;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHttpPreconditionObjectIdCompatibilityTest {

    @Test
    void workspaceCanonicalizationPreservesJGitEtagsAndExpectedHeads() {
        Random random = new Random(628);
        for (int index = 0; index < 1_000; index++) {
            byte[] bytes = new byte[20];
            random.nextBytes(bytes);
            String id = HexFormat.of().formatHex(bytes);
            String supplied = index % 2 == 0 ? id.toUpperCase(Locale.ROOT) : id;
            String expected = ObjectId.fromString(supplied).name();

            assertThat(GitHttpPrecondition.etag(supplied)).isEqualTo('"' + expected + '"');
            assertThat(GitHttpPrecondition.expectedHead("  \"" + supplied + "\"  ", null))
                    .isEqualTo(expected);
        }
    }

    @Test
    void invalidTagsAndBranchCreationRetainTheirPublicContracts() {
        String valid = "a".repeat(40);
        for (String tag : List.of("*", "W/\"" + valid + "\"", "\"abc\"",
                "\"" + "g".repeat(40) + "\"", "\"" + valid + "\",\"" + valid + "\"")) {
            assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(tag, null))
                    .isInstanceOf(GitHttpPrecondition.InvalidPreconditionException.class);
        }
        assertThat(GitHttpPrecondition.expectedHead(null, "*")).isNull();
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead(null, null))
                .isInstanceOf(GitHttpPrecondition.PreconditionRequiredException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.expectedHead('"' + valid + '"', "*"))
                .isInstanceOf(GitHttpPrecondition.InvalidPreconditionException.class);
        assertThatThrownBy(() -> GitHttpPrecondition.etag(null))
                .isInstanceOf(NullPointerException.class);
    }
}
