package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Identifies one control on the page: a role + name (like a button
 * labeled "Search"), with optional fallback locators to try if the
 * primary one can't be found. We use role/name instead of CSS selectors
 * because legacy apps often have no stable classes or ids to target.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Locator {

    private LocatorStrategy strategy;
    private String role;   // e.g. "textbox", "button", "combobox", "link"
    private String name;   // accessible name, e.g. "Initial Deposit ($)"
    private String text;   // for TEXT_EXACT
    private String css;    // for CSS fallback (last resort)

    @Builder.Default
    private int nth = 0;   // which match to use, if more than one is found

    @Builder.Default
    private List<Locator> fallbacks = new ArrayList<>();

    public String describe() {
        return switch (strategy) {
            case ROLE_NAME -> "role=" + role + " name=" + name;
            case LABEL_TEXT -> "label=" + name;
            case TEXT_EXACT -> "text=" + text;
            case CSS -> "css=" + css;
        };
    }
}