package com.taxonomy.dto;

import com.taxonomy.model.RelationType;
import com.taxonomy.model.SeedType;

/**
 * Immutable representation of a single row in the relation seed CSV file.
 *
 * <p>The CSV file supports the following columns (new columns are optional for
 * backward compatibility with older 4-column files):
 *
 * <pre>
 * SourceCode,TargetCode,RelationType,Description,SourceStandard,SourceReference,Confidence,SeedType,ReviewRequired,Status
 * </pre>
 *
 * <table>
 *   <tr><th>Column</th><th>Required</th><th>Description</th></tr>
 *   <tr><td>SourceCode</td><td>yes</td><td>Taxonomy code of the source element (e.g. CP, CR)</td></tr>
 *   <tr><td>TargetCode</td><td>yes</td><td>Taxonomy code of the target element</td></tr>
 *   <tr><td>RelationType</td><td>yes</td><td>A valid {@link RelationType} value</td></tr>
 *   <tr><td>Description</td><td>no</td><td>Human-readable explanation of the relation</td></tr>
 *   <tr><td>SourceStandard</td><td>no</td><td>Framework or standard that justifies this relation (e.g. TOGAF, NAF, LOCAL)</td></tr>
 *   <tr><td>SourceReference</td><td>no</td><td>Specific reference within the standard (e.g. NCV-2, Business Architecture)</td></tr>
 *   <tr><td>Confidence</td><td>no</td><td>Numeric confidence value between 0.0 and 1.0 (default 1.0)</td></tr>
 *   <tr><td>SeedType</td><td>no</td><td>Classification: TYPE_DEFAULT, FRAMEWORK_SEED, or SOURCE_DERIVED (default TYPE_DEFAULT)</td></tr>
 *   <tr><td>ReviewRequired</td><td>no</td><td>Whether human review is needed (default false)</td></tr>
 *   <tr><td>Status</td><td>no</td><td>Current status: accepted or proposed (default accepted)</td></tr>
 * </table>
 *
 * @param sourceCode      taxonomy code of the source element
 * @param targetCode      taxonomy code of the target element
 * @param relationType    the relation type
 * @param description     human-readable description (may be {@code null})
 * @param sourceStandard  originating standard (may be {@code null})
 * @param sourceReference specific reference within the standard (may be {@code null})
 * @param confidence      confidence value between 0.0 and 1.0
 * @param seedType        classification of the seed row
 * @param reviewRequired  whether human review is recommended
 * @param status          current status (accepted / proposed)
 */
public record RelationSeedRow(
        String sourceCode,
        String targetCode,
        RelationType relationType,
        String description,
        String sourceStandard,
        String sourceReference,
        double confidence,
        SeedType seedType,
        boolean reviewRequired,
        String status
) {

    /** Builds a provenance string from seed metadata. */
    public String toProvenance() {
        String base = switch (seedType) {
            case TYPE_DEFAULT -> "csv-default";
            case FRAMEWORK_SEED -> "csv-framework";
            case SOURCE_DERIVED -> "csv-source-derived";
        };
        if (sourceStandard != null && !sourceStandard.isBlank()) {
            return base + ":" + sourceStandard;
        }
        return base;
    }
}
