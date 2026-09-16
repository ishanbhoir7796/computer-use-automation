package com.interfaceai.cuacore.schema;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplayResult {

    private ReplayStatus status;
    private String capabilityId;
    private int capabilityVersion;
    private String runId;

    @Builder.Default
    private Map<String, Object> outputs = new HashMap<>();

    private String outcomeCode;     // set for BUSINESS_OUTCOME / HARD_FAILURE / ESCALATED
    private String message;

    private String failedStepId;
    private String expected;
    private String observed;

    private String evidenceDir;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Builder.Default
    private Instant startedAt = Instant.now();

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant finishedAt;

    /**
     * SUCCESS and BUSINESS_OUTCOME both count as "it worked" for the
     * caller -- a business outcome like "member not found" is a real
     * answer, not an error, so only HARD_FAILURE and ESCALATED are
     * actual problems.
     */
    public boolean isOkForCaller() {
        return status == ReplayStatus.SUCCESS || status == ReplayStatus.BUSINESS_OUTCOME;
    }
}