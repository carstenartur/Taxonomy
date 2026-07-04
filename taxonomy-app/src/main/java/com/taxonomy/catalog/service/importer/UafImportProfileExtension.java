package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.mapping.MappingProfile;
import com.taxonomy.dsl.mapping.profiles.UafMappingProfile;
import org.springframework.stereotype.Component;

/**
 * {@link ImportProfileExtension} adapter for the UAF / DoDAF XMI XML import profile.
 */
@Component
public class UafImportProfileExtension extends AbstractFrameworkImportProfileExtension {

    private static final MappingProfile PROFILE = new UafMappingProfile();
    private static final ExternalParser PARSER = new UafXmlParser();

    private static final ImportProfileDescriptor DESCRIPTOR = new ImportProfileDescriptor(
            PROFILE.profileId(),
            PROFILE.displayName(),
            PROFILE.supportedElementTypes(),
            PROFILE.supportedRelationTypes(),
            PARSER.fileFormat());

    public UafImportProfileExtension(DslMaterializeService materializeService) {
        super(materializeService);
    }

    @Override
    public ImportProfileDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected MappingProfile profile() {
        return PROFILE;
    }

    @Override
    protected ExternalParser parser() {
        return PARSER;
    }
}
