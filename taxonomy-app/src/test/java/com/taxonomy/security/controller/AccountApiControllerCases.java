package com.taxonomy.security.controller;

import com.taxonomy.security.service.PasswordChangeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Controller contract cases shared by JUnit and a direct Java diagnostic entry
 * point. Password validation/persistence remains covered by PasswordChangeServiceTest.
 */
final class AccountApiControllerCases {

    private static final String OWNER = "account-contract-user";
    private static final String CURRENT = "current-password-fixture";
    private static final String REPLACEMENT = "replacement-password-fixture";
    private static final Map<String, String> REQUEST = Map.of(
            "username", "must-not-select-this-account",
            "currentPassword", CURRENT,
            "newPassword", REPLACEMENT,
            "confirmPassword", REPLACEMENT);

    private AccountApiControllerCases() { }

    static Map<String, Runnable> cases() {
        Map<String, Runnable> cases = new LinkedHashMap<>();
        cases.put("unauthenticated request is rejected without invoking the service",
                AccountApiControllerCases::unauthenticated);
        cases.put("successful change uses only the authenticated account",
                AccountApiControllerCases::successfulChange);
        cases.put("missing account produces the stable 400 response",
                () -> rejected(PasswordChangeService.Result.USER_NOT_FOUND, "User not found"));
        cases.put("incorrect current password produces the stable 400 response",
                () -> rejected(PasswordChangeService.Result.CURRENT_PASSWORD_INCORRECT,
                        "Current password is incorrect"));
        cases.put("short new password produces the stable 400 response",
                () -> rejected(PasswordChangeService.Result.TOO_SHORT,
                        "New password must be at least 12 characters"));
        cases.put("mismatched confirmation produces the stable 400 response",
                () -> rejected(PasswordChangeService.Result.CONFIRMATION_MISMATCH,
                        "New passwords do not match"));
        cases.put("unchanged password produces the stable 400 response",
                () -> rejected(PasswordChangeService.Result.SAME_AS_CURRENT,
                        "New password must differ from the current password"));
        cases.put("confirmation is forwarded independently of the replacement password",
                AccountApiControllerCases::distinctConfirmation);
        cases.put("missing fields reach domain validation without a controller failure",
                AccountApiControllerCases::missingFields);
        return cases;
    }

    private static void unauthenticated() {
        var service = new RecordingPasswordChangeService(PasswordChangeService.Result.CHANGED);
        var response = new AccountApiController(service).changePassword(null, REQUEST);

        response(response, 401, Map.of("error", "AUTHENTICATION_REQUIRED"));
        equal(0, service.calls, "Unauthenticated requests must not invoke password changes");
        equal(null, service.invocation, "No account must be selected before authentication");
    }

    private static void successfulChange() {
        var service = new RecordingPasswordChangeService(PasswordChangeService.Result.CHANGED);
        var response = new AccountApiController(service).changePassword(authentication(), REQUEST);

        response(response, 200, Map.of(
                "status", "PASSWORD_CHANGED",
                "message", "Password changed successfully"));
        delegatedOnce(service, CURRENT, REPLACEMENT, REPLACEMENT);
    }

    private static void rejected(PasswordChangeService.Result result, String message) {
        var service = new RecordingPasswordChangeService(result);
        var response = new AccountApiController(service).changePassword(authentication(), REQUEST);

        // Exact maps also prevent leaking submitted credentials or returning a success field.
        response(response, 400, Map.of("error", result.name(), "message", message));
        delegatedOnce(service, CURRENT, REPLACEMENT, REPLACEMENT);
    }

    private static void distinctConfirmation() {
        var service = new RecordingPasswordChangeService(
                PasswordChangeService.Result.CONFIRMATION_MISMATCH);
        Map<String, String> request = Map.of(
                "currentPassword", CURRENT,
                "newPassword", REPLACEMENT,
                "confirmPassword", "different-confirmation-fixture");
        var response = new AccountApiController(service).changePassword(authentication(), request);

        response(response, 400, Map.of(
                "error", "CONFIRMATION_MISMATCH",
                "message", "New passwords do not match"));
        delegatedOnce(service, CURRENT, REPLACEMENT, "different-confirmation-fixture");
    }

    private static void missingFields() {
        var service = new RecordingPasswordChangeService(
                PasswordChangeService.Result.CURRENT_PASSWORD_INCORRECT);
        var response = new AccountApiController(service).changePassword(authentication(), Map.of());

        response(response, 400, Map.of(
                "error", "CURRENT_PASSWORD_INCORRECT",
                "message", "Current password is incorrect"));
        delegatedOnce(service, null, null, null);
    }

    private static TestingAuthenticationToken authentication() {
        return new TestingAuthenticationToken(OWNER, null, "ROLE_USER");
    }

    private static void response(ResponseEntity<Map<String, String>> actual,
                                 int status, Map<String, String> body) {
        equal(status, actual.getStatusCode().value(), "HTTP status");
        equal(body, actual.getBody(), "Response must contain exactly the documented fields");
    }

    private static void delegatedOnce(RecordingPasswordChangeService service,
                                      String current, String replacement, String confirmation) {
        equal(1, service.calls, "Exactly one service call");
        equal(new Invocation(OWNER, current, replacement, confirmation), service.invocation,
                "Use the authenticated owner and forward each request field unchanged");
    }

    private static void equal(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message);
        }
    }

    private record Invocation(String username, String currentPassword,
                              String newPassword, String confirmPassword) { }

    /** Only the service boundary is doubled; the production controller is executed. */
    private static final class RecordingPasswordChangeService extends PasswordChangeService {
        private final Result result;
        private int calls;
        private Invocation invocation;

        RecordingPasswordChangeService(Result result) {
            super(null, null, null);
            this.result = result;
        }

        @Override
        public Result changePassword(String username, String currentPassword,
                                     String newPassword, String confirmPassword) {
            calls++;
            invocation = new Invocation(username, currentPassword, newPassword, confirmPassword);
            return result;
        }
    }

    public static void main(String[] args) {
        var cases = cases();
        cases.forEach((name, test) -> {
            test.run();
            System.out.println("PASS: " + name);
        });
        System.out.println("Passed " + cases.size() + " account API contract cases");
    }
}
