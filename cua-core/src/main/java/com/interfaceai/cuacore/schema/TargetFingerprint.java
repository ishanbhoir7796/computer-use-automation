package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Identifies which app and vendor product a capability was recorded
 * against, separately from any one tenant's actual URL. This is what lets
 * the same artifact be replayed against a different tenant running the
 * same underlying app, just by supplying a different base_url.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TargetFingerprint {

    private String appId;             // logical app name, e.g. "horizon-teller-console"
    private String vendorProduct;     // stand-in for "vendor X's core banking UI, family Y"

    @Builder.Default
    private String uiTechnology = "web_legacy";   // "web_modern" | "web_legacy" | "desktop"

    @Builder.Default
    private String baseUrlPattern = "{base_url}"; // supplied per-tenant at invocation time

    private String entryRoute;        // route relative to base_url, e.g. "/search"
}