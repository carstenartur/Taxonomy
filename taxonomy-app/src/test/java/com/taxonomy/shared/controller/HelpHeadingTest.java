package com.taxonomy.shared.controller;

import org.junit.jupiter.api.Test;

/** The production renderer must provide the targets used by document section links. */
class HelpHeadingTest {
    @Test void englishSectionLinksHaveTargets() throws Exception {
        HelpHeadingAssertions.englishFragments();
    }
    @Test void translatedSectionLinksHaveUnicodeTargets() throws Exception {
        HelpHeadingAssertions.unicodeFragments();
    }
    @Test void repeatedHeadingsHaveUniqueIds() throws Exception {
        HelpHeadingAssertions.repeatedHeadings();
    }
    @Test void explicitAnchorsAndFencedExamplesRemainIntact() throws Exception {
        HelpHeadingAssertions.explicitAnchorsAndCode();
    }
    @Test void repeatedRenderingDoesNotShareHeadingCounters() throws Exception {
        HelpHeadingAssertions.perDocumentIdentity();
    }
}
