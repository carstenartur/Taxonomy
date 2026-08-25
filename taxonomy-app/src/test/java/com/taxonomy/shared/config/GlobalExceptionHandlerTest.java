package com.taxonomy.shared.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private static final String SAFE_INTERNAL_MESSAGE =
            "The request could not be completed safely.";

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void frameworkServerErrorDoesNotExposeInternalExceptionMessage() {
        ExposedHandler handler = handler(SAFE_INTERNAL_MESSAGE);
        RuntimeException failure = new RuntimeException(
                "jdbc:postgresql://db.internal/taxonomy?password=private-value");

        ResponseEntity<Object> response = handler.frameworkException(
                failure,
                HttpStatus.INTERNAL_SERVER_ERROR,
                request("/api/failing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("status", 500)
                .containsEntry("message", SAFE_INTERNAL_MESSAGE)
                .containsEntry("path", "/api/failing");
        assertThat(body.toString())
                .doesNotContain("db.internal")
                .doesNotContain("private-value");
    }

    @Test
    void blankLocalizedServerMessageFallsBackToSafeDefault() {
        ExposedHandler handler = handler(" ");

        ResponseEntity<Object> response = handler.frameworkException(
                new RuntimeException("private provider response"),
                HttpStatus.BAD_GATEWAY,
                request("/api/provider"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("message"))
                .isEqualTo(
                        "An internal error occurred. Please try again or check the server logs.");
        assertThat(body.toString()).doesNotContain("private provider response");
    }

    @Test
    void frameworkClientErrorKeepsActionableValidationMessage() {
        ExposedHandler handler = handler(SAFE_INTERNAL_MESSAGE);

        ResponseEntity<Object> response = handler.frameworkException(
                new IllegalArgumentException("required parameter is missing"),
                HttpStatus.BAD_REQUEST,
                request("/api/input"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("status", 400)
                .containsEntry("message", "required parameter is missing")
                .containsEntry("path", "/api/input");
    }

    @Test
    void directBadRequestUsesReasonPhraseWhenExceptionMessageIsBlank() {
        GlobalExceptionHandler handler = handler(SAFE_INTERNAL_MESSAGE);

        ResponseEntity<Map<String, Object>> response = handler.handleBadRequest(
                new IllegalArgumentException(" "),
                request("/api/input"));

        assertThat(response.getBody())
                .containsEntry("message", HttpStatus.BAD_REQUEST.getReasonPhrase());
    }

    private static ExposedHandler handler(String internalMessage) {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.internal", Locale.ENGLISH, internalMessage);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        return new ExposedHandler(messages);
    }

    private static WebRequest request(String path) {
        return new ServletWebRequest(new MockHttpServletRequest("GET", path));
    }

    private static final class ExposedHandler extends GlobalExceptionHandler {
        private ExposedHandler(StaticMessageSource messages) {
            super(messages);
        }

        private ResponseEntity<Object> frameworkException(
                Exception exception,
                HttpStatus status,
                WebRequest request) {
            return handleExceptionInternal(
                    exception,
                    null,
                    HttpHeaders.EMPTY,
                    status,
                    request);
        }
    }
}
