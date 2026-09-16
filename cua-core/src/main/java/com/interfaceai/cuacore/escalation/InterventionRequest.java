package com.interfaceai.cuacore.escalation;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InterventionRequest {

    private String interventionId;
    private String runId;
    private String runKind;              // "discovery" | "replay"
    private String capabilityId;
    private String goalOrCapabilityName;
    private String reason;
    private String reasonCode;            // e.g. "PERMISSION_DENIED", "model_stuck", "risky_step_unreviewed"
    private String failedStepId;
    private String cdpEndpoint;            // how the operator console attaches to the SAME live browser
    private String screenshotPath;
    private String pageUrl;
    private String visibleTextExcerpt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Builder.Default
    private Instant createdAt = Instant.now();
}