package com.taxonomy.security;

import com.taxonomy.security.controller.UserManagementController;
import com.taxonomy.security.service.UserManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** REST response and service-boundary contracts, independent of the HTML adapter. */
class UserManagementControllerTest {
    private static final String PASSWORD = "Rest-adapter-test-password-1139";
    private static final Map<String, Object> ACCOUNT = Map.of(
            "id", 7L, "username", "alice", "enabled", true, "roles", List.of("ROLE_USER"));
    private static final Map<String, Object> CREATE_REQUEST = Map.of(
            "username", "alice", "password", PASSWORD, "roles", List.of("ROLE_USER"));
    private static final Map<String, Object> UPDATE_REQUEST = Map.of(
            "displayName", "Alice Updated", "enabled", true, "roles", List.of("ROLE_ARCHITECT"));

    private final TestingAuthenticationToken administrator =
            new TestingAuthenticationToken("operator", "unused", "ROLE_ADMIN");
    private UserManagementService service;
    private UserManagementController controller;

    @BeforeEach
    void setUp() {
        service = mock(UserManagementService.class);
        controller = new UserManagementController(service);
    }

    @Test
    void listsTheSafeAccountProjectionFromTheService() {
        when(service.listUsers()).thenReturn(List.of(ACCOUNT));

        assertThat(controller.listUsers()).containsExactly(ACCOUNT);
        verify(service).listUsers();
        verifyNoMoreInteractions(service);
    }

