package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityStep {

    private String stepId;
    private ActionType action;
    private Locator locator;

    // value to type/select; either a literal, or "{{param_name}}" referencing
    // a declared input parameter, resolved at replay time
    private String valueTemplate;

    private String extractAs;        // output name, required if action == EXTRACT
    private String checkpointNote;    // human-readable: what this step should result in

    @Builder.Default
    private RiskLevel riskLevel = RiskLevel.SAFE;

    @Builder.Default
    private int timeoutMs = 8000;

    @Builder.Default
    private RetryPolicy retry = RetryPolicy.builder().build();
}