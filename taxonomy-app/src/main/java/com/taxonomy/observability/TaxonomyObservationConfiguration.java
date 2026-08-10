package com.taxonomy.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.ClassUtils;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Adds low-cardinality Micrometer observations to Taxonomy-owned service boundaries.
 *
 * <p>The OpenTelemetry Java agent remains responsible for automatic technical spans
 * and selected method spans. These observations intentionally provide the existing
 * Micrometer/Prometheus path with stable domain timings and outcomes. No invocation
 * argument, return value, user, workspace, repository, query, prompt, response or
 * document content is inspected or attached to an observation.</p>
 */
@Configuration(proxyBeanMethods = false)
public class TaxonomyObservationConfiguration {

    @Bean
    static BeanPostProcessor taxonomyObservationBeanPostProcessor(
            ObjectProvider<ObservationRegistry> registryProvider) {
        // BeanPostProcessors are created before many ordinary infrastructure beans.
        // Resolve the registry only when an observed operation is invoked so an early
        // NOOP fallback cannot become permanent for the lifetime of the application.
        Supplier<ObservationRegistry> registrySupplier = () -> registryProvider.getIfAvailable(
                () -> ObservationRegistry.NOOP);
        return new TaxonomyObservationBeanPostProcessor(registrySupplier);
    }

    record TargetDescriptor(String observationName, String component,
                            Set<String> methods) {
        TargetDescriptor {
            methods = Set.copyOf(methods);
        }
    }

    static final class TaxonomyObservationBeanPostProcessor
            implements BeanPostProcessor, Ordered {

        private static final Map<String, TargetDescriptor> TARGETS = Map.ofEntries(
                Map.entry("com.taxonomy.workspace.service.WorkspaceContextResolver",
                        new TargetDescriptor("taxonomy.workspace.resolve", "workspace",
                                Set.of("resolveCurrentContext", "resolveForUser",
                                        "resolveCurrentRepositoryContext",
                                        "resolveRepositoryContextForUser"))),
                Map.entry("com.taxonomy.dsl.storage.DslGitRepositoryFactory",
                        new TargetDescriptor("taxonomy.repository.route", "repository",
                                Set.of("resolveRepository", "getSystemRepository",
                                        "getWorkspaceRepository"))),
                Map.entry("com.taxonomy.versioning.service.DslOperationsFacade",
                        new TargetDescriptor("taxonomy.repository.operation", "repository",
                                Set.of("materialize", "materializeIncremental", "commitDsl",
                                        "getDslHistory", "diffBetween", "textDiff",
                                        "listBranches", "createBranch", "cherryPick", "merge",
                                        "revert", "undoLast", "restore", "getDslAtHead",
                                        "getDslAtCommit", "getHeadCommit", "indexBranch",
                                        "searchHistory"))),
                Map.entry("com.taxonomy.catalog.service.SearchService",
                        new TargetDescriptor("taxonomy.search", "search", Set.of("search"))),
                Map.entry("com.taxonomy.analysis.usecase.AnalyzeRequirementUseCase",
                        new TargetDescriptor("taxonomy.analysis", "analysis", Set.of("analyze"))),
                Map.entry("com.taxonomy.analysis.usecase.AnalyzeNodeChildrenUseCase",
                        new TargetDescriptor("taxonomy.analysis", "analysis", Set.of("analyze"))),
                Map.entry("com.taxonomy.analysis.service.LlmService",
                        new TargetDescriptor("taxonomy.llm", "llm",
                                Set.of("analyzeWithBudget", "analyzeSingleBatchDetailed",
                                        "generateLeafJustification", "callLlmRaw"))),
                Map.entry("com.taxonomy.catalog.service.importer.FrameworkImportService",
                        new TargetDescriptor("taxonomy.import", "import",
                                Set.of("preview", "importFile"))),
                Map.entry("com.taxonomy.export.service.ExportFacade",
                        new TargetDescriptor("taxonomy.export", "export",
                                Set.of("exportAsVisio", "exportAsArchiMate", "exportAsMermaid",
                                        "exportAsStructurizrDsl", "buildDiagram",
                                        "importFromJson")))
        );

        private final Supplier<ObservationRegistry> registrySupplier;

        TaxonomyObservationBeanPostProcessor(
                Supplier<ObservationRegistry> registrySupplier) {
            this.registrySupplier = registrySupplier;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName)
                throws BeansException {
            Class<?> targetClass = ClassUtils.getUserClass(AopUtils.getTargetClass(bean));
            TargetDescriptor descriptor = TARGETS.get(targetClass.getName());
            if (descriptor == null) {
                return bean;
            }

            MethodInterceptor interceptor =
                    new TaxonomyObservationInterceptor(registrySupplier, descriptor);
            if (bean instanceof Advised advised) {
                advised.addAdvice(interceptor);
                return bean;
            }

            ProxyFactory proxyFactory = new ProxyFactory(bean);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(interceptor);
            return proxyFactory.getProxy(bean.getClass().getClassLoader());
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    static final class TaxonomyObservationInterceptor implements MethodInterceptor {

        private static final Logger log = LoggerFactory.getLogger(
                TaxonomyObservationInterceptor.class);

        private final Supplier<ObservationRegistry> registrySupplier;
        private final TargetDescriptor descriptor;

        TaxonomyObservationInterceptor(ObservationRegistry observationRegistry,
                                       TargetDescriptor descriptor) {
            this(() -> observationRegistry, descriptor);
        }

        TaxonomyObservationInterceptor(Supplier<ObservationRegistry> registrySupplier,
                                       TargetDescriptor descriptor) {
            this.registrySupplier = registrySupplier;
            this.descriptor = descriptor;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            String operation = invocation.getMethod().getName();
            if (!descriptor.methods().contains(operation)) {
                return invocation.proceed();
            }

            ObservationRegistry observationRegistry = registrySupplier.get();
            if (observationRegistry == null) {
                observationRegistry = ObservationRegistry.NOOP;
            }
            Observation observation = Observation.createNotStarted(
                            descriptor.observationName(), observationRegistry)
                    .lowCardinalityKeyValue("taxonomy.component", descriptor.component())
                    .lowCardinalityKeyValue("taxonomy.operation", operation)
                    .start();
            try (Observation.Scope ignored = observation.openScope()) {
                Object result = invocation.proceed();
                observation.lowCardinalityKeyValue("outcome", "success");
                log.debug("Observed taxonomy operation component={} operation={} outcome=success",
                        descriptor.component(), operation);
                return result;
            } catch (Throwable failure) {
                // The exception is deliberately not attached to the Observation or log because
                // its message may contain imported, DSL or LLM content. Agent method spans retain
                // exception type and error status independently.
                observation.lowCardinalityKeyValue("outcome", "error");
                log.debug("Observed taxonomy operation component={} operation={} outcome=error",
                        descriptor.component(), operation);
                throw failure;
            } finally {
                observation.stop();
            }
        }
    }
}
