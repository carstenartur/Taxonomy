package com.taxonomy.dto;

import com.taxonomy.dto.RequirementNodeAssessment.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RequirementAssessmentValueTest {
    @Test
    void publishedEvidenceDoesNotShareMutableCollectionsWithItsCaller() {
        var subjects = new ArrayList<>(List.of(SubjectKind.SERVICE, SubjectKind.PRODUCT, SubjectKind.SERVICE));
        var assessment = new RequirementNodeAssessment("  Use the archive.  ", subjects, List.of(RequirementKind.FUNCTIONAL),
                ContributionType.SUPPORTS, EvidenceStatus.SUPPORTED, EvidenceBasis.INFERRED,
                Necessity.REQUIRED, Scope.EXISTING_ENVIRONMENT, "", "The archive enables recovery.", "");
        var claims = new ArrayList<>(List.of(assessment));
        var supplied = new LinkedHashMap<String, List<RequirementNodeAssessment>>();
        supplied.put("BP-2", claims);
        var result = new AnalysisResult();
        result.setNodeAssessments(supplied);
        subjects.clear();
        claims.clear();
        supplied.clear();

        assertEquals(List.of(SubjectKind.SERVICE, SubjectKind.PRODUCT), assessment.subjectKinds());
        assertEquals(List.of(assessment), result.getNodeAssessments().get("BP-2"));
        assertThrows(UnsupportedOperationException.class, () -> result.getNodeAssessments().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.getNodeAssessments().get("BP-2").clear());
        assertThrows(UnsupportedOperationException.class, () -> assessment.subjectKinds().clear());
    }
}
