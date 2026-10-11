package com.taxonomy.composition.demo;

import com.taxonomy.portfolio.controller.PortfolioExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Reuse portfolio problem semantics (including the stable {@code code} property) for the app-owned demo controller. */
@com.taxonomy.shared.features.ConditionalOnFeature({"portfolio", "analysis"})
@RestControllerAdvice(assignableTypes = ProductDemoController.class)
@Order(0)
public class ProductDemoExceptionHandler extends PortfolioExceptionHandler {}
