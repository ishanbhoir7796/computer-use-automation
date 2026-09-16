package com.interfaceai.cuacore.escalation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ControlState {

    @Builder.Default
    private String owner = "automation";   // "automation" | "human"

    private InterventionRequest intervention;

    private String resolution;   // "resumed" | "aborted" -- set by the operator console when handing back

    @Builder.Default
    private List<HumanAction> humanActions = new ArrayList<>();

    @Builder.Default
    private Instant updatedAt = Instant.now();
}