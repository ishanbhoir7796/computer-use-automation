package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetryPolicy {

    @Builder.Default
    private int maxAttempts = 2;

    @Builder.Default
    private int backoffMs = 800;
}