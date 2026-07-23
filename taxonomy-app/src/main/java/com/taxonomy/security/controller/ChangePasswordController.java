package com.taxonomy.security.controller;

import com.taxonomy.security.service.PasswordChangeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Form-login controller for local-user password changes. */
@Controller
@ConditionalOnProperty(name = "taxonomy.security.change-password-enabled",
        havingValue = "true", matchIfMissing = true)
public class ChangePasswordController {

    private static final Logger log = LoggerFactory.getLogger(ChangePasswordController.class);

    private final PasswordChangeService passwordChangeService;

    public ChangePasswordController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }

    @GetMapping("/change-password")
    public String showChangePasswordForm() {
        return "change-password";
    }

    @PostMapping("/change-password")
    public String changePassword(Authentication authentication,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword,
                                 @RequestParam String confirmPassword,
                                 Model model) {
        if (authentication == null) {
            return "redirect:/login";
        }

        String username = authentication.getName();
        PasswordChangeService.Result result = passwordChangeService.changePassword(
                username, currentPassword, newPassword, confirmPassword);

        switch (result) {
            case CHANGED -> {
                log.info("PASSWORD_CHANGED user={}", username);
                return "redirect:/?passwordChanged=true";
            }
            case USER_NOT_FOUND -> model.addAttribute("error", "User not found.");
            case CURRENT_PASSWORD_INCORRECT ->
                    model.addAttribute("error", "Current password is incorrect.");
            case TOO_SHORT -> model.addAttribute("error",
                    "New password must be at least "
                            + PasswordChangeService.MINIMUM_PASSWORD_LENGTH + " characters.");
            case CONFIRMATION_MISMATCH ->
                    model.addAttribute("error", "New passwords do not match.");
            case SAME_AS_CURRENT -> model.addAttribute("error",
                    "New password must be different from the current password.");
        }
        return "change-password";
    }
}