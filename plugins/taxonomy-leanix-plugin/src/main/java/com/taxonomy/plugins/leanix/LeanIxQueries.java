package com.taxonomy.plugins.leanix;

import com.taxonomy.extension.api.inventory.InventoryReadContracts.*;
import java.util.*;

/** Only fixed query operations; administrative scalar identifiers are validated before interpolation. */
final class LeanIxQueries {
    private LeanIxQueries() { }
    static void validate(Request request) {
        if (!"leanix".equalsIgnoreCase(request.context().externalScope().systemType())
                || !"leanix-read".equals(request.context().profile()) || !"1".equals(request.context().profileVersion()))
            throw new IllegalArgumentException("Unsupported LeanIX read profile");
        for (TypeProjection p : request.projections()) {
            if (!Set.of("Application", "ITComponent").contains(p.sourceType()))
                throw new IllegalArgumentException("Only Application and ITComponent are supported by profile 1");
            if (p.subtypeField() != null) identifier(p.subtypeField());
            p.textFields().forEach((key, field) -> {
                identifier(field);
                if (Set.of("name", "description", "subtype", "lifecycle", "revision").contains(key))
                    throw new IllegalArgumentException("Reserved evidence field name");
            });
        }
        LeanIxHttp.validateOrigin(request.origin());
    }
    static List<Map.Entry<String, String>> fields(TypeProjection p) {
        return p.textFields().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }
    static String objects(Request request, boolean single) {
        StringBuilder selection = new StringBuilder("id name description type rev ");
        for (TypeProjection p : request.projections()) {
            selection.append("... on ").append(p.sourceType()).append(" { lifecycle { phases { phase startDate } } ");
            if (p.subtypeField() != null) selection.append("inventorySubtype: ").append(p.subtypeField()).append(' ');
            int i = 0;
            for (var field : fields(p)) selection.append("inventoryScalar").append(i++).append(": ").append(field.getValue()).append(' ');
            selection.append("} ");
        }
        if (single) return "query InventoryObject($id: ID!) { factSheet(id: $id) { " + selection + " } }";
        return "query InventoryObjects($first: Int!, $after: String, $filter: FilterInput!) { "
                + "allFactSheets(first: $first, after: $after, filter: $filter) { pageInfo { hasNextPage endCursor } "
                + "edges { node { " + selection + " } } } }";
    }
    static final String RELATIONS = """
            query InventoryUsage($id: ID!, $first: Int!, $after: String) {
              factSheet(id: $id) {
                id rev
                ... on Application {
                  relApplicationToITComponent(first: $first, after: $after) {
                    pageInfo { hasNextPage endCursor }
                    edges { node { id activeFrom activeUntil factSheet { id type } } }
                  }
                }
              }
            }
            """;
    private static void identifier(String value) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid scalar field identifier");
    }
}
