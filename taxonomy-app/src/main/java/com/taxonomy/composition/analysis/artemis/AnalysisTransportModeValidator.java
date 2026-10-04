package com.taxonomy.composition.analysis.artemis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Rejects unknown {@code taxonomy.analysis.transport.mode} values at startup in every mode. */
@Component
class AnalysisTransportModeValidator {

    AnalysisTransportModeValidator(@Value("${taxonomy.analysis.transport.mode:local}") String mode) {
        ArtemisAnalysisSettings.artemisMode(mode);
    }
}
