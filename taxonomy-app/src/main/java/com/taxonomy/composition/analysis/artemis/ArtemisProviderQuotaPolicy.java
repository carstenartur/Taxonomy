package com.taxonomy.composition.analysis.artemis;

import com.taxonomy.analysis.service.ProviderRateQuotas;

/** Public scalar admin/binding contract; contains neither URLs nor credentials. */
public record ArtemisProviderQuotaPolicy(int rpm,long tpm,int rpd,long inputUpperBound,int outputCap,
                                        String tokenAdapter,Long clockSkewMillis,Integer maximumReservations) {
    public ArtemisProviderQuotaPolicy {
        clockSkewMillis=clockSkewMillis==null?1000L:clockSkewMillis;
        maximumReservations=maximumReservations==null?20000:maximumReservations;
        new ProviderQuotaLedger.Policy(rpm,tpm,rpd,inputUpperBound,outputCap,clockSkewMillis,maximumReservations);
        if(tpm>0) new ProviderRateQuotas.TokenBudget(tokenAdapter,inputUpperBound,outputCap);
        else if(tokenAdapter!=null&&!tokenAdapter.isBlank()) throw new IllegalArgumentException("RPM/RPD-only quota must not declare a token adapter");
    }
    ProviderQuotaLedger.Policy ledgerPolicy() {
        return new ProviderQuotaLedger.Policy(rpm,tpm,rpd,inputUpperBound,outputCap,clockSkewMillis,maximumReservations);
    }
    ProviderRateQuotas.TokenBudget tokenBudget() {
        return tpm==0?null:new ProviderRateQuotas.TokenBudget(tokenAdapter,inputUpperBound,outputCap);
    }
}
