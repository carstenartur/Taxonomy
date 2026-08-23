package com.taxonomy.security.webdav;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebDavApplicationCredentialControllersTest {

    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void jsonControllerDelegatesDefaultsExplicitCreationAndRevocation() {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        Authentication authentication = authentication();
        var metadata = metadata();
        var created = new WebDavApplicationCredentialService.CreatedCredential(
                metadata, "taxdav_one-time-secret");
        when(service.list(authentication)).thenReturn(List.of(metadata));
        when(service.create(authentication, null, true, false, null))
                .thenReturn(created);
        when(service.create(authentication, "Laptop", false, true, 7))
                .thenReturn(created);
        WebDavApplicationCredentialAdminController controller =
                new WebDavApplicationCredentialAdminController(service);

        assertThat(controller.list(authentication)).containsExactly(metadata);
        assertThat(controller.create(null, authentication)).isSameAs(created);
        assertThat(controller.create(
                new WebDavApplicationCredentialAdminController.CreateCredentialRequest(
                        "Laptop", false, true, 7),
                authentication)).isSameAs(created);

        controller.revoke("a".repeat(24), authentication);
        verify(service).revoke(authentication, "a".repeat(24));
    }

    @Test
    void pageControllerPopulatesThePageAndKeepsSecretsInOneTimeFlashState() {
        WebDavApplicationCredentialService service =
                mock(WebDavApplicationCredentialService.class);
        Authentication authentication = authentication();
        Model model = mock(Model.class);
        RedirectAttributes redirect = mock(RedirectAttributes.class);
        var metadata = metadata();
        var created = new WebDavApplicationCredentialService.CreatedCredential(
                metadata, "taxdav_one-time-secret");
        when(service.list(authentication)).thenReturn(List.of(metadata));
        when(service.create(authentication, "Word", true, false, 30))
                .thenReturn(created);
        WebDavApplicationCredentialPageController controller =
                new WebDavApplicationCredentialPageController(service);

        assertThat(controller.page(authentication, model))
                .isEqualTo("webdav-credentials");
        verify(model).addAttribute("credentials", List.of(metadata));
        verify(model).addAttribute(eq("now"), any(Instant.class));

        assertThat(controller.create(
                "Word", true, false, 30, authentication, redirect))
                .isEqualTo("redirect:/admin/webdav-credentials");
        verify(redirect).addFlashAttribute(
                "createdSecret", "taxdav_one-time-secret");
        verify(redirect).addFlashAttribute("createdCredential", metadata);

        assertThat(controller.revoke(
                metadata.id(), authentication, redirect))
                .isEqualTo("redirect:/admin/webdav-credentials");
        verify(service).revoke(authentication, metadata.id());
        verify(redirect).addFlashAttribute("revokedCredential", metadata.id());
    }

    private static Authentication authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "admin", "n/a", List.of());
    }

    private static WebDavApplicationCredentialService.CredentialMetadata metadata() {
        return new WebDavApplicationCredentialService.CredentialMetadata(
                "a".repeat(24),
                "Word",
                List.of("template:read"),
                NOW.minusSeconds(60),
                NOW.plusSeconds(3600),
                null,
                null);
    }
}
