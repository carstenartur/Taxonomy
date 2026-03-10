package com.nato.taxonomy.dto;

/**
 * Records a scoring discrepancy where the LLM's raw child scores exceeded
 * the parent's score — a potential taxonomy inconsistency signal.
 *
 * <p>During analysis the LLM is asked to distribute a parent's score across
 * its children. When the raw sum returned by the LLM is greater than the
 * parent score, it indicates the LLM considers the children collectively
 * more relevant than the parent budget allows — a useful signal for
 * taxonomy weakness reporting.
 *
 * @param parentCode         the parent node code whose children were scored
 * @param expectedParentScore the parent's assigned score (target for children)
 * @param actualChildSum     the raw sum of child scores returned by the LLM
 */
public record TaxonomyDiscrepancy(String parentCode, int expectedParentScore, int actualChildSum) {}
