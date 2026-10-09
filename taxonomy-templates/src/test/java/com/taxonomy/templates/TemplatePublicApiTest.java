package com.taxonomy.templates;

import com.taxonomy.templates.api.TemplateManifest;
import com.taxonomy.templates.api.TemplateFile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies the actual published operation types, including nested record components. */
class TemplatePublicApiTest {

    @Test
    void downloadValueIsImmutableAndRetainsExactEtag() {
        String revision = "0123456789abcdef0123456789abcdef01234567";
        var manifest = new TemplateManifest(1, "report", "Report", "report.dotx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
                "2026-10-09T00:00:00Z", "editor", 3, 1, "package-sha");
        byte[] source = {1, 2, 3};
        var file = new TemplateFile(manifest, revision, source, Instant.EPOCH);
        source[0] = 9;
        byte[] received = file.content();
        received[1] = 8;
        assertArrayEquals(new byte[] {1, 2, 3}, file.content());
        assertEquals('"' + revision + '"', file.etag());
        assertEquals(manifest, file.manifest());
    }

    @Test
    void publicTemplateOperationsDoNotExposeRepositoryMembers() {
        Set<Type> checked = new HashSet<>();
        for (var method : DocumentTemplateService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) continue;
            checkType(method.getGenericReturnType(), checked);
            for (Type parameter : method.getGenericParameterTypes()) checkType(parameter, checked);
            for (Type exception : method.getGenericExceptionTypes()) checkType(exception, checked);
        }
    }

    @Test
    void serviceImplementsAnIndependentPublicBoundary() {
        Class<?> boundary = assertDoesNotThrow(
                () -> Class.forName("com.taxonomy.templates.api.DocumentTemplates"),
                "The public template boundary is missing");
        assertTrue(boundary.isInterface(), "The public boundary must be an interface");
        assertTrue(boundary.isAssignableFrom(DocumentTemplateService.class));
        assertTrue(boundary.getMethods().length >= 14, "All existing operations must remain available");
        for (var method : boundary.getDeclaredMethods()) {
            checkType(method.getGenericReturnType(), new HashSet<>());
        }
    }

    private static void checkType(Type type, Set<Type> checked) {
        if (!checked.add(type)) return;
        if (type instanceof ParameterizedType parameterized) {
            checkType(parameterized.getRawType(), checked);
            for (Type argument : parameterized.getActualTypeArguments()) checkType(argument, checked);
        } else if (type instanceof GenericArrayType array) {
            checkType(array.getGenericComponentType(), checked);
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) checkType(bound, checked);
            for (Type bound : wildcard.getLowerBounds()) checkType(bound, checked);
        } else if (type instanceof Class<?> concrete) {
            assertFalse(concrete.getName().startsWith(DocumentTemplateGitRepository.class.getName()),
                    () -> "Public template API exposes repository-owned type: " + concrete.getName());
            if (concrete.isArray()) checkType(concrete.getComponentType(), checked);
            if (concrete.isRecord()) {
                for (var component : concrete.getRecordComponents()) {
                    checkType(component.getGenericType(), checked);
                }
            }
        }
    }
}
