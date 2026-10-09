package com.taxonomy.shared.features;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

final class StartupFeatureCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attributes = metadata.getAnnotationAttributes(ConditionalOnFeature.class.getName());
        Object captured = context.getBeanFactory() == null ? null : context.getBeanFactory().getSingleton("featureSet");
        var features = captured instanceof FeatureAssembly.FeatureSet installed
                ? installed : FeatureAssembly.discover(context.getClassLoader());
        boolean present = (boolean) attributes.get("present");
        for (String id : (String[]) attributes.get("value")) if (features.has(id) != present) return false;
        return true;
    }
}
