package com.taxonomy.analysis.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(45)
class AnalysisDurableEffectTest {
    @Test void simultaneousDeliveryCommitsOneDurableEffect() throws Exception { AnalysisDurableEffectCases.concurrentSameTask(); }
    @Test void failedResultRollsBackItsCompletion() throws Exception { AnalysisDurableEffectCases.rollbackAndRetry(false); }
    @Test void crashRollsBackItsCompletion() throws Exception { AnalysisDurableEffectCases.rollbackAndRetry(true); }
    @Test void independentTasksCanOverlap() throws Exception { AnalysisDurableEffectCases.independentTasksOverlap(); }
    @Test void preparationAndCommitHaveSeparateBoundaries() throws Exception { AnalysisDurableEffectCases.preparationIsOutsideCommitAndReplaySkipsEffect(); }
    @Test void differentSourceCannotAliasAResult() throws Exception { AnalysisDurableEffectCases.wrongSourceCannotReuseWinner(); }
}
