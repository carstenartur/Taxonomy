package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.mapping.MappingProfile;
import com.taxonomy.dsl.mapping.profiles.C4MappingProfile;
import com.taxonomy.extension.api.importer.ImportProfileDescriptor;
import org.springframework.stereotype.Component;

@Component
public class C4ImportProfileExtension extends AbstractFrameworkImportProfileExtension {

    private static final MappingProfile PROFILE = new C4MappingProfile();
    private static final ExternalParser PARSER = new StructurizrDslParser();
    private static final ImportProfileDescriptor DESCRIPTOR = new ImportProfileDescriptor(
            PROFILE.profileId(),
            PROFILE.displayName(),
            PROFILE.supportedElementTypes(),
            PROFILE.supportedRelationTypes(),
            PARSER.fileFormat());

    public C4ImportProfileExtension(DslMaterializeService materializeService) {
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