    @Test
    void returnsAnExistingAccountWithoutAddingCredentials() {
        when(service.getUser(7L)).thenReturn(Optional.of(ACCOUNT));

        ResponseEntity<Map<String, Object>> response = controller.getUser(7L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(ACCOUNT)
                .doesNotContainKeys("password", "passwordHash");
        verify(service).getUser(7L);
    }

    @Test
    void missingAccountReadReturnsNotFoundWithoutABody() {
        when(service.getUser(99L)).thenReturn(Optional.empty());

        assertNotFound(controller.getUser(99L));
        verify(service).getUser(99L);
    }

    @Test
    void creationPreservesTheExistingRestRequestAndActorContract() {
        when(service.createUser(CREATE_REQUEST, "operator")).thenReturn(ACCOUNT);

        ResponseEntity<Object> response = controller.createUser(CREATE_REQUEST, administrator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(ACCOUNT);
        assertThat(response.getBody().toString()).doesNotContain(PASSWORD);
        verify(service).createUser(CREATE_REQUEST, "operator");
        verifyNoMoreInteractions(service);
    }

    @Test
    void duplicateUsernameReturnsConflictInsteadOfSuccess() {
        when(service.createUser(CREATE_REQUEST, "operator"))
                .thenThrow(new UserManagementService.ConflictException("Username already exists."));

        assertError(controller.createUser(CREATE_REQUEST, administrator),
                HttpStatus.CONFLICT, "Username already exists.");
    }

    @Test
    void invalidCreationReturnsTheServiceValidationError() {
        when(service.createUser(CREATE_REQUEST, "operator"))
                .thenThrow(new UserManagementService.ValidationException("Invalid role."));

        assertError(controller.createUser(CREATE_REQUEST, administrator),
                HttpStatus.BAD_REQUEST, "Invalid role.");
    }

    @Test
    void updateDelegatesTheRequestedProfileAndActor() {
        when(service.updateUser(7L, UPDATE_REQUEST, "operator")).thenReturn(ACCOUNT);

        ResponseEntity<Object> response = controller.updateUser(7L, UPDATE_REQUEST, administrator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(ACCOUNT);
        verify(service).updateUser(7L, UPDATE_REQUEST, "operator");
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingAccountUpdateReturnsNotFoundWithoutABody() {
        when(service.updateUser(7L, UPDATE_REQUEST, "operator"))
                .thenThrow(new UserManagementService.NotFoundException("Missing account."));

        assertNotFound(controller.updateUser(7L, UPDATE_REQUEST, administrator));
    }

    @Test
    void lastAdministratorDemotionIsReportedAsBadRequest() {
        when(service.updateUser(7L, UPDATE_REQUEST, "operator"))
                .thenThrow(new UserManagementService.ValidationException("Cannot remove the last admin role."));

        assertError(controller.updateUser(7L, UPDATE_REQUEST, administrator),
                HttpStatus.BAD_REQUEST, "Cannot remove the last admin role.");
    }

    @Test
    void passwordResetReturnsOnlyTheSuccessMessage() {
        ResponseEntity<Object> response = controller.changePassword(7L, Map.of("password", PASSWORD), administrator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("message", "Password changed successfully."));
        verify(service).changePassword(7L, PASSWORD, "operator");
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingAccountPasswordResetReturnsNotFoundWithoutABody() {
        doThrow(new UserManagementService.NotFoundException("Missing account."))
                .when(service).changePassword(7L, PASSWORD, "operator");

        assertNotFound(controller.changePassword(7L, Map.of("password", PASSWORD), administrator));
    }

    @Test
    void invalidPasswordReturnsValidationErrorWithoutEchoingTheRequest() {
        doThrow(new UserManagementService.ValidationException("Password is too short."))
                .when(service).changePassword(7L, PASSWORD, "operator");

        assertError(controller.changePassword(7L, Map.of("password", PASSWORD), administrator),
                HttpStatus.BAD_REQUEST, "Password is too short.");
    }

    @Test
    void omittedPasswordStillUsesTheServiceValidationPolicy() {
        doThrow(new UserManagementService.ValidationException("Password is required."))
                .when(service).changePassword(7L, null, "operator");

        assertError(controller.changePassword(7L, Map.of(), administrator),
                HttpStatus.BAD_REQUEST, "Password is required.");
        verify(service).changePassword(7L, null, "operator");
    }

    @Test
    void disablingAnAccountReturnsTheServiceUsernameAndAttributesTheActor() {
        when(service.disableUser(7L, "operator")).thenReturn("alice");

        ResponseEntity<Object> response = controller.disableUser(7L, administrator);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("message", "User 'alice' has been disabled."));
        verify(service).disableUser(7L, "operator");
        verifyNoMoreInteractions(service);
    }

    @Test
    void missingAccountDisableReturnsNotFoundWithoutABody() {
        when(service.disableUser(7L, "operator"))
                .thenThrow(new UserManagementService.NotFoundException("Missing account."));

        assertNotFound(controller.disableUser(7L, administrator));
    }

    @Test
    void lastAdministratorCannotBeDisabledThroughTheRestAdapter() {
        when(service.disableUser(7L, "operator"))
                .thenThrow(new UserManagementService.ValidationException("Cannot disable the last admin user."));

        assertError(controller.disableUser(7L, administrator),
                HttpStatus.BAD_REQUEST, "Cannot disable the last admin user.");
    }

    @Test
    void directInternalCallsRetainTheExistingSystemAuditAttribution() {
        // This exercises direct adapter calls, not anonymous access through Spring Security.
        when(service.createUser(CREATE_REQUEST, "system")).thenReturn(ACCOUNT);
        when(service.updateUser(7L, UPDATE_REQUEST, "system")).thenReturn(ACCOUNT);
        when(service.disableUser(7L, "system")).thenReturn("alice");

        assertThat(controller.createUser(CREATE_REQUEST, null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(controller.updateUser(7L, UPDATE_REQUEST, null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.changePassword(7L, Map.of("password", PASSWORD), null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(controller.disableUser(7L, null).getStatusCode()).isEqualTo(HttpStatus.OK);

        verify(service).createUser(CREATE_REQUEST, "system");
        verify(service).updateUser(7L, UPDATE_REQUEST, "system");
        verify(service).changePassword(7L, PASSWORD, "system");
        verify(service).disableUser(7L, "system");
        verifyNoMoreInteractions(service);
    }

    private static void assertNotFound(ResponseEntity<?> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    private static void assertError(ResponseEntity<?> response, HttpStatus status, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isEqualTo(Map.of("error", message));
        assertThat(response.getBody().toString()).doesNotContain(PASSWORD);
    }
}
