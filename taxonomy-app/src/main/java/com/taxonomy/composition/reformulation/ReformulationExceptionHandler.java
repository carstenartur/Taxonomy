package com.taxonomy.composition.reformulation;

import com.taxonomy.portfolio.controller.PortfolioExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Reuse portfolio problem semantics for this app-owned composition controller. */
@RestControllerAdvice(assignableTypes=ReformulationController.class)
@Order(0)
public class ReformulationExceptionHandler extends PortfolioExceptionHandler {}
