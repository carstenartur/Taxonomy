package com.taxonomy.export;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class VisioPresentationGridTest {
    @Test
    void rejectsNonFiniteAndOutOfGridCoordinates() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), VisioGridRegression::rejectsInvalidCoordinates);
    }

    @Test
    void terminatesAtIntegerBoundariesWithoutOverflow() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), VisioGridRegression::acceptsBoundaryCoordinates);
    }
}
