package com.interfaceai.cuacore.cli;

import com.interfaceai.cuacore.escalation.HandoffController;
import com.interfaceai.cuacore.guardrails.AllowlistConfig;
import com.interfaceai.cuacore.guardrails.RiskPolicy;
import com.interfaceai.cuacore.replay.ReplayExecutor;
import com.interfaceai.cuacore.schema.Capability;
import com.interfaceai.cuacore.schema.ReplayResult;
import com.interfaceai.cuacore.surface.PlaywrightSurface;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Replays a previously-saved Capability artifact deterministically -- no
 * LLM involved. This is the path an AI agent would trigger in production.
 *
 * Usage:
 *   java -cp ... ReplayCommand \
 *     --capability-file=evidence/discovery_run/lookup_savings_balance.json \
 *     --base-url=http://localhost:8080 \
 *     --evidence-dir=evidence/replay_run \
 *     --input=member_id=10001
 */
public class ReplayCommand {

    public static void main(String[] args) throws Exception {
        ArgParser parsed = new ArgParser(args);
        Map<String, Object> inputs = ArgParser.parseInputs(args);

        String capabilityFilePath = parsed.require("capability-file");
        String baseUrl = parsed.require("base-url");
        String evidenceDir = parsed.get("evidence-dir", "evidence/replay_run");
        boolean headless = Boolean.parseBoolean(parsed.get("headless", "false"));

        Files.createDirectories(Path.of(evidenceDir));

        Capability capability = JsonUtil.MAPPER.readValue(new File(capabilityFilePath), Capability.class);

        AllowlistConfig allowlist = AllowlistConfig.builder()
                .allowedDomains(List.of(baseUrl.replaceFirst("https?://", "")))
                .build();

        // Launched with handoff enabled, so if replay needs to escalate, a
        // human can attach to this exact browser via the operator console.
        PlaywrightSurface surface = new PlaywrightSurface(allowlist, headless).launchExposedForHandoff();
        surface.navigate(baseUrl.replaceAll("/$", "") + capability.getTarget().getEntryRoute());

        System.out.println("Replaying capability: " + capability.getCapabilityId());
        System.out.println("  inputs: " + inputs);

        HandoffController handoffController = new HandoffController(evidenceDir + "/control_state.json");
        ReplayExecutor executor = new ReplayExecutor(RiskPolicy.builder().build(), handoffController, evidenceDir);
        ReplayResult result;
        try {
            result = executor.replay(capability, inputs, baseUrl, surface);
        } finally {
            surface.screenshot(evidenceDir + "/final_state.png");
            surface.close();
        }

        JsonUtil.MAPPER.writeValue(new File(evidenceDir, "replay_result.json"), result);

        System.out.println("\n=== REPLAY RESULT ===");
        System.out.println("status: " + result.getStatus());
        System.out.println("outcomeCode: " + result.getOutcomeCode());
        System.out.println("message: " + result.getMessage());
        System.out.println("outputs: " + result.getOutputs());
        System.out.println("\nSaved to: " + evidenceDir + "/replay_result.json");
    }
}