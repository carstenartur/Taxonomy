package com.taxonomy.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.taxonomy.analysis.session.AnalysisDraftConflictException;
import com.taxonomy.analysis.session.AnalysisDraftValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.util.WebUtils;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Real handler calls with synthetic private values, captured at the logging boundary. */
class GlobalExceptionHandlerDiagnosticPrivacyTest {

    private static final String PRIVATE_MESSAGE = "PRIVATE_EXCEPTION_QUERY_secret-budget.xlsx";
    private static final String PRIVATE_CAUSE = "PRIVATE_CAUSE_provider-reply";
    private static final String PRIVATE_PATH = "/api/workspaces/PRIVATE_WORKSPACE_ID/draft";
    private static final String PRIVATE_METHOD = "PRIVATE_METHOD_secret-budget.xlsx";
    private static final String LOCALIZED_INTERNAL = "Localized safe internal error.";
    private static final String LOCALIZED_FORBIDDEN = "Localized access denied.";

    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Logger pageNotFoundLogger = (Logger) LoggerFactory.getLogger("org.springframework.web.servlet.PageNotFound");
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private StaticMessageSource messages;
    private ExposedHandler handler;
    private Level previousLevel;
    private boolean previousAdditive;
    private Level previousPageNotFoundLevel;
    private boolean previousPageNotFoundAdditive;

