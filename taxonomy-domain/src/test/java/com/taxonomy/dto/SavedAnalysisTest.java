package com.taxonomy.dto;

import com.taxonomy.model.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SavedAnalysisTest {

    @Test
    void defaultVersionIsTwo() {
        var dto = new SavedAnalysis();
        assertEquals(2, dto.getVersion());
    }

    @Test
    void defaultConstructorSetsNullForOptionalFields() {
        var dto = new SavedAnalysis();

        assertNull(dto.getRequirement());
        assertNull(dto.getTimestamp());
        assertNull(dto.getProvider());
        assertNull(dto.getScores());
        assertNull(dto.getReasons());
        assertNull(dto.getSources());
        assertNull(dto.getSourceVersions());
        assertNull(dto.getSourceFragments());
        assertNull(dto.getRequirementSourceLinks());
    }

    @Test
    void allGettersAndSettersWork() {
        var dto = new SavedAnalysis();
        dto.setVersion(1);
        dto.setRequirement("Process incoming mail");
        dto.setTimestamp("2024-06-15T12:00:00Z");
        dto.setProvider("gemini");
        dto.setScores(Map.of("CP-1023", 80, "CR-1047", 60));
        dto.setReasons(Map.of("CP-1023", "Relevant capability"));
        dto.setSources(List.of(new SourceArtifactDto(SourceType.REGULATION, "Test")));
        dto.setSourceVersions(List.of(new SourceVersionDto()));
        dto.setSourceFragments(List.of(new SourceFragmentDto()));
        dto.setRequirementSourceLinks(List.of(new RequirementSourceLinkDto()));

        assertEquals(1, dto.getVersion());
        assertEquals("Process incoming mail", dto.getRequirement());
        assertEquals("2024-06-15T12:00:00Z", dto.getTimestamp());
        assertEquals("gemini", dto.getProvider());
        assertEquals(2, dto.getScores().size());
        assertEquals(80, dto.getScores().get("CP-1023"));
        assertEquals(1, dto.getReasons().size());
        assertEquals(1, dto.getSources().size());
        assertEquals(1, dto.getSourceVersions().size());
        assertEquals(1, dto.getSourceFragments().size());
        assertEquals(1, dto.getRequirementSourceLinks().size());
    }

    @Test
    void scoreZeroMeansEvaluatedNotRelevant() {
        var dto = new SavedAnalysis();
        dto.setScores(Map.of("CP-1023", 0));

        assertTrue(dto.getScores().containsKey("CP-1023"));
        assertEquals(0, dto.getScores().get("CP-1023"));
    }

    @Test
    void absentScoreMeansNotEvaluated() {
        var dto = new SavedAnalysis();
        dto.setScores(Map.of("CP-1023", 80));

        assertFalse(dto.getScores().containsKey("CR-1047"));
    }

    @Test
    void provenanceFieldsCanBeNull() {
        var dto = new SavedAnalysis();
        dto.setVersion(2);

        assertNull(dto.getSources());
        assertNull(dto.getSourceVersions());
        assertNull(dto.getSourceFragments());
        assertNull(dto.getRequirementSourceLinks());
    }
}
