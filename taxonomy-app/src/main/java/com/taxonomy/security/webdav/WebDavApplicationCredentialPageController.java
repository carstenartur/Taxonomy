package com.taxonomy.security.webdav;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;

/** Server-rendered management page; newly created secrets are shown exactly once via flash state. */
@Controller
public final class WebDavApplicationCredentialPageController {

    private final WebDavApplicationCredentialService credentials;

    public WebDavApplicationCredentialPageController(
            WebDavApplicationCredentialService credentials) {
        this.credentials = credentials;
    }

    @GetMapping("/admin/webdav-credentials")
    public String page(Authentication authentication, Model model) {
        model.addAttribute("credentials", credentials.list(authentication));
        model.addAttribute("now", Instant.now());
        return "webdav-credentials";
    }

    @PostMapping("/admin/webdav-credentials")
    public String create(
            @RequestParam(required = false) String description,
            @RequestParam(defaultValue = "true") boolean readAllowed,
            @RequestParam(defaultValue = "false") boolean writeAllowed,
            @RequestParam(defaultValue = "30") Integer lifetimeDays,
            Authentication authentication,
            RedirectAttributes redirect) {
        WebDavApplicationCredentialService.CreatedCredential created =
                credentials.create(authentication, description,
                        readAllowed, writeAllowed, lifetimeDays);
        redirect.addFlashAttribute("createdSecret", created.secret());
        redirect.addFlashAttribute("createdCredential", created.credential());
        return "redirect:/admin/webdav-credentials";
    }

    @PostMapping("/admin/webdav-credentials/revoke")
    public String revoke(
            @RequestParam String credentialId,
            Authentication authentication,
            RedirectAttributes redirect) {
        credentials.revoke(authentication, credentialId);
        redirect.addFlashAttribute("revokedCredential", credentialId);
        return "redirect:/admin/webdav-credentials";
    }
}
