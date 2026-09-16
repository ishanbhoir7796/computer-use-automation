package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputFieldSpec {

    private String name;
    private ParamType type;

    @Builder.Default
    private String description = "";

    private String extractedFromStep;   // step_id of the EXTRACT step that produces this

    @Builder.Default
    private boolean sensitive = false;
}