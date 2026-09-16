package com.interfaceai.cuacore.console;

import com.interfaceai.cuacore.escalation.ControlState;
import com.interfaceai.cuacore.escalation.ControlStateStore;
import com.interfaceai.cuacore.escalation.HumanAction;
import com.interfaceai.cuacore.escalation.InterventionRequest;
import com.interfaceai.cuacore.guardrails.AllowlistConfig;
import com.interfaceai.cuacore.schema.Locator;
import com.interfaceai.cuacore.schema.LocatorStrategy;
import com.interfaceai.cuacore.surface.PlaywrightSurface;

import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * The human operator's tool. A person runs this to connect to the exact
 * same live browser the automation was using (not a new one), see why it
 * stopped, and manually click/fill things using the same role+name system
 * automation uses -- then hand control back.
 *
 * Usage: run with one argument -- the path to the control-state JSON file
 * that the paused automation wrote.
 */
public class OperatorConsole {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: OperatorConsole <path-to-control-state.json>");
            return;
        }

        ControlStateStore store = new ControlStateStore(args[0]);
        ControlState state = store.read();

        if (!"human".equals(state.getOwner()) || state.getIntervention() == null) {
            System.out.println("No pending intervention -- nothing to do.");
            return;
        }

        InterventionRequest iv = state.getIntervention();
        System.out.println("=== Operator Console ===");
        System.out.println("run_id=" + iv.getRunId() + "  kind=" + iv.getRunKind()
                + "  reason_code=" + iv.getReasonCode());
        System.out.println("reason: " + iv.getReason());
        System.out.println("page: " + iv.getPageUrl());
        System.out.println("screenshot: " + iv.getScreenshotPath());
        System.out.println("Commands: click <role> <name...> | fill <role> <name...> = <value> | resume | abort");

        PlaywrightSurface surface = PlaywrightSurface.attach(iv.getCdpEndpoint(), AllowlistConfig.builder().build());

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("operator> ");
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.equals("resume")) {
                    store.handBackToAutomation("resumed");
                    System.out.println("Control handed back to automation (resumed).");
                    break;
                }
                if (line.equals("abort")) {
                    store.handBackToAutomation("aborted");
                    System.out.println("Control handed back to automation (aborted).");
                    break;
                }

                try {
                    if (line.startsWith("click ")) {
                        String rest = line.substring(6);
                        String[] parts = rest.split(" ", 2);
                        String role = parts[0];
                        String name = parts.length > 1 ? parts[1] : "";
                        Locator loc = Locator.builder().strategy(LocatorStrategy.ROLE_NAME).role(role).name(name).build();
                        surface.click(loc, 5000);
                        System.out.println("  -> clicked role=" + role + " name=" + name);

                        Map<String, Object> detail = new HashMap<>();
                        detail.put("role", role);
                        detail.put("name", name);
                        store.appendHumanAction(HumanAction.builder().action("click").detail(detail).build());

                    } else if (line.startsWith("fill ")) {
                        String rest = line.substring(5);
                        String[] eqParts = rest.split("=", 2);
                        String lhs = eqParts[0].trim();
                        String value = eqParts.length > 1 ? eqParts[1].trim() : "";
                        String[] parts = lhs.split(" ", 2);
                        String role = parts[0];
                        String name = parts.length > 1 ? parts[1] : "";
                        Locator loc = Locator.builder().strategy(LocatorStrategy.ROLE_NAME).role(role).name(name).build();
                        surface.fill(loc, value, 5000);
                        System.out.println("  -> filled role=" + role + " name=" + name);

                        Map<String, Object> detail = new HashMap<>();
                        detail.put("role", role);
                        detail.put("name", name);
                        detail.put("value", value);
                        store.appendHumanAction(HumanAction.builder().action("fill").detail(detail).build());

                    } else {
                        System.out.println("unrecognized command");
                    }
                } catch (Exception e) {
                    System.out.println("  !! " + e.getMessage());
                }
            }
        } finally {
            surface.close();
        }
    }
}