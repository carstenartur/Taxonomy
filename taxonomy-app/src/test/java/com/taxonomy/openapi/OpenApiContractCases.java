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
import com.taxonomy.security.controller.AccountApiController;
import com.taxonomy.security.service.PasswordChangeService;
import com.taxonomy.setup.DatabaseSetupController;
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
    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "patch", "delete", "head", "options", "trace");
    private static final String ADOPTION =
            "/api/projects/{projectId}/requirements/{requirementId}/reformulations/{proposalId}";
    private static final String TEMPLATES = "/api/admin/document-templates";
    private static final String DATABASE_SETUP = "/api/admin/database-setup";
    private static final String DOTX = "application/vnd.openxmlformats-officedocument.wordprocessingml.template";

    private OpenApiContractCases() { }

    public static void verify() throws Exception {
        var current = document();
        checkDocument(current);
        verifyContractMutations(current);
        for (String setting : List.of("--taxonomy.security.change-password-enabled=false",
                "--spring.profiles.active=keycloak")) {
            require(!document(setting).path("paths").has("/api/account/change-password"),
                    "Disabled local-account API must not be advertised: " + setting);
        }
    }

    private static JsonNode document(String... settings) throws Exception {
        var app = new SpringApplication(ApiFixture.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setRegisterShutdownHook(false);
        app.setAdditionalProfiles("openapi-contract-fixture");
        var arguments = new ArrayList<>(List.of(
                "--spring.config.location=optional:classpath:/openapi-contract-only.properties",
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.main.banner-mode=off",
                "--springdoc.api-docs.enabled=true", "--springdoc.swagger-ui.enabled=false",
                "--springdoc.api-docs.version=OPENAPI_3_0",
                "--taxonomy.analysis.transport.mode=artemis", "--taxonomy.backup.enabled=true",
                "--logging.level.root=WARN"));
        arguments.addAll(List.of(settings));
        try (var context = app.run(arguments.toArray(String[]::new))) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
                var response = client.send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/v3/api-docs"))
                        .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString());
                require(response.statusCode() == 200, "OpenAPI generation returned " + response.statusCode());
                JsonNode document = JsonMapper.builder().build().readTree(response.body());
                checkRegisteredMappings(document, context.getBean(
                        org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class));
                return document;
            }
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
                if (HTTP_METHODS.contains(method.getKey())) {
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
        checkLocalReferences(document, document, errors);
        var operationIds = new java.util.HashSet<String>();
        for (var path : document.path("paths").properties()) {
            if (!path.getKey().startsWith("/api/")) continue;
            for (var method : path.getValue().properties()) {
                if (!HTTP_METHODS.contains(method.getKey())) continue;
                var operation = method.getValue();
                String name = method.getKey().toUpperCase() + " " + path.getKey();
                check(!operation.path("summary").asText("").isBlank(), name + ": missing summary", errors);
                check(!operation.path("description").asText("").isBlank(), name + ": missing description", errors);
                check(!operation.path("operationId").asText("").isBlank(), name + ": missing operationId", errors);
                check(operationIds.add(operation.path("operationId").asText("")), name + ": duplicate operationId", errors);
                check(operation.path("responses").size() > 0, name + ": missing responses", errors);
                for (var response : operation.path("responses").properties()) {
                    check(!resolve(document, response.getValue()).path("description").asText("").isBlank(),
                            name + ": undocumented response " + response.getKey(), errors);
                }
                for (var parameter : operation.path("parameters")) {
                    check(!parameter.path("description").asText("").isBlank(),
                            name + ": undocumented " + parameter.path("name").asText(), errors);
                }
            }
        }
        checkAccountContract(document, errors);
        checkDatabaseSetupContract(document, errors);
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

    private static void verifyContractMutations(JsonNode current) {
        String account = "/paths/~1api~1account~1change-password/post";
        rejectsMutation(current, copy -> object(copy.at(account + "/responses")).remove("401"),
                "Password change response missing: 401");
        rejectsMutation(current, copy -> object(copy.at(account + "/responses/200")).put("description", ""),
                "undocumented response 200");
        rejectsMutation(current, copy -> object(copy.at(account)).put("operationId",
                copy.path("paths").path(TEMPLATES + "/{templateId}").path("put").path("operationId").asText()),
                "duplicate operationId");
        rejectsMutation(current, copy -> object(copy.at(account + "/responses/400/content/application~1json/schema/anyOf/0"))
                        .put("$ref", "#/components/schemas/absent-contract-fixture"),
                "Unresolved local OpenAPI reference");
        rejectsMutation(current, copy -> object(resolve(copy,
                copy.at(account + "/requestBody/content/application~1json/schema"))).remove("required"),
                "Required password field missing");
        rejectsMutation(current, copy -> object(resolve(copy,
                copy.at(account + "/requestBody/content/application~1json/schema"))
                .path("properties").path("newPassword")).put("writeOnly", false),
                "Password must be write-only");
        rejectsMutation(current, copy -> object(resolve(copy,
                copy.at(account + "/requestBody/content/application~1json/schema"))
                .path("properties").path("newPassword")).put("minLength", 12),
                "JSON Schema code-point length");
    }

    private static tools.jackson.databind.node.ObjectNode object(JsonNode node) {
        return (tools.jackson.databind.node.ObjectNode) node;
    }

    private static void rejectsMutation(JsonNode original,
            java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> mutate, String expected) {
        var damaged = object(original.deepCopy());
        mutate.accept(damaged);
        try {
            checkDocument(damaged);
        } catch (AssertionError failure) {
            require(failure.getMessage().contains(expected), "Mutation failed for the wrong reason: " + failure.getMessage());
            return;
        }
        throw new AssertionError("Invalid OpenAPI document was accepted: " + expected);
    }

    private static void checkLocalReferences(JsonNode document, JsonNode node, List<String> errors) {
        if (node.isObject()) {
            String ref = node.path("$ref").asText("");
            if (ref.startsWith("#/")) {
                check(!document.at(ref.substring(1)).isMissingNode(), "Unresolved local OpenAPI reference: " + ref, errors);
            }
            for (var property : node.properties()) checkLocalReferences(document, property.getValue(), errors);
        } else if (node.isArray()) {
            for (var child : node) checkLocalReferences(document, child, errors);
        }
    }

    private static void checkAccountContract(JsonNode document, List<String> errors) {
        var account = operation(document, "/api/account/change-password", "post", errors);
        for (String status : List.of("200", "400", "401", "403")) {
            check(account.path("responses").has(status), "Password change response missing: " + status, errors);
        }
        check(account.path("requestBody").path("required").asBoolean(false),
                "Password change body must be required", errors);
        var request = resolve(document, account.path("requestBody").path("content")
                .path("application/json").path("schema"));
        check(request.path("required").size() == 3 && request.path("properties").size() == 3,
                "Password request must describe exactly the three known fields", errors);
        check("string".equals(request.path("additionalProperties").path("type").asText()),
                "Additional string fields must retain the existing open-map binding", errors);
        for (String name : List.of("currentPassword", "newPassword", "confirmPassword")) {
            var property = request.path("properties").path(name);
            check(contains(request.path("required"), name), "Required password field missing: " + name, errors);
            check("string".equals(property.path("type").asText()), "Password must be a string: " + name, errors);
            check("password".equals(property.path("format").asText()), "Password format missing: " + name, errors);
            check(property.path("writeOnly").asBoolean(false), "Password must be write-only: " + name, errors);
            check(!property.path("description").asText("").isBlank(), "Password description missing: " + name, errors);
        }
        var replacement = request.path("properties").path("newPassword");
        check(replacement.path("description").asText("").contains("UTF-16"),
                "Password length must describe the actual Java UTF-16 policy", errors);
        check(!replacement.has("minLength"),
                "JSON Schema code-point length must not misrepresent Java UTF-16 length", errors);
        check(!contains(request.path("required"), "username"), "Account comes from authentication, not request username", errors);
        var success = resolve(document, account.path("responses").path("200")
                .path("content").path("application/json").path("schema"));
        check(success.path("properties").size() == 2, "Password success must not advertise echoed input secrets", errors);
        check(contains(success.path("required"), "status") && contains(success.path("required"), "message"),
                "Password success must describe both actual response fields", errors);
        check(contains(success.path("properties").path("status").path("enum"), "PASSWORD_CHANGED"),
                "Password success status missing", errors);
        var badRequest = account.path("responses").path("400")
                .path("content").path("application/json").path("schema");
        check(badRequest.path("anyOf").size() == 2,
                "400 must distinguish domain validation from framework binding errors", errors);
        check("object".equals(resolve(document, badRequest.path("anyOf").path(1)).path("type").asText()),
                "Framework binding errors need a separate error-object alternative", errors);
        var rejection = resolve(document, badRequest.path("anyOf").path(0));
        check(contains(rejection.path("required"), "error") && contains(rejection.path("required"), "message"),
                "Password domain rejection must describe error and message", errors);
        var codes = rejection.path("properties").path("error").path("enum");
        check(codes.size() == 5, "Password domain rejection must enumerate exactly five error codes", errors);
        for (String code : List.of("USER_NOT_FOUND", "CURRENT_PASSWORD_INCORRECT", "TOO_SHORT",
                "CONFIRMATION_MISMATCH", "SAME_AS_CURRENT")) {
            check(contains(codes, code), "Password rejection code missing: " + code, errors);
        }
    }

    private static void checkDatabaseSetupContract(JsonNode document, List<String> errors) {
        for (String suffix : List.of("", "/dismiss", "/preview", "/check")) {
            var operation = operation(document, DATABASE_SETUP + suffix, suffix.isEmpty() ? "get" : "post", errors);
            var statuses = new ArrayList<>(List.of("200", "401", "403"));
            if (!suffix.isEmpty()) statuses.add("503");
            boolean acceptsSettings = suffix.equals("/preview") || suffix.equals("/check");
            if (acceptsSettings) statuses.addAll(List.of("400", "413", "415"));
            if (suffix.equals("/preview")) statuses.add("409");
            for (String status : statuses) {
                check(operation.path("responses").has(status),
                        "Database setup " + suffix + " response missing: " + status, errors);
            }
            if (!acceptsSettings) continue;

            check(operation.path("requestBody").path("required").asBoolean(false),
                    "Database setup " + suffix + " JSON body must be required", errors);
            var request = resolve(document, operation.path("requestBody").path("content")
                    .path("application/json").path("schema"));
            for (String field : List.of("type", "host", "port", "database", "username")) {
                check(request.path("properties").has(field),
                        "Database setup request field missing: " + field, errors);
                check(contains(request.path("required"), field),
                        "Database setup required field missing: " + field, errors);
            }
            for (String field : List.of("password", "tls", "caCertificate")) {
                check(request.path("properties").has(field),
                        "Database setup optional field missing: " + field, errors);
                check(!contains(request.path("required"), field),
                        "Database setup field must remain optional: " + field, errors);
            }
            var password = request.path("properties").path("password");
            check("string".equals(password.path("type").asText())
                            && "password".equals(password.path("format").asText()),
                    "Database setup password must use the string/password format", errors);
            check(password.path("writeOnly").asBoolean(false),
                    "Database setup password must be write-only", errors);
            if (suffix.equals("/check")) {
                check(!operation.path("responses").has("409"),
                        "Database connection checks report schema overrides as findings, not a 409 conflict", errors);
            }
        }
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

    @org.springframework.context.annotation.Profile("openapi-contract-fixture")
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, SpringDocConfiguration.class, SpringDocWebMvcConfiguration.class
    })
    @org.springframework.boot.context.properties.EnableConfigurationProperties(org.springdoc.core.properties.SpringDocConfigProperties.class)
    @Import({OpenApiConfig.class, AccountApiController.class})
    static class ApiFixture {
        // Import the real controller so its profile/property conditions remain effective.
        @Bean PasswordChangeService passwordChangeService() {
            return new PasswordChangeService(null, null, null);
        }
        @Bean BackupJobController backupController() {
            return new BackupJobController(null, null);
        }
        @Bean DatabaseSetupController databaseSetupController() {
            return new DatabaseSetupController(null);
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
