package com.interfaceai.cuacore.guardrails;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules for what automation is and isn't allowed to touch: which
 * domains and routes it can navigate to, which action types it can use,
 * and which specific actions (like clicking "Submit") should always be
 * treated as risky, no matter what the capability's own settings say.
 * This is checked inside PlaywrightSurface itself, so an action can't
 * reach the browser at all if it breaks the rules.
 */

@Data
@Builder
@NoArgsConstructor
public class AllowlistConfig {

    @Builder.Default
    private String profileName = "default";

    @Builder.Default
    private List<String> allowedDomains = new ArrayList<>();     // e.g. ["localhost:8080"]

    @Builder.Default
    private List<String> allowedRouteGlobs = new ArrayList<>(List.of("/*"));

    @Builder.Default
    private List<String> allowedActionTypes = new ArrayList<>(List.of(
            "navigate", "click", "fill", "select", "extract", "assert_text", "wait_for_text"));

    // "action:role_name_glob" patterns treated as RISKY regardless of the
    // artifact's own tagging, e.g. "click:*Submit*"
    @Builder.Default
    private List<String> riskyActionPatterns = new ArrayList<>(List.of(
            "click:*Submit*", "click:*Confirm*"));

    public AllowlistConfig(String profileName, List<String> allowedDomains, List<String> allowedRouteGlobs,
                           List<String> allowedActionTypes, List<String> riskyActionPatterns) {
        this.profileName = profileName;
        this.allowedDomains = allowedDomains;
        this.allowedRouteGlobs = allowedRouteGlobs;
        this.allowedActionTypes = allowedActionTypes;
        this.riskyActionPatterns = riskyActionPatterns;
    }

    public void checkNavigation(String url) {
        URI parsed = URI.create(url);
        String netloc = parsed.getAuthority(); // host[:port]

        boolean domainOk = allowedDomains.isEmpty() || allowedDomains.stream()
                .anyMatch(d -> netloc != null && (netloc.equals(d) || netloc.endsWith("." + d)));
        if (!domainOk) {
            throw new AllowlistViolation("domain not in allowlist: " + netloc + " (allowed: " + allowedDomains + ")");
        }

        String path = parsed.getPath() == null || parsed.getPath().isEmpty() ? "/" : parsed.getPath();
        boolean routeOk = allowedRouteGlobs.isEmpty() || allowedRouteGlobs.stream()
                .anyMatch(g -> globToRegex(g).matcher(path).matches());
        if (!routeOk) {
            throw new AllowlistViolation("route not in allowlist: " + path + " (allowed: " + allowedRouteGlobs + ")");
        }
    }

    public void checkActionType(String action) {
        if (!allowedActionTypes.contains(action)) {
            throw new AllowlistViolation("action type not permitted by policy: " + action);
        }
    }

    public boolean isRiskyByPolicy(String action, String role, String name) {
        String label = action + ":" + (name == null ? "" : name);
        return riskyActionPatterns.stream().anyMatch(pat -> globToRegex(pat).matcher(label).matches());
    }

    private static Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append(".");
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        sb.append("$");
        return Pattern.compile(sb.toString());
    }
}