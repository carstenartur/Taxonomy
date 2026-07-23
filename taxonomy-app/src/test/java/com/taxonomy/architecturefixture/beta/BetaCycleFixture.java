package com.taxonomy.architecturefixture.beta;

import com.taxonomy.architecturefixture.alpha.AlphaCycleFixture;

/** Test-only fixture proving that the cycle rule rejects a new domain cycle. */
public class BetaCycleFixture {
    private AlphaCycleFixture alpha;

    public AlphaCycleFixture getAlpha() {
        return alpha;
    }
}
