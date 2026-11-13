package com.taxonomy.analysis.dag.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.StdConverter;
import com.taxonomy.analysis.dag.AnalysisOperationContext;
import com.taxonomy.analysis.dag.AnalysisSourceAuthority;
import com.taxonomy.analysis.dag.RequirementReference;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

/** Version-four source fields appear only when bound, including in portable backup rows. */
public final class AnalysisContextJson extends StdConverter<AnalysisOperationContext, Map<String, Object>> {
    private interface AuthorityFields {
        @JsonInclude(JsonInclude.Include.NON_NULL) String semanticModelSha256();
    }

    private interface RequirementFields {
        @JsonInclude(JsonInclude.Include.NON_NULL) Long requirementVersionId();
    }

    static JsonMapper.Builder withSourceFields(JsonMapper.Builder builder) {
        return builder.addMixIn(AnalysisSourceAuthority.class, AuthorityFields.class)
                .addMixIn(RequirementReference.class, RequirementFields.class);
    }

    private static final JsonMapper CONTEXT = withSourceFields(JsonMapper.builder()).build();
    private static final TypeReference<Map<String, Object>> FIELDS = new TypeReference<>() { };

    /** The portable row writer uses Jackson 2 and consumes the same plain JSON field values. */
    @Override public Map<String, Object> convert(AnalysisOperationContext context) {
        return CONTEXT.convertValue(context, FIELDS);
    }
    /** Restore the two explicitly omitted legacy fields while retaining the enclosing reader's strict schema. */
    public static final class Reader extends JsonDeserializer<AnalysisOperationContext> {
        @Override public AnalysisOperationContext deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonNode value = context.readTree(parser);
            if (value.path("authority") instanceof ObjectNode authority && !authority.has("semanticModelSha256"))
                authority.putNull("semanticModelSha256");
            if (value.path("requirement") instanceof ObjectNode requirement && !requirement.has("requirementVersionId"))
                requirement.putNull("requirementVersionId");
            return context.readTreeAsValue(value, AnalysisOperationContext.class);
        }
    }

}
