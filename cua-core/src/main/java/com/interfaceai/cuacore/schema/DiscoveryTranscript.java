package com.interfaceai.cuacore.schema;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The raw, turn-by-turn record of one discovery run -- what the model saw
 * and did at each step, and why. This is different from Capability: this
 * is a messy log of one specific run, kept for evidence and debugging.
 * Recorder turns a successful one of these into a clean, reusable
 * Capability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoveryTranscript {

    private String runId;
    private String goal;
    private String targetBaseUrl;
    private String entryRoute;

    @Builder.Default
    private Map<String, Object> inputParamsUsed = new HashMap<>();

    @Builder.Default
    private List<TranscriptStep> steps = new ArrayList<>();

    @Builder.Default
    private String outcome = "in_progress";   // "success" | "stuck" | "max_steps" | "error"

    private String finalSummary;
    private String successConditionText;

    @Builder.Default
    private Map<String, String> declaredOutputs = new HashMap<>();

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Builder.Default
    private Instant startedAt = Instant.now();

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant finishedAt;
}