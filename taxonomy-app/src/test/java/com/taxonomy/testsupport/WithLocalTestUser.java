package com.taxonomy.testsupport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Runs an integration test with a persisted, verified local principal. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@WithSecurityContext(factory = WithLocalTestUser.Factory.class)
public @interface WithLocalTestUser {
    String username() default "user";

    String[] roles() default {"USER"};

    final class Factory implements WithSecurityContextFactory<WithLocalTestUser> {
        private final ApplicationContext application;

        @Autowired
        Factory(ApplicationContext application) {
            this.application = application;
        }

        @Override
        public SecurityContext createSecurityContext(WithLocalTestUser user) {
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(LocalTestLogin.authentication(application, user.username(), user.roles()));
            return context;
        }
    }
}
