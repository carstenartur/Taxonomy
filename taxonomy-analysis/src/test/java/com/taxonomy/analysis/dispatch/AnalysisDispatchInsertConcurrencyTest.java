package com.taxonomy.analysis.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(45)
class AnalysisDispatchInsertConcurrencyTest {
    @Test void concurrentFirstInsertPreservesBothCallerTransactions() throws Exception {
        DispatchInsertConcurrencyCases.simultaneousFirstInsert();
    }
    @Test void callerRollbackAlsoRollsBackIntents() { DispatchInsertConcurrencyCases.rollbackKeepsIntentsAtomic(); }
    @Test void duplicateCallerRollbackPreservesWinner() { DispatchInsertConcurrencyCases.duplicateThenRollbackPreservesWinner(); }
    @Test void publicationOrderAndSettledFilteringAreUnchanged() throws Exception { DispatchInsertConcurrencyCases.publicationOrderIsPreserved(); }
    @Test void otherConstraintsStillFail() { DispatchInsertConcurrencyCases.nonDuplicateConstraintFailurePropagates(); }
    @Test void differentSourceCannotAliasAnIntent() { DispatchInsertConcurrencyCases.foreignSourceIsRejected(); }
    @Test void reversedBatchesCommitWithoutChangingPublicationOrder() throws Exception {
        DispatchInsertConcurrencyCases.reversedBatchesCommitWithoutChangingPublicationOrder();
    }
    @Test void onlyUniqueKeyFailuresAreRecognized() {
        DispatchInsertConcurrencyCases.onlyUniqueKeyFailuresAreRecognized();
    }
}
