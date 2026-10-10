package com.taxonomy.shared.features;

import java.lang.annotation.*;
import org.springframework.context.annotation.Conditional;

/** Explicit prerequisites for host composition bridges; evaluated without resolving feature classes. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(StartupFeatureCondition.class)
public @interface ConditionalOnFeature {
    String[] value();
    boolean present() default true;
}
