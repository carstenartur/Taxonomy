package com.taxonomy.dsl;

import com.taxonomy.dsl.model.TaxonomyRootTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TaxonomyRootTypesTest {

    @ParameterizedTest
    @CsvSource({
            "CP, Capability",
            "BP, Process",
            "CR, CoreService",
            "CI, COIService",
            "CO, CommunicationsService",
            "UA, UserApplication",
            "IP, InformationProduct",
            "BR, BusinessRole",
            "SY, System",
            "CM, Component"
    })
    void typeForReturnsCorrectMapping(String root, String expectedType) {
        assertEquals(expectedType, TaxonomyRootTypes.typeFor(root));
    }

    @Test
    void typeForReturnsUnknownForNull() {
        assertEquals("Unknown", TaxonomyRootTypes.typeFor(null));
    }

    @Test
    void typeForReturnsRootCodeItselfForUnknownRoot() {
        assertEquals("ZZ", TaxonomyRootTypes.typeFor("ZZ"));
    }

    @ParameterizedTest
    @CsvSource({
            "Capability, CP",
            "Process, BP",
            "CoreService, CR",
            "COIService, CI",
            "CommunicationsService, CO",
            "UserApplication, UA",
            "InformationProduct, IP",
            "BusinessRole, BR",
            "System, SY",
            "Component, CM"
    })
    void rootForReturnsCorrectReverseMapping(String typeName, String expectedRoot) {
        assertEquals(expectedRoot, TaxonomyRootTypes.rootFor(typeName));
    }

    @Test
    void rootForReturnsNullForNull() {
        assertNull(TaxonomyRootTypes.rootFor(null));
    }

    @Test
    void rootForReturnsNullForUnknownType() {
        assertNull(TaxonomyRootTypes.rootFor("NonExistentType"));
    }

    @Test
    void rootForReturnsRootCodeWhenPassedRootCode() {
        // If "CP" is passed, it's a valid root code so it returns "CP"
        assertEquals("CP", TaxonomyRootTypes.rootFor("CP"));
    }

    @ParameterizedTest
    @CsvSource({
            "CP-1023, CP",
            "CR-1047, CR",
            "BP-2001, BP",
            "UA-3001, UA",
            "IP-4001, IP",
            "SY-0001, SY",
            "CM-0001, CM"
    })
    void rootFromIdExtractsCorrectPrefix(String elementId, String expectedRoot) {
        assertEquals(expectedRoot, TaxonomyRootTypes.rootFromId(elementId));
    }

    @Test
    void rootFromIdReturnsNullForNull() {
        assertNull(TaxonomyRootTypes.rootFromId(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "C", "X"})
    void rootFromIdReturnsNullForShortIds(String id) {
        assertNull(TaxonomyRootTypes.rootFromId(id));
    }

    @Test
    void rootFromIdReturnsNullForUnknownPrefix() {
        assertNull(TaxonomyRootTypes.rootFromId("ZZ-1234"));
    }

    @Test
    void rootFromIdReturnsNullForNoDash() {
        assertNull(TaxonomyRootTypes.rootFromId("CP1023"));
    }

    @Test
    void rootFromIdReturnsNullForDashAtStart() {
        assertNull(TaxonomyRootTypes.rootFromId("-CP1023"));
    }

    @Test
    void rootToTypeMapHasExpectedSize() {
        assertEquals(10, TaxonomyRootTypes.ROOT_TO_TYPE.size());
    }

    @Test
    void typeToRootMapHasExpectedSize() {
        assertEquals(10, TaxonomyRootTypes.TYPE_TO_ROOT.size());
    }

    @Test
    void mapsAreUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> TaxonomyRootTypes.ROOT_TO_TYPE.put("XX", "Test"));
        assertThrows(UnsupportedOperationException.class,
                () -> TaxonomyRootTypes.TYPE_TO_ROOT.put("Test", "XX"));
    }
}
