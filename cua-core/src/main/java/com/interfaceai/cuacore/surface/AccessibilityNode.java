package com.interfaceai.cuacore.surface;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One clickable or readable thing on the page, as we see it: a role, a
 * name, and (for table cells) an xpath, since cells don't have a real
 * accessible name we can rely on.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccessibilityNode {

    private String role;
    private String name;

    @Builder.Default
    private String tag = "";

    @Builder.Default
    private String value = "";

    @Builder.Default
    private String href = "";

    @Builder.Default
    private String xpath = "";
}