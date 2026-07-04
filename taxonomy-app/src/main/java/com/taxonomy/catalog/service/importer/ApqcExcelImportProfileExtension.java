package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.mapping.MappingProfile;
import com.taxonomy.dsl.mapping.profiles.ApqcMappingProfile;
import org.springframework.stereotype.Component;

/**
 * {@link ImportProfileExtension} adapter for the APQC PCF Excel import profile.
 */
@Component
public class ApqcExcelImportProfileExtension extends AbstractFrameworkImportProfileExtension {

    private static final MappingProfile PROFILE = new ApqcMappingProfile();
    private static final ExternalParser PARSER = new ApqcExcelParser();

    private static final ImportProfileDescriptor DESCRIPTOR = new ImportProfileDescriptor(
            "apqc-excel",
            "APQC PCF (Excel)",
            PROFILE.supportedElementTypes(),
            PROFILE.supportedRelationTypes(),
            PARSER.fileFormat());

    public ApqcExcelImportProfileExtension(DslMaterializeService materializeService) {
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
