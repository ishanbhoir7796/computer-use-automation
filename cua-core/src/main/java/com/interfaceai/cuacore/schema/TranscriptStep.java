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
public class TranscriptStep {

    private int turn;
    private String action;             // matches tool name: "click", "fill", "finish_success", etc.

    @Builder.Default
    private Map<String, Object> toolInput = new HashMap<>();

    private String resultText;         // e.g. extracted text, or a short description of what happened
    private String rationale;           // model's stated reasoning for this action, if any
    private boolean risky;
    private String screenshotPath;
    private String urlBefore;
    private String urlAfter;
    private Locator resolvedLocator;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    @Builder.Default
    private Instant timestamp = Instant.now();
}