package com.taxonomy.export;
import org.junit.jupiter.api.Test;
class RecoveryProjectionTest {
    @Test void openAssessmentsRemainExplicitWithoutInventingNodes() { RecoveryProjectionProbe.verify(); }
}
