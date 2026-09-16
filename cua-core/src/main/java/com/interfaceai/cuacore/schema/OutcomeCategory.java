package com.interfaceai.cuacore.schema;

public enum OutcomeCategory {
    BUSINESS_OUTCOME,  // legitimate answer, not a crash (e.g. "no such member")
    RECOVERABLE,        // known transient/interstitial condition; replay handles it and continues
    HARD_FAILURE         // stop; surface a clear, debuggable error (or escalate)
}