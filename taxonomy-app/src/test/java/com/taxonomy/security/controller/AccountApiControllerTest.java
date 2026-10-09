package com.taxonomy.security.controller;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

class AccountApiControllerTest {

    @TestFactory
    Stream<DynamicTest> passwordChangeHttpContract() {
        return AccountApiControllerCases.cases().entrySet().stream()
                .map(entry -> DynamicTest.dynamicTest(entry.getKey(), entry.getValue()::run));
    }
}
