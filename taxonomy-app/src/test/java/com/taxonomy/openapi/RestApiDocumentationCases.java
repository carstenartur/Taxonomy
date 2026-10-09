package com.taxonomy.openapi;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.HttpEntity;
import java.util.ArrayList;
import java.util.Comparator;

/** Includes conditional controllers without starting their services or hiding old debt in an allowlist. */
public final class RestApiDocumentationCases {
    private RestApiDocumentationCases() { }

    public static void verify() throws Exception {
        var scan = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.core.type.classreading.MetadataReader reader) {
                // This is a source/annotation inventory, not an active-profile bean inventory.
                // Do not let @ConditionalOnProperty hide Artemis or local-account APIs.
                var metadata = reader.getAnnotationMetadata();
                return (metadata.hasAnnotation(Controller.class.getName())
                        || metadata.hasMetaAnnotation(Controller.class.getName()))
                        && !reader.getResource().toString().contains("/test-classes/");
            }
        };
        scan.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        var failures = new ArrayList<String>();
        int methods = 0;
        for (var candidate : scan.findCandidateComponents("com.taxonomy").stream()
                .sorted(Comparator.comparing(b -> b.getBeanClassName())).toList()) {
            Class<?> type = Class.forName(candidate.getBeanClassName(), false,
                    RestApiDocumentationCases.class.getClassLoader());
            if (AnnotatedElementUtils.hasAnnotation(type, Hidden.class)) continue;
            for (var method : type.getMethods()) {
                if (AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null
                        || AnnotatedElementUtils.hasAnnotation(method, Hidden.class)) continue;
                if (!AnnotatedElementUtils.hasAnnotation(type, ResponseBody.class)
                        && !AnnotatedElementUtils.hasAnnotation(method, ResponseBody.class)
                        && !HttpEntity.class.isAssignableFrom(method.getReturnType())
                        && !(method.getReturnType() == void.class
                            && java.util.Arrays.stream(method.getParameterTypes()).anyMatch(
                                jakarta.servlet.ServletResponse.class::isAssignableFrom))) continue;
                Operation operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation.class);
                if (operation != null && operation.hidden()) continue;
                methods++;
                String id = type.getName() + "#" + method.getName();
                if (operation == null) failures.add(id + ": missing @Operation");
                else {
                    if (operation.summary().isBlank()) failures.add(id + ": missing summary");
                    if (operation.description().isBlank()) failures.add(id + ": missing description");
                }
            }
        }
        if (methods == 0) throw new AssertionError("No REST methods discovered; inventory is not evidence");
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " documentation failures in "
                + methods + " REST methods:\n" + String.join("\n", failures));
        System.out.println("REST documentation inventory passed: " + methods + " methods");
    }
    public static void main(String[] args) throws Exception { verify(); }
}
