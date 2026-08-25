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

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void frameworkLevelServerErrorDoesNotExposeInternalExceptionMessage() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("error.internal", Locale.ENGLISH, "Safe internal error.");
        GlobalExceptionHandler handler = new GlobalExceptionHandler(messages);
        LocaleContextHolder.setLocale(Locale.ENGLISH);

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("jdbc:postgresql://admin:secret@database/internal"),
                null,
                HttpHeaders.EMPTY,
                HttpStatus.INTERNAL_SERVER_ERROR,
                request("/api/internal"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("message")).isEqualTo("Safe internal error.");
        assertThat(body.toString()).doesNotContain("secret", "database/internal");
    }

    @Test
    void frameworkLevelClientErrorRetainsActionableMessage() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalArgumentException("Required parameter 'projectId' is missing"),
                null,
                HttpHeaders.EMPTY,
                HttpStatus.BAD_REQUEST,
                request("/api/projects"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertThat(body.get("message"))
                .isEqualTo("Required parameter 'projectId' is missing");
    }

    private static ServletWebRequest request(String uri) {
        return new ServletWebRequest(new MockHttpServletRequest("GET", uri));
    }
}
