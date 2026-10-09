package com.taxonomy.openapi;

import org.junit.jupiter.api.Test;

class RestApiDocumentationTest {
    @Test
    void everyControllerIncludingConditionalAdaptersHasOperationDocumentation() throws Exception {
        RestApiDocumentationCases.verify();
    }
    @Test
    void nonPublicMappedMethodsCannotEvadeDocumentationChecks() {
        RestApiDocumentationDiscoveryCases.verify();
    }

    @Test
    void testFixturesCannotLeakIntoOtherApplicationContexts() {
        RestApiDocumentationDiscoveryCases.verifyIsolation();
    }
}
