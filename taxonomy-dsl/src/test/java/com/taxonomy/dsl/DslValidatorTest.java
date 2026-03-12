package com.taxonomy.dsl;

import com.taxonomy.dsl.model.*;
import com.taxonomy.dsl.validation.DslValidationResult;
import com.taxonomy.dsl.validation.DslValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DslValidator}.
 */
class DslValidatorTest {

    private DslValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DslValidator();
    }

    @Test
    void validModelPassesValidation() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test", "Desc", "CP"));
        model.getElements().add(new ArchitectureElement("BP-1040", "Process", "Test 2", "Desc 2", "BP"));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "REALIZES", "BP-1040");
        rel.setStatus("accepted");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void duplicateElementIdIsError() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "First", null, null));
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Duplicate", null, null));

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Duplicate element ID: CP-1001"));
    }

    @Test
    void duplicateRequirementIdIsError() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getRequirements().add(new ArchitectureRequirement("REQ-001", "First", "Text"));
        model.getRequirements().add(new ArchitectureRequirement("REQ-001", "Duplicate", "Text"));

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Duplicate requirement ID: REQ-001"));
    }

    @Test
    void relationWithUnknownSourceIdIsWarning() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("BP-1040", "Process", "Test", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("UNKNOWN-1", "SUPPORTS", "BP-1040");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isTrue(); // warnings don't make it invalid
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("unknown source ID: UNKNOWN-1"));
    }

    @Test
    void relationWithUnknownTargetIdIsWarning() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "REALIZES", "UNKNOWN-2");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("unknown target ID: UNKNOWN-2"));
    }

    @Test
    void invalidRelationStatusIsError() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test", null, null));
        model.getElements().add(new ArchitectureElement("BP-1040", "Process", "Test", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "REALIZES", "BP-1040");
        rel.setStatus("invalid-status");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("Invalid relation status"));
    }

    @Test
    void unknownRelationTypeIsWarning() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("A", "Capability", "Test", null, null));
        model.getElements().add(new ArchitectureElement("B", "Process", "Test", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("A", "UNKNOWN_TYPE", "B");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("Unknown relation type: UNKNOWN_TYPE"));
    }

    @Test
    void missingElementIdIsError() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement(null, "Capability", "Test", null, null));

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).anyMatch(e -> e.contains("missing an ID"));
    }

    @Test
    void missingElementTitleIsWarning() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", null, null, null));

        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("missing a title"));
    }

    @Test
    void mappingWithUnknownRequirementIsWarning() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test", null, null));

        RequirementMapping mapping = new RequirementMapping("REQ-UNKNOWN", "CP-1001");
        model.getMappings().add(mapping);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("unknown requirement ID"));
    }

    @Test
    void mappingWithUnknownElementIsWarning() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getRequirements().add(new ArchitectureRequirement("REQ-001", "Req", "Text"));

        RequirementMapping mapping = new RequirementMapping("REQ-001", "UNKNOWN-EL");
        model.getMappings().add(mapping);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("unknown element ID"));
    }

    @Test
    void emptyModelIsValid() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        DslValidationResult result = validator.validate(model);
        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
    }

    // --- Type-combination validation tests (type matrix) ---

    @Test
    void validTypeCombinationRealizes() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test Cap", null, null));
        model.getElements().add(new ArchitectureElement("CR-2001", "CoreService", "Test Svc", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "REALIZES", "CR-2001");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("not a valid source type") || w.contains("not a valid target type"));
    }

    @Test
    void invalidSourceTypeForRelation() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        // BP (Process) should not be source for REALIZES (requires CP)
        model.getElements().add(new ArchitectureElement("BP-1040", "Process", "Test Proc", null, null));
        model.getElements().add(new ArchitectureElement("CR-2001", "CoreService", "Test Svc", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("BP-1040", "REALIZES", "CR-2001");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("not a valid source type for REALIZES"));
    }

    @Test
    void invalidTargetTypeForRelation() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        // CP→BP is wrong for REALIZES; REALIZES requires CP→CR
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test Cap", null, null));
        model.getElements().add(new ArchitectureElement("BP-1040", "Process", "Test Proc", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "REALIZES", "BP-1040");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).anyMatch(w -> w.contains("not a valid target type"));
    }

    @Test
    void relatedToHasNoTypeRestrictions() {
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CP-1001", "Capability", "Test", null, null));
        model.getElements().add(new ArchitectureElement("BP-1040", "Process", "Test", null, null));

        ArchitectureRelation rel = new ArchitectureRelation("CP-1001", "RELATED_TO", "BP-1040");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("not a valid source type") || w.contains("not a valid target type"));
    }

    @Test
    void typeCombinationByIdPrefix() {
        // When no explicit type is provided, use ID prefix to resolve root
        CanonicalArchitectureModel model = new CanonicalArchitectureModel();
        model.getElements().add(new ArchitectureElement("CR-2001", null, "Service", null, null));
        model.getElements().add(new ArchitectureElement("BP-1040", null, "Process", null, null));

        // CR→BP for SUPPORTS is valid
        ArchitectureRelation rel = new ArchitectureRelation("CR-2001", "SUPPORTS", "BP-1040");
        model.getRelations().add(rel);

        DslValidationResult result = validator.validate(model);
        assertThat(result.getWarnings()).noneMatch(w -> w.contains("not a valid source type") || w.contains("not a valid target type"));
    }
}
