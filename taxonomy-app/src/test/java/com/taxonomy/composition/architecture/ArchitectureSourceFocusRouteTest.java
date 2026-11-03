package com.taxonomy.composition.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureSourceFocusRouteTest {
    @Test void readOnlySourceInvestigationHasItsOwnRouteOutsideTheEditorRevisionApi() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        var paths = new java.util.ArrayList<String>();
        for (var bean : scanner.findCandidateComponents("com.taxonomy.composition.architecture")) {
            for (var method : Class.forName(bean.getBeanClassName()).getDeclaredMethods()) {
                var mapping = method.getAnnotation(PostMapping.class);
                if (mapping != null) paths.addAll(Arrays.asList(mapping.value()));
            }
        }
        assertThat(paths).contains("/api/architecture/focus/suggestions");
    }
}
