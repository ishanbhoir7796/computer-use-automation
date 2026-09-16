package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InputParamSpec {

    private String name;
    private ParamType type;

    @Builder.Default
    private boolean required = true;

    @Builder.Default
    private String description = "";

    // if true, never written to logs/artifacts in plaintext
    @Builder.Default
    private boolean sensitive = false;
}