    @BeforeEach
    void captureHandlerDiagnostics() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        previousPageNotFoundLevel = pageNotFoundLogger.getLevel();
        previousPageNotFoundAdditive = pageNotFoundLogger.isAdditive();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        pageNotFoundLogger.addAppender(events);
        pageNotFoundLogger.setLevel(Level.TRACE);
        pageNotFoundLogger.setAdditive(false);
        messages = new StaticMessageSource();
        messages.addMessage("error.internal", Locale.GERMAN, LOCALIZED_INTERNAL);
        messages.addMessage("error.forbidden", Locale.GERMAN, LOCALIZED_FORBIDDEN);
        LocaleContextHolder.setLocale(Locale.GERMAN);
        handler = new ExposedHandler(messages);
    }

    @AfterEach
    void restoreLoggingAndLocale() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
        pageNotFoundLogger.detachAppender(events);
        pageNotFoundLogger.setLevel(previousPageNotFoundLevel);
        pageNotFoundLogger.setAdditive(previousPageNotFoundAdditive);
        events.stop();
        LocaleContextHolder.resetLocaleContext();
    }

    @ParameterizedTest
    @EnumSource(FailureKind.class)
    void diagnosticsExcludePrivateValuesWhilePreservingTheResponse(FailureKind kind) {
        var response = invoke(kind, request(new MockHttpServletResponse()));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(kind.status);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getBody();
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path")
                .containsEntry("status", kind.status.value())
                .containsEntry("error", kind.status.getReasonPhrase())
                .containsEntry("path", PRIVATE_PATH)
                .containsEntry("message", kind.message);
        assertThat(Instant.parse((String) body.get("timestamp"))).isNotNull();
        if (kind == FailureKind.FRAMEWORK_CLIENT || kind == FailureKind.FRAMEWORK_SERVER) {
            assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
        } else {
            assertThat(response.getHeaders().isEmpty()).isTrue();
        }
        assertSafeDiagnostic(kind.status.is5xxServerError() ? Level.ERROR : Level.WARN,
                Integer.toString(kind.status.value()));
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = {"GENERIC", "FRAMEWORK_SERVER"})
    void committedResponsesRemainUntouchedAndFailuresStillHaveSafeDiagnostics(FailureKind kind)
            throws Exception {
        var servletResponse = new MockHttpServletResponse();
        servletResponse.getWriter().write("already sent");
        servletResponse.flushBuffer();

        assertThat(invoke(kind, request(servletResponse))).isNull();
        assertThat(servletResponse.getContentAsString()).isEqualTo("already sent");
        assertSafeDiagnostic(Level.ERROR, "500");
    }

    @Test
    void actualFrameworkHandlerDoesNotLeakASecondaryCommittedResponseWarning() throws Exception {
        var actualHandler = new GlobalExceptionHandler(messages);
        var servletResponse = new MockHttpServletResponse();
        servletResponse.getWriter().write("already sent");
        servletResponse.flushBuffer();
        var failure = new HttpMessageNotWritableException(PRIVATE_MESSAGE,
                new IllegalStateException(PRIVATE_CAUSE));

        assertThat(actualHandler.handleException(failure, request(servletResponse))).isNull();
        assertThat(servletResponse.getContentAsString()).isEqualTo("already sent");
        assertThat(events.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage())
                    .doesNotContain(PRIVATE_MESSAGE, PRIVATE_CAUSE, PRIVATE_PATH, "PRIVATE_WORKSPACE_ID");
            assertThat(Arrays.toString(event.getArgumentArray()))
                    .doesNotContain(PRIVATE_MESSAGE, PRIVATE_CAUSE, PRIVATE_PATH, "PRIVATE_WORKSPACE_ID");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertSafeDiagnostic(Level.ERROR, "500");
    }

    @Test
    void uncommittedFrameworkServerFailureRetainsTheExistingServletErrorAttribute() throws Exception {
        var actualHandler = new GlobalExceptionHandler(messages);
        var servletResponse = new MockHttpServletResponse();
        var request = request(servletResponse);
        var previousFailure = new IllegalStateException("previous servlet error");
        request.getRequest().setAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE, previousFailure);
        var failure = new HttpMessageNotWritableException(PRIVATE_MESSAGE,
                new IllegalStateException(PRIVATE_CAUSE));

        var response = actualHandler.handleException(failure, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("status", 500)
                .containsEntry("message", LOCALIZED_INTERNAL)
                .containsEntry("path", PRIVATE_PATH);
        assertThat(request.getRequest().getAttribute(WebUtils.ERROR_EXCEPTION_ATTRIBUTE)).isSameAs(previousFailure);
        assertSafeDiagnostic(Level.ERROR, "500");
    }

    @Test
    void unsupportedMethodPreservesThe405ContractWithoutLoggingThePrivateMethodToken() throws Exception {
        var actualHandler = new GlobalExceptionHandler(messages);
        var failure = new HttpRequestMethodNotSupportedException(PRIVATE_METHOD, List.of("GET", "POST"));

        var response = actualHandler.handleException(failure, request(new MockHttpServletResponse()));

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.POST);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getBody();
        assertThat(body).containsOnlyKeys("timestamp", "status", "error", "message", "path")
                .containsEntry("status", 405)
                .containsEntry("error", "Method Not Allowed")
                .containsEntry("path", PRIVATE_PATH)
                .containsEntry("message", "Request method 'PRIVATE_METHOD_secret-budget.xlsx' is not supported");
        assertThat(events.list).isNotEmpty().allSatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .doesNotContain(PRIVATE_METHOD, PRIVATE_PATH, "PRIVATE_WORKSPACE_ID");
            assertThat(Arrays.toString(event.getArgumentArray()))
                    .doesNotContain(PRIVATE_METHOD, PRIVATE_PATH, "PRIVATE_WORKSPACE_ID");
            assertThat(event.getThrowableProxy()).isNull();
        });
        assertSafeDiagnostic(Level.WARN, "405");
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind.class, names = {"GENERIC", "FRAMEWORK_SERVER"})
    void disconnectDiagnosticsAtTraceDoNotExposeTheirExceptionPayloads(FailureKind kind) {
        var disconnect = new IllegalStateException(PRIVATE_MESSAGE,
                new AsyncRequestNotUsableException(PRIVATE_CAUSE,
                        new IOException("Broken pipe " + PRIVATE_CAUSE)));
        var request = request(new MockHttpServletResponse());
        var response = kind == FailureKind.GENERIC
                ? handler.handleGenericException(disconnect, request)
                : handler.frameworkException(disconnect, HttpStatus.INTERNAL_SERVER_ERROR, request);

        assertThat(response).isNull();
        assertSafeDiagnostic(Level.TRACE, null);
    }

    private ResponseEntity<?> invoke(FailureKind kind, WebRequest request) {
        var cause = new IllegalStateException(PRIVATE_CAUSE);
        return switch (kind) {
            case BAD_REQUEST -> handler.handleBadRequest(
                    new IllegalArgumentException(PRIVATE_MESSAGE, cause), request);
            case DRAFT_VALIDATION -> handler.handleAnalysisDraftValidation(
                    new AnalysisDraftValidationException(PRIVATE_MESSAGE, cause), request);
            case DRAFT_CONFLICT -> handler.handleAnalysisDraftConflict(
                    new AnalysisDraftConflictException(PRIVATE_MESSAGE, cause), request);
            case ACCESS_DENIED -> handler.handleAccessDenied(
                    new AccessDeniedException(PRIVATE_MESSAGE, cause), request);
            case GENERIC -> handler.handleGenericException(
                    new IllegalStateException(PRIVATE_MESSAGE, cause), request);
            case FRAMEWORK_CLIENT, FRAMEWORK_SERVER -> handler.frameworkException(
                    new IllegalStateException(PRIVATE_MESSAGE, cause), kind.status, request);
        };
    }

    private void assertSafeDiagnostic(Level level, String status) {
        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(level);
            assertThat(event.getFormattedMessage()).isNotBlank()
                    .doesNotContain(PRIVATE_MESSAGE, PRIVATE_CAUSE, PRIVATE_PATH, "PRIVATE_WORKSPACE_ID");
            assertThat(Arrays.toString(event.getArgumentArray()))
                    .doesNotContain(PRIVATE_MESSAGE, PRIVATE_CAUSE, PRIVATE_PATH, "PRIVATE_WORKSPACE_ID");
            assertThat(event.getThrowableProxy()).isNull();
            if (status != null) {
                assertThat(event.getFormattedMessage()).contains(status);
            }
        });
    }

    private static ServletWebRequest request(MockHttpServletResponse response) {
        return new ServletWebRequest(new MockHttpServletRequest("GET", PRIVATE_PATH), response);
    }

    enum FailureKind {
        BAD_REQUEST(HttpStatus.BAD_REQUEST, PRIVATE_MESSAGE),
        DRAFT_VALIDATION(HttpStatus.BAD_REQUEST, PRIVATE_MESSAGE),
        DRAFT_CONFLICT(HttpStatus.CONFLICT, PRIVATE_MESSAGE),
        ACCESS_DENIED(HttpStatus.FORBIDDEN, LOCALIZED_FORBIDDEN),
        GENERIC(HttpStatus.INTERNAL_SERVER_ERROR, LOCALIZED_INTERNAL),
        FRAMEWORK_CLIENT(HttpStatus.BAD_REQUEST, PRIVATE_MESSAGE),
        FRAMEWORK_SERVER(HttpStatus.INTERNAL_SERVER_ERROR, LOCALIZED_INTERNAL);

        private final HttpStatus status;
        private final String message;

        FailureKind(HttpStatus status, String message) {
            this.status = status;
            this.message = message;
        }
    }

    private static final class ExposedHandler extends GlobalExceptionHandler {
        ExposedHandler(StaticMessageSource messages) {
            super(messages);
        }

        ResponseEntity<Object> frameworkException(Exception exception, HttpStatus status, WebRequest request) {
            var headers = new HttpHeaders();
            headers.setAllow(java.util.Set.of(HttpMethod.GET));
            return handleExceptionInternal(exception, null, headers, status, request);
        }
    }
}
