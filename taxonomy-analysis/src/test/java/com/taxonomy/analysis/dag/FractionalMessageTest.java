package com.taxonomy.analysis.dag;
import org.junit.jupiter.api.Test;
class FractionalMessageTest {
    @Test void versionedFractionalResultsRoundTrip() { FractionalMessageChecks.roundTrip(); }
    @Test void legacyIntegerMessagesRemainReadableButCannotMasqueradeAsDecimalResults() { FractionalMessageChecks.legacy(); }
    @Test void malformedNumbersAndFractionalCountersAreRejected() { FractionalMessageChecks.invalid(); }
}
