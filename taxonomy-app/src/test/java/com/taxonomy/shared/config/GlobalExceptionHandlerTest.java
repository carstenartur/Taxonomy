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
    private static final String DEFAULT_INTERNAL_MESSAGE =
            "An internal error occurred. Please try again or check the server logs.";

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void frameworkServerErrorDoesNotExposeInternalExceptionMessage() {
        ExposedHandler handler = handler(SAFE_INTERNAL_MESSAGE);
        RuntimeException failure = new RuntimeException(
                "private-template-path contained "
                        + "jdbc:postgresql://internal-user:secret@database/taxonomy");

        ResponseEntity<Object> response = handler.frameworkException(
                failure,
                HttpStatus.INTERNAL_SERVER_ERROR,
                request("/api/failing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        Map<String, Object> body = responseBody(response);
        assertThat(body)
                .containsEntry("status", 500)
                .containsEntry("message", SAFE_INTERNAL_MESSAGE)
                .containsEntry("path", "/api/failing");
        assertThat(body.toString())
                .doesNotContain("private-template-path")
                .doesNotContain("jdbc:postgresql")
                .doesNotContain("internal-user")
                .doesNotContain("secret");
    }

    @Test
    void genericServerErrorUsesTheSameSanitizedMessage() {
        GlobalExceptionHandler handler = handler(SAFE_INTERNAL_MESSAGE);

        ResponseEntity<Map<String, Object>> response = handler.handleGenericException(
                new RuntimeException("provider response contained private-value"),
                request("/api/provider"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody())
                .containsEntry("message", SAFE_INTERNAL_MESSAGE)
                .containsEntry("path", "/api/provider");
        assertThat(response.getBody().toString())
                .doesNotContain("provider response")
                .doesNotContain("private-value");
    }

    @Test
    void blankLocalizedServerMessageFallsBackToSafeDefault() {
        ExposedHandler handler = handler(" ");

        ResponseEntity<Object> response = handler.frameworkException(
                new RuntimeException("private provider response"),
                HttpStatus.BAD_GATEWAY,
                request("/api/provider"));

        Map<String, Object> body = responseBody(response);
        assertThat(body)
                .containsEntry("status", 502)
                .containsEntry("message", DEFAULT_INTERNAL_MESSAGE);
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
        assertThat(responseBody(response))
                .containsEntry("status", 400)
                .containsEntry("message", "required parameter is missing")
                .containsEntry("path", "/api/input");
    }

    @Test
    void blankClientErrorMessageFallsBackToReasonPhrase() {
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

    private static Map<String, Object> responseBody(ResponseEntity<Object> response) {
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        return body;
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
