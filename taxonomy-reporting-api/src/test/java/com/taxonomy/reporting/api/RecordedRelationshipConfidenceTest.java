package com.taxonomy.reporting.api;
import com.taxonomy.dto.RequirementRelationshipView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RecordedRelationshipConfidenceTest {
    @Test void legacyDefaultIsDistinctFromExplicitZeroConfidence() throws Exception {
        var view = new RequirementRelationshipView();
        var accessor = java.util.Arrays.stream(view.getClass().getMethods())
                .filter(method -> method.getName().equals("getRecordedConfidence")).findFirst();
        assertTrue(accessor.isPresent(), "A nullable recorded-confidence accessor is required");
        assertNull(accessor.get().invoke(view));
        assertEquals(0d, view.getConfidence());
        view.setConfidence(0d);
        assertEquals(0d, accessor.get().invoke(view));
    }
    @Test void serializedAbsenceAndExplicitZeroRemainDistinctOnRoundTrip() throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var absent = new RequirementRelationshipView();
        var json = mapper.writeValueAsString(absent);
        assertTrue(json.contains("\"confidence\":null") || !json.contains("\"confidence\""), json);
        var restored = mapper.readValue(json, RequirementRelationshipView.class);
        var accessor = restored.getClass().getMethod("getRecordedConfidence");
        assertNull(accessor.invoke(restored));
        absent.setConfidence(0d);
        var zeroJson = mapper.writeValueAsString(absent);
        assertTrue(zeroJson.contains("\"confidence\":0.0"), zeroJson);
        assertFalse(zeroJson.contains("recordedConfidence"), zeroJson);
        assertEquals(0d, accessor.invoke(mapper.readValue(zeroJson, RequirementRelationshipView.class)));
    }
}
