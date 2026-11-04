package com.taxonomy.setup;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;

/** Shared security requires ADMIN on every route and CSRF protection for session-authenticated POSTs. */
@RestController
@Tag(name = "Administration")
@RequestMapping("/api/admin/database-setup")
public class DatabaseSetupController {
    static final int MAX_REQUEST_BYTES = 16 * 1024;
    private static final JsonMapper INPUT = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxDocumentLength(MAX_REQUEST_BYTES)
                            .maxNestingDepth(3).maxStringLength(4096).maxNameLength(64).maxNumberLength(10).maxTokenCount(128).build())
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).build();
    private final DatabaseSetupService service;
    private final PostgresProvisioningService provisioning;

    public DatabaseSetupController(DatabaseSetupService service, PostgresProvisioningService provisioning) {
        this.service = service;
        this.provisioning = provisioning;
    }

    @GetMapping
    @Operation(summary = "Read database setup guidance status",
            description = "ADMIN only. Reports the current database type, whether it is in memory, whether the setup prompt "
                    + "is still required and whether deployment is managed. Does not probe the database or expose credentials.")
    @ApiResponse(responseCode = "200", description = "Current setup guidance state",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DatabaseSetupService.State.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role required", content = @Content)
    public ResponseEntity<DatabaseSetupService.State> status() { return noStore(service.status()); }

    @PostMapping("/dismiss")
    @Operation(summary = "Dismiss the optional database setup prompt",
            description = "ADMIN only; session-authenticated requests require a CSRF token. Persists the installation's "
                    + "dismissed prompt preference with the authenticated actor. Does not change database or deployment settings.")
    @ApiResponse(responseCode = "200", description = "The dismissed preference was saved",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = "dismissed", additionalPropertiesSchema = Boolean.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role and valid CSRF protection required", content = @Content)
    @ApiResponse(responseCode = "503", description = "The preference could not be saved; code DATABASE_SETUP_UNAVAILABLE",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    public ResponseEntity<Map<String, Boolean>> dismiss(Authentication actor) throws IOException {
        service.dismiss(actor.getName());
        return noStore(Map.of("dismissed", true));
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Prepare database deployment configuration",
            description = "ADMIN only; session-authenticated requests require a CSRF token. Builds password-free Spring and "
                    + "Helm configuration for manual deployment and restart. Schema-generation conflicts block export. "
                    + "Does not connect to the target, change the running datasource or transfer existing records.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Strict JSON up to 16 KiB. Select postgres, mssql or oracle with a plain host, port 1–65535, "
                    + "database/service name and username. Password is optional and omitted from the preview. TLS defaults "
                    + "to verified TLS; caCertificate is an optional mounted absolute PostgreSQL CA path.",
            content = @Content(mediaType = "application/json", schema = @Schema(
                    implementation = DatabaseSetupService.ConnectionSettings.class)))
    @ApiResponse(responseCode = "200", description = "Password-free configuration and deployment findings",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DatabaseSetupService.Preview.class)))
    @ApiResponse(responseCode = "400", description = "Invalid connection settings or JSON; code DATABASE_SETUP_INVALID_REQUEST",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role and valid CSRF protection required", content = @Content)
    @ApiResponse(responseCode = "409", description = "Schema overrides block export; code DATABASE_SETUP_SCHEMA_CONFLICT",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    @ApiResponse(responseCode = "413", description = "Request exceeds 16 KiB; code DATABASE_SETUP_REQUEST_TOO_LARGE",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    @ApiResponse(responseCode = "415", description = "Content-Type must be application/json", content = @Content)
    @ApiResponse(responseCode = "503", description = "The request could not be read; code DATABASE_SETUP_UNAVAILABLE",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    public ResponseEntity<DatabaseSetupService.Preview> preview(HttpServletRequest request) throws IOException {
        return noStore(service.preview(read(request, DatabaseSetupService.ConnectionSettings.class)));
    }

    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Check an independent database connection",
            description = "ADMIN only; session-authenticated requests require a CSRF token. Uses the supplied credentials "
                    + "for a bounded independent JDBC connection with a read-only hint. Connection failures and schema "
                    + "overrides are returned as findings with HTTP 200. Does not test schema readiness, execute DDL, copy "
                    + "records, persist the password or replace the running datasource.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Strict JSON up to 16 KiB. Select postgres, mssql or oracle with a plain host, port 1–65535, "
                    + "database/service name and username. Password is optional and used only by this connection check. "
                    + "TLS defaults to verified TLS; caCertificate is an optional mounted absolute PostgreSQL CA path.",
            content = @Content(mediaType = "application/json", schema = @Schema(
                    implementation = DatabaseSetupService.ConnectionSettings.class)))
    @ApiResponse(responseCode = "200", description = "Connection and deployment findings; target login failures remain findings",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = DatabaseSetupService.CheckResult.class)))
    @ApiResponse(responseCode = "400", description = "Invalid connection settings or JSON; code DATABASE_SETUP_INVALID_REQUEST",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator role and valid CSRF protection required", content = @Content)
    @ApiResponse(responseCode = "413", description = "Request exceeds 16 KiB; code DATABASE_SETUP_REQUEST_TOO_LARGE",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    @ApiResponse(responseCode = "415", description = "Content-Type must be application/json", content = @Content)
    @ApiResponse(responseCode = "503", description = "The request could not be read; code DATABASE_SETUP_UNAVAILABLE",
            content = @Content(mediaType = "application/json", schema = @Schema(type = "object",
                    requiredProperties = {"code", "message"}, additionalPropertiesSchema = String.class)))
    public ResponseEntity<DatabaseSetupService.CheckResult> check(HttpServletRequest request) throws IOException {
        return noStore(service.check(read(request, DatabaseSetupService.ConnectionSettings.class)));
    }

    @PostMapping(value = "/postgres/inspect", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Inspect a PostgreSQL database creation plan",
            description = "ADMIN only; session-authenticated requests require CSRF. Checks the existing server, "
                    + "administrator capabilities, target names and any existing application login without creating resources. "
                    + "READY describes a plan; it does not mean resources were created. Domain failures remain HTTP 200 findings.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Strict JSON up to 16 KiB. PostgreSQL target settings including the application password, "
                    + "maintenanceDatabase and separate administrator credentials. Credentials are used only for this operation.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PostgresProvisioningService.ProvisionSettings.class)))
    @ApiResponse(responseCode = "200", description = "Inspected plan or safe diagnostic; no resources were created",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PostgresProvisioningService.ProvisionResult.class)))
    @ApiResponse(responseCode = "400", description = "Invalid settings or JSON; DATABASE_SETUP_INVALID_REQUEST", content = @Content)
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator and valid CSRF protection required", content = @Content)
    @ApiResponse(responseCode = "413", description = "Request exceeds 16 KiB", content = @Content)
    @ApiResponse(responseCode = "415", description = "Content-Type must be application/json", content = @Content)
    @ApiResponse(responseCode = "503", description = "The request could not be read", content = @Content)
    public ResponseEntity<PostgresProvisioningService.ProvisionResult> inspectPostgres(HttpServletRequest request) throws IOException {
        return noStore(provisioning.inspect(read(request, PostgresProvisioningService.ProvisionSettings.class)));
    }

    @PostMapping(value = "/postgres/provision", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Prepare a PostgreSQL database and restricted application account",
            description = "ADMIN only; session-authenticated requests require CSRF and confirmed=true. Reinspects names and "
                    + "capabilities, creates missing resources or verifies compatible existing resources, and checks the "
                    + "application login. Existing resources are never altered or dropped. CREATE DATABASE is not atomic "
                    + "with role creation: inspect PARTIAL or UNKNOWN outcomes before retrying. Does not switch the active datasource.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            description = "Strict JSON up to 16 KiB. Reviewed PostgreSQL target settings and separate administrator credentials, "
                    + "plus confirmed=true and expectedRole/expectedDatabase set to the inspected CREATE or REUSE actions. "
                    + "Changed actions require a new inspection. Both passwords are transient and never returned or exported.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PostgresProvisioningService.ProvisionSettings.class)))
    @ApiResponse(responseCode = "200", description = "Creation/reuse state and safe diagnostic; COMPLETE includes verified application login",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = PostgresProvisioningService.ProvisionResult.class)))
    @ApiResponse(responseCode = "400", description = "Invalid settings or JSON; DATABASE_SETUP_INVALID_REQUEST", content = @Content)
    @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content)
    @ApiResponse(responseCode = "403", description = "Administrator and valid CSRF protection required", content = @Content)
    @ApiResponse(responseCode = "413", description = "Request exceeds 16 KiB", content = @Content)
    @ApiResponse(responseCode = "415", description = "Content-Type must be application/json", content = @Content)
    @ApiResponse(responseCode = "503", description = "The request could not be read", content = @Content)
    public ResponseEntity<PostgresProvisioningService.ProvisionResult> provisionPostgres(HttpServletRequest request) throws IOException {
        return noStore(provisioning.provision(read(request, PostgresProvisioningService.ProvisionSettings.class)));
    }

    private static <T> T read(HttpServletRequest request, Class<T> type) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_BYTES) { throw new RequestTooLarge(); }
        byte[] bytes = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) { throw new RequestTooLarge(); }
        try {
            var value = INPUT.readTree(bytes);
            if (value == null || !value.isObject()) { throw new IllegalArgumentException(); }
            return INPUT.treeToValue(value, type);
        } catch (RuntimeException invalid) {
            // Parser failures may include source values. Neither retain nor log their exceptions.
            throw new IllegalArgumentException("Invalid database setup request.");
        }
    }

    @ExceptionHandler(DatabaseSetupService.SchemaConflictException.class)
    public ResponseEntity<Map<String, String>> schemaConflict() {
        return error(409, "DATABASE_SETUP_SCHEMA_CONFLICT", DatabaseSetupService.SCHEMA_CONFLICT_MESSAGE);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid() {
        return error(400, "DATABASE_SETUP_INVALID_REQUEST", "Use a supported database type and valid bounded connection settings.");
    }

    @ExceptionHandler(RequestTooLarge.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        return error(413, "DATABASE_SETUP_REQUEST_TOO_LARGE", "Database setup requests must not exceed 16 KiB.");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unavailable() {
        return error(503, "DATABASE_SETUP_UNAVAILABLE", "The setup preference could not be saved or the request could not be read. Try again.");
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
    private static ResponseEntity<Map<String, String>> error(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code", code, "message", message));
    }
    private static final class RequestTooLarge extends RuntimeException { }
}
