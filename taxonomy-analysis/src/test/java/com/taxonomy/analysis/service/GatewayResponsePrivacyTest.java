package com.taxonomy.analysis.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GatewayResponsePrivacyTest {
    @Test void gatewaySuccessLogsContainNoRequirementOrAnswerContent() {
        var rest=mock(RestTemplate.class);var json=new ObjectMapper();var parser=new LlmResponseParser(json);
        var config=mock(LlmProviderConfig.class);when(config.getGeminiUrl()).thenReturn("https://provider.test/?key=");
        String body="{\"sensitive\":\"UNTRUSTED_PRIVATE_ANSWER_9384\"}";
        when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(String.class))).thenReturn(ResponseEntity.ok(body));
        for(LlmGateway gateway:new LlmGateway[]{new OpenAiCompatibleGateway(LlmProvider.OPENAI,"https://provider.test/","model",0,rest,json,parser,null,null,null),new GeminiGateway(config,rest,json,parser,null,null,null)}) {
            var logger=(Logger)LoggerFactory.getLogger(gateway.getClass());var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
            try {assertThat(gateway.sendHttpRequest("PRIVATE_REQUIREMENT","key")).isEqualTo(body);
                assertThat(appender.list).allSatisfy(event->assertThat(event.getFormattedMessage()).doesNotContain("UNTRUSTED_PRIVATE_ANSWER_9384","PRIVATE_REQUIREMENT"));
            } finally {logger.detachAppender(appender);appender.stop();}
        }
    }
    @Test void errorBodiesHttpErrorsAndUnexpectedExceptionsDoNotLeakContent() {
        for(String mode:new String[]{"gemini-error","gemini-quota","gemini-http","openai-http","gemini-unexpected","openai-unexpected"}) {
            var rest=mock(RestTemplate.class);var json=new ObjectMapper();var parser=new LlmResponseParser(json);
            var config=mock(LlmProviderConfig.class);when(config.getGeminiUrl()).thenReturn("https://provider.test/?key=");
            String marker="PRIVATE_SOURCE_AND_ANSWER_7719";
            if(mode.endsWith("http")) when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(String.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(HttpStatus.BAD_REQUEST,"Bad request",HttpHeaders.EMPTY,marker.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8));
            else if(mode.endsWith("unexpected")) when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(String.class)))
                .thenThrow(new IllegalStateException(marker));
            else when(rest.exchange(anyString(),eq(HttpMethod.POST),any(HttpEntity.class),eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"error\":\""+marker+(mode.endsWith("quota")?" RESOURCE_EXHAUSTED":"")+"\"}"));
            LlmGateway gateway=mode.startsWith("gemini")?new GeminiGateway(config,rest,json,parser,null,null,null)
                :new OpenAiCompatibleGateway(LlmProvider.OPENAI,"https://provider.test/","model",0,rest,json,parser,null,null,null);
            var logger=(Logger)LoggerFactory.getLogger(gateway.getClass());var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
            try {
                try {assertThat(gateway.sendHttpRequest(marker,"key")).isNull();}
                catch(LlmRateLimitException | LlmProviderException failure) {assertThat(failure.getMessage()).doesNotContain(marker);}
                assertThat(appender.list).allSatisfy(event->{assertThat(event.getFormattedMessage()).doesNotContain(marker);assertThat(event.getThrowableProxy()).isNull();});
            } finally {logger.detachAppender(appender);appender.stop();}
        }
    }

}
