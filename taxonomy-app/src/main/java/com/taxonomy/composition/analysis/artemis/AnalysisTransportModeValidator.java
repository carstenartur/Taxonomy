package com.taxonomy.composition.analysis.artemis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Rejects unknown {@code taxonomy.analysis.transport.mode} values at startup in every mode. */
@Component
class AnalysisTransportModeValidator {

    AnalysisTransportModeValidator(@Value("${taxonomy.analysis.transport.mode:local}") String mode,
            @Value("${taxonomy.analysis.runtime-role:all}") String role) {
        ArtemisAnalysisSettings.artemisMode(mode);
        var runtimeRole = ArtemisAnalysisTransportConfiguration.RuntimeRole.parse(role);
        if (!ArtemisAnalysisSettings.artemisMode(mode) && runtimeRole != ArtemisAnalysisTransportConfiguration.RuntimeRole.ALL)
            throw new IllegalArgumentException("Dedicated analysis roles require Artemis transport");
    }
}
