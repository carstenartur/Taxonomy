package com.taxonomy;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Catalogue-backed source shared by screenshot generation and its parser contract. */
final class SavedGraphFixtureDsl {
    private SavedGraphFixtureDsl() {}

    static String create(Map<?, ?> names) {
        var json = new ObjectMapper();
        var types = new LinkedHashMap<String, String>();
        types.put("CP-1023", "Capability"); types.put("CR-1047", "CoreService");
        types.put("BP-1327", "Process"); types.put("BP-1000", "Process");
        StringBuilder dsl = new StringBuilder("meta {\n  language: \"taxdsl\";\n  version: \"2.0\";\n  namespace: \"default\";\n}\n");
        types.forEach((code, type) -> {
            if (!(names.get(code) instanceof String title) || title.isBlank()) {
                throw new IllegalArgumentException("Catalogue node must exist: " + code);
            }
            dsl.append("element ").append(code).append(" type ").append(type)
                    .append(" {\n  title: ").append(json.writeValueAsString(title)).append(";\n}\n");
        });
        return dsl.append("relation CP-1023 REALIZES CR-1047 {\n  status: accepted;\n}\n")
                .append("relation CR-1047 SUPPORTS BP-1327 {\n  status: accepted;\n}\n")
                .append("relation CR-1047 SUPPORTS BP-1000 {\n  status: accepted;\n}\n").toString();
    }
}
