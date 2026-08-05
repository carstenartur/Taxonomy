package com.taxonomy.portfolio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Supplies the Jackson 2 mapper used by the report renderer.
 *
 * <p>Spring Boot 4 auto-configures the application HTTP stack with Jackson 3,
 * while Hibernate Search keeps the fixed Jackson 2 family on the runtime
 * classpath. {@code PortfolioReportService} deliberately uses the latter for
 * its self-contained downloadable JSON representation. Declaring the mapper
 * explicitly prevents that internal renderer from depending on Boot's HTTP
 * mapper generation.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PortfolioReportJacksonConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper portfolioReportObjectMapper() {
        SimpleModule timeValues = new SimpleModule("portfolio-report-time-values");
        timeValues.addSerializer(Instant.class, ToStringSerializer.instance);
        timeValues.addSerializer(LocalDate.class, ToStringSerializer.instance);
        timeValues.addSerializer(LocalDateTime.class, ToStringSerializer.instance);
        timeValues.addSerializer(OffsetDateTime.class, ToStringSerializer.instance);
        timeValues.addSerializer(ZonedDateTime.class, ToStringSerializer.instance);
        return new ObjectMapper().findAndRegisterModules().registerModule(timeValues);
    }
}
