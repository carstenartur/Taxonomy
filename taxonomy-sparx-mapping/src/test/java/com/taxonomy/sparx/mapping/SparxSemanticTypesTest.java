package com.taxonomy.sparx.mapping;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SparxSemanticTypesTest {
    @ParameterizedTest
    @CsvSource({"Capability,UseCase", "Process,Activity", "CoreService,Interface", "COIService,Interface",
            "CommunicationsService,Interface", "UserApplication,Component", "InformationProduct,Artifact",
            "BusinessRole,Actor", "System,Component", "Component,Component"})
    void eachCanonicalElementHasItsExistingUmlTransportAndQualifiedStereotype(String canonical, String transport) {
        assertEquals(transport, SparxSemanticTypes.umlType(canonical));
        assertEquals(transport, SparxSemanticTypes.elementTypes().get(canonical));
        assertTrue(SparxSemanticTypes.canonicalTypes().contains(canonical));
        assertEquals("Taxonomy::" + canonical, SparxSemanticTypes.taxonomyStereotype(canonical));
    }

    @ParameterizedTest
    @CsvSource({"DEPENDS_ON,Dependency,false", "REALIZES,Association,true", "COMMUNICATES_WITH,InformationFlow,false",
            "RELATED_TO,Association,false", "CONSUMES,Usage,false", "CONTAINS,Composition,false",
            "SUPPORTS,Association,true", "USES,Association,true", "FULFILLS,Association,true",
            "ASSIGNED_TO,Association,true", "PRODUCES,Association,true", "REQUIRES,Association,true"})
    void eachExplicitRelationHasItsVersionThreeTransportAndQualifiedMeaning(String canonical, String transport,
                                                                            boolean explicitAssociation) {
        assertEquals(transport, SparxSemanticTypes.relationTransport(canonical));
        assertEquals(transport, SparxSemanticTypes.relationTypes().get(canonical));
        assertEquals(explicitAssociation, SparxSemanticTypes.associationTransport(canonical));
        assertEquals(explicitAssociation, SparxSemanticTypes.associationTypes().contains(canonical));
        assertEquals("Taxonomy::" + canonical, SparxSemanticTypes.taxonomyStereotype(canonical));
    }

    @Test
    void declarationsAreCompleteImmutableAndRejectUnsupportedOrMissingNames() {
        assertEquals(Set.of("Capability", "Process", "CoreService", "COIService", "CommunicationsService",
                "UserApplication", "InformationProduct", "BusinessRole", "System", "Component"),
                SparxSemanticTypes.canonicalTypes());
        assertEquals(SparxSemanticTypes.canonicalTypes(), SparxSemanticTypes.elementTypes().keySet());
        assertEquals(Set.of("DEPENDS_ON", "REALIZES", "COMMUNICATES_WITH", "RELATED_TO", "CONSUMES", "CONTAINS",
                "SUPPORTS", "USES", "FULFILLS", "ASSIGNED_TO", "PRODUCES", "REQUIRES"),
                SparxSemanticTypes.relationTypes().keySet());
        assertEquals(Set.of("REALIZES", "SUPPORTS", "USES", "FULFILLS", "ASSIGNED_TO", "PRODUCES", "REQUIRES"),
                SparxSemanticTypes.associationTypes());
        assertThrows(UnsupportedOperationException.class, () -> SparxSemanticTypes.elementTypes().put("Capability", "Class"));
        assertThrows(UnsupportedOperationException.class, () -> SparxSemanticTypes.relationTypes().put("REALIZES", "Realization"));
        assertThrows(UnsupportedOperationException.class, () -> SparxSemanticTypes.canonicalTypes().remove("Capability"));
        assertThrows(UnsupportedOperationException.class, () -> SparxSemanticTypes.associationTypes().remove("REALIZES"));
        for (String invalid : Arrays.asList(null, "", "capability", "Unknown")) {
            assertThrows(IllegalArgumentException.class, () -> SparxSemanticTypes.umlType(invalid));
            assertThrows(IllegalArgumentException.class, () -> SparxSemanticTypes.relationTransport(invalid));
            assertThrows(IllegalArgumentException.class, () -> SparxSemanticTypes.taxonomyStereotype(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> SparxSemanticTypes.umlType("REALIZES"));
        assertThrows(IllegalArgumentException.class, () -> SparxSemanticTypes.relationTransport("Capability"));
    }
}
