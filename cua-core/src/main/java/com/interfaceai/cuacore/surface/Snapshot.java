package com.interfaceai.cuacore.surface;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Snapshot {

    private String url;
    private String title;

    @Builder.Default
    private List<AccessibilityNode> nodes = new ArrayList<>();

    @Builder.Default
    private String visibleText = "";

    // Turns the snapshot into plain text Claude can read -- this is
    // literally what gets shown to the model each turn.
    public String compactRepr(int maxNodes) {
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append("\n");
        sb.append("TITLE: ").append(title).append("\n");
        sb.append("INTERACTIVE ELEMENTS:\n");

        int limit = Math.min(nodes.size(), maxNodes);
        for (int i = 0; i < limit; i++) {
            AccessibilityNode n = nodes.get(i);
            String extra = n.getValue() != null && !n.getValue().isEmpty()
                    ? " | value=\"" + n.getValue() + "\"" : "";
            sb.append("  - role=").append(n.getRole())
                    .append(" name=\"").append(n.getName()).append("\"")
                    .append(extra).append("\n");
        }
        if (nodes.size() > maxNodes) {
            sb.append("  ... (").append(nodes.size() - maxNodes).append(" more)\n");
        }
        sb.append("VISIBLE TEXT (truncated):\n");
        sb.append(visibleText.length() > 1500 ? visibleText.substring(0, 1500) : visibleText);
        return sb.toString();
    }

    // Collapses tabs/newlines/extra spaces into single spaces, so text
    // comparisons don't fail over invisible formatting differences.
    public static String normalizeWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }
}