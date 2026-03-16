package com.taxonomy.catalog.service.importer;

import com.taxonomy.dsl.mapping.ExternalElement;
import com.taxonomy.dsl.mapping.ExternalRelation;

import java.io.InputStream;
import java.util.List;
import com.taxonomy.dsl.mapping.ExternalModelMapper;

/**
 * Parses an external architecture model file into lists of
 * {@link ExternalElement}s and {@link ExternalRelation}s.
 *
 * <p>Implementations handle a single file format (XML, CSV, DSL, etc.).
 * The parsed data is then fed into an {@link com.taxonomy.dsl.mapping.ExternalModelMapper}
 * for conversion to the canonical model.
 */
public interface ExternalParser {

    /**
     * Parse an input stream into external elements and relations.
     *
     * @param input the file content
     * @return a parsed external model
     * @throws Exception if parsing fails
     */
    ParsedExternalModel parse(InputStream input) throws Exception;

    /**
     * The file format this parser supports (e.g., "xml", "csv", "dsl").
     */
    String fileFormat();

    /**
     * Parsed result containing elements and relations extracted from an external file.
     */
    record ParsedExternalModel(
        List<ExternalElement> elements,
        List<ExternalRelation> relations
    ) {}
}
