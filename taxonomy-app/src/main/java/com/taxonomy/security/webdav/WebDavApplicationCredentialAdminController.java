package com.taxonomy.security.webdav;

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
@RequestMapping("/api/admin/webdav-credentials")
public final class WebDavApplicationCredentialAdminController {

    private final WebDavApplicationCredentialService credentials;

    public WebDavApplicationCredentialAdminController(
            WebDavApplicationCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping
    public List<WebDavApplicationCredentialService.CredentialMetadata> list(
            Authentication authentication) {
        return credentials.list(authentication);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
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
    public void revoke(
            @PathVariable String credentialId,
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
