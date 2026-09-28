package com.taxonomy.security.controller;

import com.taxonomy.security.config.LocalUserManagementAccess;
import com.taxonomy.security.service.UserManagementService;
import com.taxonomy.security.service.PasswordChangeService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Opt-in, administrator-only HTML adapter over the existing local account service. */
@Controller
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@Profile(LocalUserManagementAccess.PROFILE_EXPRESSION)
@ConditionalOnProperty(name = LocalUserManagementAccess.PROPERTY,
        havingValue = "true", matchIfMissing = true)
public class UserManagementPageController {
    private static final int MIN_PASSWORD_LENGTH = PasswordChangeService.MINIMUM_PASSWORD_LENGTH;
    private static final int MAX_PASSWORD_BYTES = 72;
    private final UserManagementService users;
    private final boolean temporaryPasswords;

    public UserManagementPageController(UserManagementService users,
            @Value("${taxonomy.security.require-password-change:false}") boolean temporaryPasswords) {
        this.users = users;
        this.temporaryPasswords = temporaryPasswords;
    }

    @ModelAttribute
    public void pageContext(Model model, HttpServletResponse response, Authentication authentication) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("availableRoles", List.of("ROLE_USER", "ROLE_ARCHITECT", "ROLE_ADMIN"));
        model.addAttribute("minimumPasswordLength", MIN_PASSWORD_LENGTH);
        model.addAttribute("temporaryPasswords", temporaryPasswords);
        model.addAttribute("currentUsername", authentication == null ? "" : authentication.getName());
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("users", users.listUsers().stream()
                .sorted(Comparator.comparing(user -> String.valueOf(user.get("username")),
                        String.CASE_INSENSITIVE_ORDER)).toList());
        return "user-management";
    }

    @GetMapping("/new")
    public String newUser(Model model) {
        model.addAttribute("form", UserManagementForm.empty());
        model.addAttribute("creating", true);
        return "user-management-form";
    }

    @GetMapping("/{id}")
    public String edit(@PathVariable Long id, Model model) {
        return editPage(id, model);
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") UserManagementForm form, BindingResult errors,
            @RequestParam(defaultValue = "") String newPassword,
            @RequestParam(defaultValue = "") String confirmPassword,
            Authentication authentication, Model model, HttpServletResponse response,
            RedirectAttributes redirect, Locale locale) {
        model.addAttribute("creating", true);
        String passwordError = passwordError(newPassword, confirmPassword);
        if (errors.hasErrors() || passwordError != null) {
            return failure(model, response, HttpStatus.BAD_REQUEST,
                    passwordError == null ? "users.error.profile" : passwordError, "user-management-form");
        }
        Map<String, Object> body = form.profile();
        body.put("username", form.username());
        body.put("password", newPassword);
        try {
            users.createUser(body, authentication.getName());
            return redirect(redirect, locale, "users.created", "/admin/users");
        } catch (UserManagementService.ConflictException exception) {
            return failure(model, response, HttpStatus.CONFLICT, "users.error.duplicate", "user-management-form");
        } catch (UserManagementService.ValidationException exception) {
            return failure(model, response, HttpStatus.BAD_REQUEST, "users.error.profile", "user-management-form");
        }
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
            @Valid @ModelAttribute("form") UserManagementForm form, BindingResult errors,
            Authentication authentication, Model model, HttpServletResponse response,
            RedirectAttributes redirect, Locale locale) {
        model.addAttribute("account", account(id));
        model.addAttribute("creating", false);
        if (errors.hasErrors()) {
            return failure(model, response, HttpStatus.BAD_REQUEST, "users.error.profile", "user-management-form");
        }
        try {
            // Username and enabled are deliberately not accepted by this form.
            users.updateUser(id, form.profile(), authentication.getName());
            return redirect(redirect, locale, "users.saved", "/admin/users/" + id);
        } catch (UserManagementService.NotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (UserManagementService.ValidationException exception) {
            return failure(model, response, HttpStatus.BAD_REQUEST, "users.error.lastAdmin", "user-management-form");
        }
    }

    @PostMapping("/{id}/password")
    public String resetPassword(@PathVariable Long id,
            @RequestParam(defaultValue = "") String newPassword,
            @RequestParam(defaultValue = "") String confirmPassword,
            Authentication authentication, Model model, HttpServletResponse response,
            RedirectAttributes redirect, Locale locale) {
        String passwordError = passwordError(newPassword, confirmPassword);
        if (passwordError != null) {
            editPage(id, model);
            return failure(model, response, HttpStatus.BAD_REQUEST, passwordError, "user-management-form");
        }
        try {
            users.changePassword(id, newPassword, authentication.getName());
            return redirect(redirect, locale, "users.passwordChanged", "/admin/users/" + id);
        } catch (UserManagementService.NotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (UserManagementService.ValidationException exception) {
            editPage(id, model);
            return failure(model, response, HttpStatus.BAD_REQUEST, "users.error.passwordLength", "user-management-form");
        }
    }

    @GetMapping("/{id}/status")
    public String confirmStatus(@PathVariable Long id, @RequestParam boolean enabled, Model model) {
        model.addAttribute("account", account(id));
        model.addAttribute("enableAccount", enabled);
        return "user-management-status";
    }

    @PostMapping("/{id}/status")
    public String changeStatus(@PathVariable Long id, @RequestParam boolean enabled,
            Authentication authentication, Model model, HttpServletResponse response,
            RedirectAttributes redirect, Locale locale) {
        try {
            if (enabled) {
                users.updateUser(id, Map.of("enabled", true), authentication.getName());
            } else {
                users.disableUser(id, authentication.getName());
            }
            return redirect(redirect, locale, enabled ? "users.enabled" : "users.disabled", "/admin/users");
        } catch (UserManagementService.NotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        } catch (UserManagementService.ValidationException exception) {
            confirmStatus(id, enabled, model);
            return failure(model, response, HttpStatus.BAD_REQUEST, "users.error.lastAdmin", "user-management-status");
        }
    }

    private Map<String, Object> account(Long id) {
        return users.getUser(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private String editPage(Long id, Model model) {
        Map<String, Object> account = account(id);
        model.addAttribute("account", account);
        model.addAttribute("form", UserManagementForm.from(account));
        model.addAttribute("creating", false);
        return "user-management-form";
    }

    private static String passwordError(String password, String confirmation) {
        if (!password.equals(confirmation)) {
            return "users.error.passwordMismatch";
        }
        if (password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return "users.error.passwordLength";
        }
        return null;
    }

    private static String failure(Model model, HttpServletResponse response, HttpStatus status,
            String errorKey, String view) {
        response.setStatus(status.value());
        model.addAttribute("errorKey", errorKey);
        return view;
    }

    private static String redirect(RedirectAttributes redirect, Locale locale, String successKey, String path) {
        redirect.addFlashAttribute("successKey", successKey);
        redirect.addAttribute("lang", locale.getLanguage());
        return "redirect:" + path;
    }
}
