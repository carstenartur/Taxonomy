package com.taxonomy.openapi;

import com.taxonomy.analysis.controller.AnalysisProgressController;
import com.taxonomy.backup.web.BackupJobController;
import com.taxonomy.editor.ArchitectureEditorController;
import com.taxonomy.interop.controller.IntegrationController;
import com.taxonomy.analysis.recovery.AnalysisContinuationController;
import com.taxonomy.composition.analysis.artemis.AnalysisDispatchAdminController;
import com.taxonomy.composition.reformulation.ReformulationAdoptionController;
import com.taxonomy.export.controller.ExportApiController;
import com.taxonomy.shared.config.OpenApiConfig;
import com.taxonomy.templates.DocumentTemplateAdminController;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The real Spring MVC mappings and Springdoc generator, without a database, broker
 * or provider. Controller collaborators are deliberately absent: schema reads must
 * never execute a business operation. Security remains covered by the application
 * security suites; this fixture does not replace them.
 */
public final class OpenApiContractCases {
    private static final String ADOPTION =
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}";
    private static final String TEMPLATES = "/api/admin/document-templates";
    private static final String DOTX = "application/vnd.openxmlformats-officedocument.wordprocessingml.template";

    private OpenApiContractCases() { }

    public static void verify() throws Exception {
        var app = new SpringApplication(ApiFixture.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setRegisterShutdownHook(false);
        try (var context = app.run(
                "--spring.config.location=optional:classpath:/openapi-contract-only.properties",
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off",
                "--springdoc.api-docs.enabled=true", "--springdoc.swagger-ui.enabled=false",
                "--springdoc.api-docs.version=OPENAPI_3_0",
                "--taxonomy.analysis.transport.mode=artemis", "--taxonomy.backup.enabled=true",
                "--logging.level.root=WARN")) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var response = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + port + "/v3/api-docs"))
                    .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
            require(response.statusCode() == 200, "OpenAPI generation returned " + response.statusCode());
            JsonNode document = JsonMapper.builder().build().readTree(response.body());
            checkDocument(document);
            checkRegisteredMappings(document, context.getBean(
                    org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class));
        }
    }

    private static void checkRegisteredMappings(JsonNode document,
            org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings) {
        var errors = new ArrayList<String>();
        var seen = new java.util.HashSet<String>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getName().startsWith("com.taxonomy.")) return;
            for (String path : mapping.getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    String verb = method.name().toLowerCase(java.util.Locale.ROOT);
                    operation(document, path, verb, errors);
                    seen.add(verb + " " + path);
                }
            }
        });
        for (var path : document.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) continue;
            for (var method : path.getValue().properties()) {
                if (List.of("get", "post", "put", "patch", "delete").contains(method.getKey())) {
                    check(seen.contains(method.getKey() + " " + path.getKey()),
                            "OpenAPI advertises an unregistered mapping: " + method.getKey() + " " + path.getKey(), errors);
                }
            }
        }
        require(!seen.isEmpty(), "No application MVC mappings registered");
        require(errors.isEmpty(), String.join("\\n", errors));
    }

    static void checkDocument(JsonNode document) {
        var errors = new ArrayList<String>();
        for (var path : document.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) continue;
            for (var method : path.getValue().properties()) {
                if (!List.of("get", "post", "put", "patch", "delete").contains(method.getKey())) continue;
                var operation = method.getValue();
                String name = method.getKey().toUpperCase() + " " + path.getKey();
                check(!operation.path("summary").asText("").isBlank(), name + ": missing summary", errors);
                check(!operation.path("description").asText("").isBlank(), name + ": missing description", errors);
                check(!operation.path("operationId").asText("").isBlank(), name + ": missing operationId", errors);
                check(operation.path("responses").size() > 0, name + ": missing responses", errors);
                for (var parameter : operation.path("parameters")) {
                    check(!parameter.path("description").asText("").isBlank(),
                            name + ": undocumented " + parameter.path("name").asText(), errors);
                }
            }
        }
        for (String suffix : List.of("/adoption-previews", "/adoptions")) {
            var operation = operation(document, ADOPTION + suffix, "post", errors);
            var header = parameter(operation, "If-Match", "header");
            check(header.path("required").asBoolean(false), suffix + ": If-Match must be required", errors);
            check(header.path("schema").path("pattern").asText("").contains("[1-9]"),
                    suffix + ": missing quoted revision pattern", errors);
            for (String status : List.of("400", "404", "412", "428")) {
                check(operation.path("responses").has(status), suffix + ": missing response " + status, errors);
            }
        }
        var upload = operation(document, TEMPLATES + "/{templateId}", "put", errors);
        check(upload.path("requestBody").path("required").asBoolean(false), "DOTX body must be required", errors);
        check(binary(upload.path("requestBody").path("content").path(DOTX)),
                "DOTX upload must describe a raw binary body", errors);
        check(binary(upload.path("requestBody").path("content").path("application/octet-stream")),
                "Octet-stream upload must describe a raw binary body", errors);
        check(upload.path("responses").has("201"), "DOTX upload must document 201", errors);
        check(upload.path("responses").has("412"), "Template head conflict must document 412, not 409", errors);
        check(!upload.path("responses").has("409"), "Template conflict is not HTTP 409", errors);
        var confirm = operation(document, ADOPTION + "/adoptions", "post", errors);
        check(confirm.path("responses").has("409"), "Adoption conflict response missing", errors);
        var confirmation = resolve(document, confirm.path("requestBody").path("content")
                .path("application/json").path("schema"));
        check(contains(confirmation.path("required"), "rationale"), "Adoption rationale is always required", errors);
        check(confirmation.path("properties").path("rationale").path("maxLength").asInt() == 1000,
                "Adoption rationale length bound missing", errors);
        var download = operation(document, TEMPLATES + "/{templateId}/download", "get", errors);
        check(binary(download.path("responses").path("200").path("content").path(DOTX)),
                "DOTX download must describe binary content", errors);
        check(download.path("responses").path("200").path("headers").has("ETag"),
                "DOTX download must describe ETag", errors);

        var backup = operation(document, "/api/backups/jobs", "post", errors);
        check(backup.path("requestBody").path("required").asBoolean(false), "Backup JSON request body is required", errors);
        var backupSchema = resolve(document, backup.path("requestBody").path("content").path("application/json").path("schema"));
        for (String field : List.of("profile", "scope", "time", "gitRepresentation", "secrets")) {
            check(backupSchema.path("properties").has(field), "Backup wire field missing: " + field, errors);
            check(contains(backupSchema.path("required"), field), "Backup wire field must be required: " + field, errors);
        }
        var archive = operation(document, "/api/backups/jobs/{id}/download", "get", errors);
        check(binary(archive.path("responses").path("200").path("content").path("application/octet-stream")),
                "Servlet-streamed backup download missing binary schema", errors);
        var editor = operation(document, "/api/architecture/editor/commands", "post", errors);
        check(parameter(editor, "If-Match", "header").path("required").asBoolean(false),
                "Editor command requires semantic If-Match", errors);

        for (String status : List.of("200", "202", "400", "409", "412", "422", "428", "503")) {
            check(editor.path("responses").has(status), "Editor command response missing: " + status, errors);
        }
        var templateFailure = resolve(document, upload.path("responses").path("412")
                .path("content").path("application/json").path("schema"));
        check(templateFailure.path("properties").has("error"), "Template errors must describe the real error body", errors);
        var dispatch = operation(document, "/api/admin/analysis/dispatch", "get", errors);
        check(!dispatch.path("description").asText("").isBlank(), "Artemis admin endpoint absent", errors);
        var visio = operation(document, "/api/diagram/visio", "post", errors);
        check(visio.path("responses").has("500"), "Visio must document generation failure", errors);
        var requestSchema = resolve(document, visio.path("requestBody").path("content")
                .path("application/json").path("schema"));
        check(requestSchema.path("properties").has("businessText"), "Visio requires a described request schema", errors);
        check(contains(requestSchema.path("required"), "businessText"), "businessText must be required", errors);
        var events = operation(document, "/api/analysis-runs/{operationId}/events", "get", errors);
        check(events.path("responses").path("200").path("content").has("text/event-stream"),
                "SSE media type missing", errors);
        require(errors.isEmpty(), errors.size() + " OpenAPI contract failures:\n" + String.join("\n", errors));
    }

    private static JsonNode operation(JsonNode doc, String path, String method, List<String> errors) {
        var value = doc.path("paths").path(path).path(method);
        check(!value.isMissingNode(), "Missing " + method + " " + path, errors);
        return value;
    }
    private static JsonNode parameter(JsonNode operation, String name, String in) {
        for (var p : operation.path("parameters")) {
            if (name.equals(p.path("name").asText()) && in.equals(p.path("in").asText())) return p;
        }
        return operation.path("__absent_parameter__");
    }
    private static JsonNode resolve(JsonNode doc, JsonNode schema) {
        String ref = schema.path("$ref").asText("");
        return ref.startsWith("#/") ? doc.at(ref.substring(1)) : schema;
    }
    private static boolean binary(JsonNode media) {
        return "string".equals(media.path("schema").path("type").asText())
                && "binary".equals(media.path("schema").path("format").asText());
    }
    private static boolean contains(JsonNode values, String value) {
        for (var item : values) if (value.equals(item.asText())) return true;
        return false;
    }
    private static void check(boolean condition, String message, List<String> errors) {
        if (!condition) errors.add(message);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        verify();
        System.out.println("OpenAPI generated-contract checks passed");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class
    })
    @org.springframework.boot.context.properties.EnableConfigurationProperties(org.springdoc.core.properties.SpringDocConfigProperties.class)
    @Import(OpenApiConfig.class)
    static class ApiFixture {
        @Bean BackupJobController backupController() {
            return new BackupJobController(null, null);
        }
        @Bean ArchitectureEditorController editorController() {
            return new ArchitectureEditorController(null, null, null);
        }
        @Bean IntegrationController integrationController() {
            return new IntegrationController(null, null);
        }
        @Bean AnalysisDispatchAdminController dispatchController() {
            return new AnalysisDispatchAdminController(null, null, null);
        }
        @Bean AnalysisContinuationController continuationController() {
            return new AnalysisContinuationController(null, null);
        }
        @Bean DocumentTemplateAdminController templateController() {
            return new DocumentTemplateAdminController(null);
        }
        @Bean ReformulationAdoptionController adoptionController() {
            return new ReformulationAdoptionController(null, null);
        }
        @Bean AnalysisProgressController progressController() {
            return new AnalysisProgressController(null, null);
        }
        @Bean ExportApiController exportController() {
            return new ExportApiController(null, null);
        }
    }
}
