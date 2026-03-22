package com.taxonomy.security.controller;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Allows authenticated users to change their password.
 * <p>
 * When {@code taxonomy.security.require-password-change=true} and the user still
 * has the default password, Spring Security redirects all GUI requests here.
 * <p>
 * Only active when local user management is enabled (without Keycloak).
 * In the Keycloak profile, password changes are handled by the identity provider.
 */
@Controller
@ConditionalOnProperty(name = "taxonomy.security.change-password-enabled",
        havingValue = "true", matchIfMissing = true)
public class ChangePasswordController {

    private static final Logger log = LoggerFactory.getLogger(ChangePasswordController.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ChangePasswordController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/change-password")
    public String showChangePasswordForm(Model model) {
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
        Optional<AppUser> optionalUser = userRepository.findByUsername(username);
        if (optionalUser.isEmpty()) {
            model.addAttribute("error", "User not found.");
            return "change-password";
        }

        AppUser user = optionalUser.get();

        // Validate current password
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            model.addAttribute("error", "Current password is incorrect.");
            return "change-password";
        }

        // Validate new password
        if (newPassword == null || newPassword.length() < 8) {
            model.addAttribute("error", "New password must be at least 8 characters.");
            return "change-password";
        }

        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New passwords do not match.");
            return "change-password";
        }

        if (newPassword.equals(currentPassword)) {
            model.addAttribute("error", "New password must be different from the current password.");
            return "change-password";
        }

        // Update password
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        log.info("PASSWORD_CHANGED user={}", username);

        model.addAttribute("success", "Password changed successfully.");
        return "change-password";
    }
}
