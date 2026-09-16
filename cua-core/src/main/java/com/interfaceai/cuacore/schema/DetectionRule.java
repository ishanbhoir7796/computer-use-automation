package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DetectionRule {

    private DetectionKind kind;
    private String value;   // text substring / status code string / "role:name"
}