package com.taxonomy.testsupport;

import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.engine.FilterResult;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.PostDiscoveryFilter;

/** Removes inherited methods and templates before they become executable test cases. */
public final class IgnoreInheritedTestsFilter implements PostDiscoveryFilter {

    @Override
    public FilterResult apply(TestDescriptor descriptor) {
        if (!(descriptor.getSource().orElse(null) instanceof MethodSource method)) {
            return FilterResult.included("Descriptor is not a test method");
        }
        Class<?> testClass = getTestClass(descriptor);
        if (testClass == null || !AnnotationSupport.isAnnotated(testClass, IgnoreInheritedTests.class)) {
            return FilterResult.included("Test class has not opted out of inherited tests");
        }
        return FilterResult.includedIf(method.getJavaMethod().getDeclaringClass().equals(testClass),
                () -> "Test method is declared by the selected class",
                () -> "Inherited test method is owned by its declaring class");
    }

    private static Class<?> getTestClass(TestDescriptor descriptor) {
        if (descriptor.getSource().orElse(null) instanceof ClassSource source) {
            return source.getJavaClass();
        }
        return descriptor.getParent().map(IgnoreInheritedTestsFilter::getTestClass).orElse(null);
    }
}
