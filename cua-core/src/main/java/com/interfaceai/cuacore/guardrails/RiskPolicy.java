package com.interfaceai.cuacore.guardrails;

import com.interfaceai.cuacore.schema.Capability;
import com.interfaceai.cuacore.schema.CapabilityStep;
import com.interfaceai.cuacore.schema.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Decides what to do with a risky action, and the rule is different
 * depending on when it happens. During discovery, a human is watching
 * live, so risky actions are allowed by default. During replay -- running
 * unattended, with no one watching -- a risky step only runs automatically
 * if the capability has already been reviewed and approved. Otherwise it
 * gets sent to a human for a yes/no before it runs.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPolicy {

    @Builder.Default
    private boolean allowRiskyInDiscovery = true;

    @Builder.Default
    private boolean requireReviewForUnattendedRiskyReplay = true;

    public RiskDecision decideDiscovery(boolean risky) {
        if (!risky) return RiskDecision.PROCEED;
        return allowRiskyInDiscovery ? RiskDecision.PROCEED : RiskDecision.BLOCK;
    }

    public RiskDecision decideReplay(Capability capability, CapabilityStep step) {
        if (step.getRiskLevel() != RiskLevel.RISKY) return RiskDecision.PROCEED;
        if (capability.isReviewed() || !requireReviewForUnattendedRiskyReplay) return RiskDecision.PROCEED;
        return RiskDecision.ESCALATE_FOR_CONFIRMATION;
    }
}