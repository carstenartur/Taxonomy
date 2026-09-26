package com.taxonomy.analysis.recovery;
import org.junit.jupiter.api.Test;
class RecoveryExchangeTest {
    @Test void versionThreeCannotDropOrPromoteUnknownEvidence() { RecoveryExchangeProbe.verify(); }
}
