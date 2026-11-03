package com.taxonomy.composition.architecture;

import com.taxonomy.archimate.exchange.ArchiMateExchangeProfile;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Shared page fragments expose the same export profile on every architecture route. */
@ControllerAdvice
public class ArchitectureWorkspaceModelAdvice {
    @ModelAttribute("archimateMappingProfile")
    public String archimateMappingProfile() {
        return ArchiMateExchangeProfile.VERSION;
    }
}
