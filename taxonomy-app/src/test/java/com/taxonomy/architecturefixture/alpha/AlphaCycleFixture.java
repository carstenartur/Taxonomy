package com.taxonomy.architecturefixture.alpha;

import com.taxonomy.architecturefixture.beta.BetaCycleFixture;

/** Test-only fixture proving that the cycle rule rejects a new domain cycle. */
public class AlphaCycleFixture {
    private BetaCycleFixture beta;

    public BetaCycleFixture getBeta() {
        return beta;
    }
}