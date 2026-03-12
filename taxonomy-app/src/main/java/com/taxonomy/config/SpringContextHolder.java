package com.taxonomy.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Static holder for the Spring {@link ApplicationContext}.
 * Allows non-Spring-managed code (such as Hibernate Search bridges) to access
 * Spring beans by type without requiring constructor injection.
 */
@Component
public class SpringContextHolder implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(@NonNull ApplicationContext ctx) {
        context = ctx;
    }

    public static <T> T getBean(Class<T> beanType) {
        if (context == null) return null;
        try {
            return context.getBean(beanType);
        } catch (Exception e) {
            return null;
        }
    }
}
