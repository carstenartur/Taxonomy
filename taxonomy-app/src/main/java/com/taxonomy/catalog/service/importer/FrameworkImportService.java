package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.export.DslMaterializeService;
import com.taxonomy.dsl.mapper.ModelToAstMapper;
import com.taxonomy.dsl.mapping.ExternalModelMapper;
import com.taxonomy.dsl.mapping.MappingProfile;
import com.taxonomy.dsl.mapping.MappingResult;
import com.taxonomy.dsl.mapping.profiles.ApqcMappingProfile;
import com.taxonomy.dsl.mapping.profiles.C4MappingProfile;
import com.taxonomy.dsl.mapping.profiles.UafMappingProfile;
import com.taxonomy.dsl.serializer.TaxDslSerializer;
import com.taxonomy.dto.FrameworkImportResult;
import com.taxonomy.dto.ProfileInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import com.taxonomy.catalog.model.TaxonomyRelation;
import com.taxonomy.catalog.service.ArchiMateXmlImporter;
import com.taxonomy.dsl.model.CanonicalArchitectureModel;
import com.taxonomy.relations.model.RelationHypothesis;

/**
 * Generic framework import service that combines a {@link MappingProfile} with
 * an {@link ExternalParser} to import external architecture models.
 *
 * <p>Registered profiles:
 * <ul>
 *   <li>{@code archimate} — ArchiMate 3.x XML (not re-parsed here; uses {@code ArchiMateXmlImporter})</li>
 *   <li>{@code uaf} — UAF/DoDAF XMI XML</li>
 *   <li>{@code apqc} — APQC PCF CSV</li>
 *   <li>{@code apqc-excel} — APQC PCF Excel</li>
 *   <li>{@code c4} — C4/Structurizr DSL</li>
 * </ul>
 *
 * <p>Data flow:
 * <pre>
 * File → ExternalParser → ExternalElements/Relations
 *      → ExternalModelMapper (profile) → CanonicalArchitectureModel
 *      → DslSerializer → DSL text
 *      → DslMaterializeService → TaxonomyRelation / RelationHypothesis (DB)
 * </pre>
 */
@Service
public class FrameworkImportService {

    private static final Logger log = LoggerFactory.getLogger(FrameworkImportService.class);

    private final DslMaterializeService materializeService;
    private final Map<String, RegisteredProfile> profiles = new LinkedHashMap<>();

    public FrameworkImportService(DslMaterializeService materializeService) {
        this.materializeService = materializeService;

        // Register all supported profiles
        register(new UafMappingProfile(), new UafXmlParser());
        register(new ApqcMappingProfile(), new ApqcCsvParser());
        register(new ApqcMappingProfile(), "apqc-excel", "APQC PCF (Excel)", new ApqcExcelParser());
        register(new C4MappingProfile(), new StructurizrDslParser());
    }

    private void register(MappingProfile profile, ExternalParser parser) {
        register(profile, profile.profileId(), profile.displayName(), parser);
    }

    private void register(MappingProfile profile, String profileId, String displayName,
                           ExternalParser parser) {
        profiles.put(profileId, new RegisteredProfile(profile, displayName, parser));
    }

    /**
     * Returns info about all available import profiles.
     */
    public List<ProfileInfo> getAvailableProfiles() {
        List<ProfileInfo> result = new ArrayList<>();
        for (var entry : profiles.entrySet()) {
            RegisteredProfile rp = entry.getValue();
            result.add(new ProfileInfo(
                    entry.getKey(),
                    rp.displayName,
                    rp.profile.supportedElementTypes(),
                    rp.profile.supportedRelationTypes(),
                    rp.parser.fileFormat()));
        }
        return result;
    }

    /**
     * Preview an import (dry run): parse and map but do not materialize.
     */
    public FrameworkImportResult preview(String profileId, InputStream input) {
        RegisteredProfile rp = profiles.get(profileId);
        if (rp == null) {
            return errorResult(profileId, "Unknown profile: " + profileId);
        }

        try {
            ExternalParser.ParsedExternalModel parsed = rp.parser.parse(input);
            ExternalModelMapper mapper = new ExternalModelMapper(rp.profile);
            MappingResult mappingResult = mapper.map(parsed.elements(), parsed.relations());

            return new FrameworkImportResult(
                    profileId,
                    rp.displayName,
                    true,
                    parsed.elements().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedElements", 0),
                    parsed.relations().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedRelations", 0),
                    0, 0, null,
                    mappingResult.warnings(),
                    new ArrayList<>(mappingResult.unmappedTypes()),
                    mappingResult.mappingStatistics());
        } catch (Exception e) {
            log.error("Preview failed for profile {}", profileId, e);
            return errorResult(profileId, "Preview failed: " + e.getMessage());
        }
    }

    /**
     * Full import: parse, map, serialize to DSL, and materialize into the database.
     */
    public FrameworkImportResult importFile(String profileId, InputStream input, String branch) {
        RegisteredProfile rp = profiles.get(profileId);
        if (rp == null) {
            return errorResult(profileId, "Unknown profile: " + profileId);
        }

        try {
            // 1. Parse
            ExternalParser.ParsedExternalModel parsed = rp.parser.parse(input);

            // 2. Map to canonical model
            ExternalModelMapper mapper = new ExternalModelMapper(rp.profile);
            MappingResult mappingResult = mapper.map(parsed.elements(), parsed.relations());

            // 3. Serialize to DSL text
            ModelToAstMapper astMapper = new ModelToAstMapper();
            TaxDslSerializer serializer = new TaxDslSerializer();
            var astDoc = astMapper.toDocument(mappingResult.model(), profileId + "-import");
            String dslText = serializer.serialize(astDoc);

            // 4. Materialize
            String path = "import/" + profileId + "-" + System.currentTimeMillis() + ".taxdsl";
            DslMaterializeService.MaterializeResult matResult =
                    materializeService.materialize(dslText, path, branch, null);

            List<String> allWarnings = new ArrayList<>(mappingResult.warnings());
            allWarnings.addAll(matResult.warnings());
            if (!matResult.valid()) {
                allWarnings.addAll(matResult.errors());
            }

            return new FrameworkImportResult(
                    profileId,
                    rp.displayName,
                    matResult.valid(),
                    parsed.elements().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedElements", 0),
                    parsed.relations().size(),
                    (int) mappingResult.mappingStatistics().getOrDefault("mappedRelations", 0),
                    matResult.relationsCreated(),
                    matResult.hypothesesCreated(),
                    matResult.documentId(),
                    allWarnings,
                    new ArrayList<>(mappingResult.unmappedTypes()),
                    mappingResult.mappingStatistics());
        } catch (Exception e) {
            log.error("Import failed for profile {}", profileId, e);
            return errorResult(profileId, "Import failed: " + e.getMessage());
        }
    }

    private FrameworkImportResult errorResult(String profileId, String message) {
        String displayName = profiles.containsKey(profileId) ?
                profiles.get(profileId).displayName : profileId;
        return new FrameworkImportResult(
                profileId, displayName, false,
                0, 0, 0, 0, 0, 0, null,
                List.of(message), List.of(), Map.of());
    }

    private record RegisteredProfile(
        MappingProfile profile,
        String displayName,
        ExternalParser parser
    ) {}
}
