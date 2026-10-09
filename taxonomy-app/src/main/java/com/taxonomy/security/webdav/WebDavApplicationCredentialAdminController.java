package com.taxonomy.security.webdav;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Administrative JSON API for revocable WebDAV-only application credentials. */
@RestController
@Tag(name = "WebDAV application credentials")
@RequestMapping("/api/admin/webdav-credentials")
public final class WebDavApplicationCredentialAdminController {

    private final WebDavApplicationCredentialService credentials;

    public WebDavApplicationCredentialAdminController(
            WebDavApplicationCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    @Operation(summary = "List WebDAV application credential metadata",
            description = "Returns credential metadata visible to the authenticated administrator. Does not return existing credential secrets or grant access to ordinary REST endpoints.")
    @ApiResponse(responseCode = "200", description = "Operation completed")
    public List<WebDavApplicationCredentialService.CredentialMetadata> list(
            Authentication authentication) {
        return credentials.list(authentication);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue a scoped WebDAV application credential",
            description = "Creates a revocable, expiring WebDAV-only credential with explicit read/write permissions. The newly issued secret is returned once and must not be logged. This credential does not replace interactive login or authorize general API calls.")
    @ApiResponse(responseCode = "201", description = "Operation completed")
    public WebDavApplicationCredentialService.CreatedCredential create(
            @RequestBody CreateCredentialRequest request,
            Authentication authentication) {
        CreateCredentialRequest safe = request == null
                ? new CreateCredentialRequest(null, true, false, null) : request;
        return credentials.create(
                authentication,
                safe.description(),
                safe.readAllowed(),
                safe.writeAllowed(),
                safe.lifetimeDays());
    }

    @DeleteMapping("/{credentialId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a WebDAV application credential",
            description = "Revokes the selected credential under administrator authorization. Future WebDAV authentication with that credential is rejected; the underlying templates and their revision history are not deleted.")
    @ApiResponse(responseCode = "204", description = "Operation completed")
    public void revoke(
            @Parameter(description = "Existing revocable WebDAV credential identifier") @PathVariable String credentialId,
            Authentication authentication) {
        credentials.revoke(authentication, credentialId);
    }

    public record CreateCredentialRequest(
            String description,
            boolean readAllowed,
            boolean writeAllowed,
            Integer lifetimeDays) {
    }
}
