package com.taxonomy.security.service;

import com.taxonomy.security.model.AppUser;
import com.taxonomy.security.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Domain service for validating and applying local-user password changes. */
@Service
public class PasswordChangeService {

    public static final int MINIMUM_PASSWORD_LENGTH = 12;

    public enum Result {
        CHANGED,
        USER_NOT_FOUND,
        CURRENT_PASSWORD_INCORRECT,
        TOO_SHORT,
        CONFIRMATION_MISMATCH,
        SAME_AS_CURRENT
    }

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordChangeService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public boolean isPasswordChangeRequired(String username) {
        return username != null && userRepository.findByUsername(username)
                .map(AppUser::isMustChangePassword)
                .orElse(false);
    }

    @Transactional
    public Result changePassword(String username,
                                 String currentPassword,
                                 String newPassword,
                                 String confirmPassword) {
        AppUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return Result.USER_NOT_FOUND;
        }
        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            return Result.CURRENT_PASSWORD_INCORRECT;
        }
        if (newPassword == null || newPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            return Result.TOO_SHORT;
        }
        if (!newPassword.equals(confirmPassword)) {
            return Result.CONFIRMATION_MISMATCH;
        }
        if (newPassword.equals(currentPassword)) {
            return Result.SAME_AS_CURRENT;
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        return Result.CHANGED;
    }
}
