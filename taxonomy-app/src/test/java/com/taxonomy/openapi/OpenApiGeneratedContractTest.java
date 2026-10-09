package com.taxonomy.openapi;

import org.junit.jupiter.api.Test;

class OpenApiGeneratedContractTest {
    @Test
    void generatedSpecificationDescribesTheRealControllerContracts() throws Exception {
        OpenApiContractCases.verify();
    }
}
