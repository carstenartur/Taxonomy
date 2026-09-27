package com.taxonomy.analysis.recovery;
import org.junit.jupiter.api.Test;
class QuestionRecoveryTest {
    @Test void retainsAnswersAndPausesWithoutInventingZeroScores() { QuestionRecoveryProbe.verify(); }
}
