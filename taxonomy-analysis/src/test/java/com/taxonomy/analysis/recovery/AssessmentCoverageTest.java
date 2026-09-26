package com.taxonomy.analysis.recovery;

import org.junit.jupiter.api.Test;

class AssessmentCoverageTest {
    @Test void ownEvidenceAndMissingCoverageRemainDistinct() { AssessmentCoverageProbe.verify(); }
}
