package com.interfaceai.cuacore.cli;

import com.interfaceai.cuacore.agent.AnthropicProperties;
import com.interfaceai.cuacore.agent.ClaudeClient;
import com.interfaceai.cuacore.agent.DiscoveryAgent;
import com.interfaceai.cuacore.agent.Recorder;
import com.interfaceai.cuacore.escalation.HandoffController;
import com.interfaceai.cuacore.guardrails.AllowlistConfig;
import com.interfaceai.cuacore.guardrails.Redactor;
import com.interfaceai.cuacore.guardrails.RiskPolicy;
import com.interfaceai.cuacore.schema.Capability;
import com.interfaceai.cuacore.schema.DiscoveryTranscript;
import com.interfaceai.cuacore.schema.OutcomeSpec;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs a real LLM-driven discovery session against the target app and, on
 * success, saves both the raw transcript and the derived Capability
 * artifact to an evidence directory.
 *
 * Usage:
 *   java -cp ... DiscoverCommand \
 *     --goal="search for member 10001 and read their savings balance" \
 *     --base-url=http://localhost:8080 \
 *     --entry-route=/search \
 *     --capability-id=lookup_savings_balance \
 *     --evidence-dir=evidence/discovery_run \
 *     --input=member_id=10001
 */
public class DiscoverCommand {

    public static void main(String[] args) throws Exception {
        ArgParser parsed = new ArgParser(args);
        Map<String, Object> inputs = ArgParser.parseInputs(args);

        String goal = parsed.require("goal");
        String baseUrl = parsed.require("base-url");
        String entryRoute = parsed.require("entry-route");
        String capabilityId = parsed.require("capability-id");
        String evidenceDir = parsed.get("evidence-dir", "evidence/discovery_run");
        boolean headless = Boolean.parseBoolean(parsed.get("headless", "false"));

        Files.createDirectories(Path.of(evidenceDir));
        Files.createDirectories(Path.of(evidenceDir, "screenshots"));

        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("ERROR: ANTHROPIC_API_KEY environment variable is not set.");
            System.exit(1);
        }

        AnthropicProperties props = new AnthropicProperties();
        props.setApiKey(apiKey);
        props.setModel(parsed.get("model", "claude-sonnet-4-5-20250929"));

        ClaudeClient client = new ClaudeClient(props.getApiKey(), props.getModel());

        AllowlistConfig allowlist = AllowlistConfig.builder()
                .allowedDomains(List.of(baseUrl.replaceFirst("https?://", "")))
                .build();

        // If the model gets stuck, it can hand off to a human via the
        // operator console attached to this same evidence directory.
        HandoffController handoffController = new HandoffController(evidenceDir + "/control_state.json");

        DiscoveryAgent agent = new DiscoveryAgent(client, props, allowlist, RiskPolicy.builder().build(),
                15, 180_000, 3, handoffController, evidenceDir);

        System.out.println("Starting discovery run...");
        System.out.println("  goal: " + goal);
        System.out.println("  target: " + baseUrl + entryRoute);
        System.out.println("  inputs: " + inputs);

        DiscoveryTranscript transcript = agent.run(goal, baseUrl, entryRoute, inputs, headless,
                evidenceDir + "/screenshots");

        Redactor.scrubTranscript(transcript);
        JsonUtil.MAPPER.writeValue(new File(evidenceDir, "discovery_transcript.json"), transcript);

        System.out.println("\nDiscovery finished with outcome: " + transcript.getOutcome());

        if (!"success".equals(transcript.getOutcome())) {
            System.out.println("No capability artifact produced (transcript did not succeed).");
            System.out.println("Transcript saved to: " + evidenceDir + "/discovery_transcript.json");
            return;
        }

        Recorder recorder = new Recorder();
        Capability capability = recorder.buildCapability(
                transcript,
                capabilityId,
                capabilityId.replace("_", " "),
                "Recorded from a live discovery run: " + goal,
                "target-app",
                "target-app-vendor",
                List.<OutcomeSpec>of(), // known outcomes attached separately; see README
                Map.of()
        );

        File capabilityFile = new File(evidenceDir, capabilityId + ".json");
        JsonUtil.MAPPER.writeValue(capabilityFile, capability);

        System.out.println("Capability artifact saved to: " + capabilityFile.getPath());
    }
}