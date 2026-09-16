package com.interfaceai.cuacore.schema;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A recorded, reusable capability -- the actual thing an AI agent calls
 * in production. It holds everything needed to replay a discovered flow
 * without any LLM: the target app, the inputs it needs, the outputs it
 * returns, the ordered steps, how to tell it succeeded, and what known
 * outcomes (like "not found") to watch for.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Capability {

    private String capabilityId;
    private String name;

    @Builder.Default
    private int version = 1;

    private String description;

    private TargetFingerprint target;

    private List<InputParamSpec> inputSchema;
    private List<OutputFieldSpec> outputSchema;

    private List<CapabilityStep> steps;
    private DetectionRule successCondition;

    @Builder.Default
    private List<OutcomeSpec> knownOutcomes = new ArrayList<>();

    @Builder.Default
    private String allowlistProfile = "default";

    // draft -> approved gate; unattended replay of RISKY steps requires this
    @Builder.Default
    private boolean reviewed = false;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Builder.Default
    private String createdFromRunId = "";

    @Builder.Default
    private int schemaVersion = 1;

    public CapabilityStep findStep(String stepId) {
        return steps.stream()
                .filter(s -> s.getStepId().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new NoSuchElementException("no such step_id: " + stepId));
    }
}