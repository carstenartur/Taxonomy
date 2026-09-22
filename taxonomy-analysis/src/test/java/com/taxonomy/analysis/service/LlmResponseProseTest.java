package com.taxonomy.analysis.service;

import org.junit.jupiter.api.Test;

class LlmResponseProseTest {
    @Test void skipsBracketedProseBeforeScoreObjects() throws Exception {
        LlmResponseProseChecks.skipsBracketedProseBeforeScoreObjects();
    }
    @Test void preservesArrayRootsAfterBracketedProse() {
        LlmResponseProseChecks.preservesArrayRootsAfterBracketedProse();
    }
    @Test void truncatedContainersAreNeverSalvaged() {
        LlmResponseProseChecks.truncatedContainersAreNeverSalvaged();
    }
    @Test void configuredReadLimitsAreNotTreatedAsProse() {
        LlmResponseProseChecks.configuredReadLimitsAreNotTreatedAsProse();
    }
    @Test void bracketedExamplesCannotSupplyInnerScores() {
        LlmResponseProseChecks.bracketedExamplesCannotSupplyInnerScores();
    }
}
