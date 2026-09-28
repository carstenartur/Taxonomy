package com.taxonomy.shared.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Reproduces the nested servlet-output failure from scenario acceptance logs. */
class GlobalExceptionHandlerDisconnectTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new StaticMessageSource());
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void captureOnlyThisHandlersDiagnostics() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.DEBUG);
        logger.setAdditive(false);
    }

    @AfterEach
    void restoreLogging() {
        logger.detachAppender(events);
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
        events.stop();
    }

    @Test
    void nestedJsonWriteDisconnectDoesNotCreateAnotherResponse() throws Exception {
        var response = handler.handleException(nestedDisconnect(), request());

        assertThat(response).isNull();
        assertNoWarningOrError();
    }

    @Test
    void genericBrokenPipeDoesNotBecomeAnInternalServerError() {
        var response = handler.handleGenericException(new IOException("Broken pipe"), request());

        assertThat(response).isNull();
        assertNoWarningOrError();
    }

    @Test
    void genericConnectionResetDoesNotBecomeAnInternalServerError() {
        var response = handler.handleGenericException(new IOException("Connection reset by peer"), request());

        assertThat(response).isNull();
        assertNoWarningOrError();
    }

    @Test
    void directTomcatAbortDoesNotDependOnTheLocalizedMessage() {
        var response = handler.handleGenericException(new ClientAbortException(new IOException("closed")), request());

        assertThat(response).isNull();
        assertNoWarningOrError();
    }

    @Test
    void frameworkAsyncUnusableResponseRemainsHandledWithoutWriting() throws Exception {
        var response = handler.handleException(new AsyncRequestNotUsableException("already closed"), request());

        assertThat(response).isNull();
        assertNoWarningOrError();
    }

    @Test
    void disconnectProducesOneDebugLineWithoutAStackTrace() throws Exception {
        handler.handleException(nestedDisconnect(), request());

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void traceLoggingStillProvidesTheDisconnectStack() throws Exception {
        logger.setLevel(Level.TRACE);
        handler.handleException(nestedDisconnect(), request());

        assertThat(events.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.TRACE);
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void committedFrameworkResponseIsNotReplacedButRealFailureIsLogged() throws Exception {
        var servletResponse = new MockHttpServletResponse();
        servletResponse.getWriter().write("already sent");
        servletResponse.flushBuffer();
        var request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/taxonomy"), servletResponse);
        var failure = new HttpMessageNotWritableException("private serialization bug", new IllegalStateException("bad getter"));

        assertThat(handler.handleException(failure, request)).isNull();
        assertThat(servletResponse.getContentAsString()).isEqualTo("already sent");
        assertOneRealError();
    }

    @Test
    void committedGenericResponseIsNotReplacedButRealFailureIsLogged() throws Exception {
        var servletResponse = new MockHttpServletResponse();
        servletResponse.flushBuffer();
        var request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/taxonomy"), servletResponse);

        assertThat(handler.handleGenericException(new IllegalStateException("real bug"), request)).isNull();
        assertOneRealError();
    }

    @Test
    void realJsonSerializationFailureStillReturnsSanitized500() throws Exception {
        var failure = new HttpMessageNotWritableException("private serialization bug", new IllegalStateException("bad getter"));
        var response = handler.handleException(failure, request());

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().toString()).contains("An internal error occurred")
                .doesNotContain("private serialization bug", "bad getter");
        assertOneRealError();
    }

    @Test
    void outboundProviderBrokenPipeIsNotMistakenForABrowserDisconnect() {
        var failure = new ResourceAccessException("provider connection failed", new IOException("Broken pipe"));
        var response = handler.handleGenericException(failure, request());

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertOneRealError();
    }

    @Test
    void databaseBrokenPipeIsNotMistakenForABrowserDisconnect() {
        var failure = new DataAccessResourceFailureException("database connection failed", new IOException("Broken pipe"));
        var response = handler.handleGenericException(failure, request());

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertOneRealError();
    }

    @Test
    void ordinaryIoFailureStillReturns500() {
        var response = handler.handleGenericException(new IOException("disk failure"), request());

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertOneRealError();
    }

    @Test
    void methodNotAllowedPreservesTheFrameworkAllowHeaderAndErrorContract() throws Exception {
        var response = handler.handleException(
                new HttpRequestMethodNotSupportedException("DELETE", List.of("GET")), request());

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
        assertThat(response.getBody().toString()).contains("status=405", "path=/api/taxonomy");
    }

    @Test
    void realMvcExceptionDispatchDoesNotTryToRenderADisconnectErrorBody() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DisconnectedController())
                .setControllerAdvice(handler).build();

        var result = mvc.perform(get("/disconnected-output")).andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(HttpMessageNotWritableException.class);
        assertThat(result.getResponse().getContentAsString()).isEmpty();
        assertNoWarningOrError();
    }

    private void assertNoWarningOrError() {
        assertThat(events.list).noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN));
    }

    private void assertOneRealError() {
        assertThat(events.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList())
                .singleElement().satisfies(event -> assertThat(event.getThrowableProxy()).isNotNull());
    }

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest("GET", "/api/taxonomy"), new MockHttpServletResponse());
    }

    private static HttpMessageNotWritableException nestedDisconnect() {
        return new HttpMessageNotWritableException("Could not write JSON",
                new IllegalStateException("serialization interrupted",
                        new AsyncRequestNotUsableException("ServletOutputStream failed to write",
                                new ClientAbortException(new IOException("Broken pipe")))));
    }

    @RestController
    static class DisconnectedController {
        @GetMapping("/disconnected-output")
        String disconnectedOutput() {
            throw nestedDisconnect();
        }
    }
}
