package com.interfaceai.cuacore.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.interfaceai.cuacore.escalation.ControlState;
import com.interfaceai.cuacore.escalation.HandoffController;
import com.interfaceai.cuacore.guardrails.AllowlistConfig;
import com.interfaceai.cuacore.guardrails.RiskDecision;
import com.interfaceai.cuacore.guardrails.RiskPolicy;
import com.interfaceai.cuacore.schema.DiscoveryTranscript;
import com.interfaceai.cuacore.schema.Locator;
import com.interfaceai.cuacore.schema.LocatorStrategy;
import com.interfaceai.cuacore.schema.TranscriptStep;
import com.interfaceai.cuacore.surface.PlaywrightSurface;
import com.interfaceai.cuacore.surface.Snapshot;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives the actual LLM-in-the-loop discovery session: shows Claude the
 * current page, lets it decide one action, runs that action, shows it the
 * new page, and repeats until it says the goal is done (or gives up).
 * Everything it does gets recorded into a DiscoveryTranscript.
 */
public class DiscoveryAgent {

    private final ClaudeClient client;
    private final AnthropicProperties anthropicProperties;
    private final AllowlistConfig allowlist;
    private final RiskPolicy riskPolicy;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int maxSteps;
    private final long maxWallMs;
    private final int maxConsecutiveErrors;

    private final Map<String, Object> pendingOutputs = new HashMap<>();
    private Locator lastResolvedLocator;

    private final HandoffController handoffController;   // null if this agent can't hand off to a human
    private final String evidenceDir;

    public DiscoveryAgent(ClaudeClient client, AnthropicProperties anthropicProperties,
                          AllowlistConfig allowlist, RiskPolicy riskPolicy,
                          int maxSteps, long maxWallMs, int maxConsecutiveErrors) {
        this(client, anthropicProperties, allowlist, riskPolicy, maxSteps, maxWallMs, maxConsecutiveErrors,
                null, null);
    }

    public DiscoveryAgent(ClaudeClient client, AnthropicProperties anthropicProperties,
                          AllowlistConfig allowlist, RiskPolicy riskPolicy,
                          int maxSteps, long maxWallMs, int maxConsecutiveErrors,
                          HandoffController handoffController, String evidenceDir) {
        this.client = client;
        this.anthropicProperties = anthropicProperties;
        this.allowlist = allowlist;
        this.riskPolicy = riskPolicy;
        this.maxSteps = maxSteps;
        this.maxWallMs = maxWallMs;
        this.maxConsecutiveErrors = maxConsecutiveErrors;
        this.handoffController = handoffController;
        this.evidenceDir = evidenceDir;
    }

    public DiscoveryTranscript run(String goal, String baseUrl, String entryRoute,
                                   Map<String, Object> inputParams, boolean headless,
                                   String screenshotDir) throws Exception {

        String runId = "discovery-" + UUID.randomUUID().toString().substring(0, 8);
        DiscoveryTranscript transcript = DiscoveryTranscript.builder()
                .runId(runId)
                .goal(goal)
                .targetBaseUrl(baseUrl)
                .entryRoute(entryRoute)
                .inputParamsUsed(inputParams)
                .build();

        boolean exposeForHandoff = handoffController != null;
        PlaywrightSurface surface = exposeForHandoff
                ? new PlaywrightSurface(allowlist, headless).launchExposedForHandoff()
                : new PlaywrightSurface(allowlist, headless).launch();

        long started = System.currentTimeMillis();
        int consecutiveErrors = 0;
        ArrayNode messages = mapper.createArrayNode();

        try {
            surface.navigate(baseUrl.replaceAll("/$", "") + entryRoute);
            Snapshot snapshot = surface.perceive();

            String userPrompt = "GOAL: " + goal + "\n\n"
                    + "Relevant input values you may need (use them verbatim where the goal calls for them): "
                    + inputParams + "\n\n"
                    + "CURRENT PAGE STATE:\n" + snapshot.compactRepr(30);

            messages.add(userMessage(userPrompt));

            for (int turn = 1; turn <= maxSteps; turn++) {
                if (System.currentTimeMillis() - started > maxWallMs) {
                    transcript.setOutcome("max_steps");
                    break;
                }

                JsonNode response = callClaude(messages);
                JsonNode contentBlocks = response.get("content");
                messages.add(assistantMessage(contentBlocks));

                StringBuilder rationale = new StringBuilder();
                JsonNode toolBlock = null;
                for (JsonNode block : contentBlocks) {
                    if ("text".equals(block.get("type").asText())) {
                        rationale.append(block.get("text").asText()).append(" ");
                    } else if ("tool_use".equals(block.get("type").asText()) && toolBlock == null) {
                        toolBlock = block;
                    }
                }

                if (toolBlock == null) {
                    messages.add(userMessage("Please call exactly one tool to proceed."));
                    continue;
                }

                String toolName = toolBlock.get("name").asText();
                String toolUseId = toolBlock.get("id").asText();
                JsonNode toolInput = toolBlock.get("input");

                if (toolName.equals("finish_success")) {
                    transcript.setOutcome("success");
                    transcript.setFinalSummary(toolInput.get("summary").asText());
                    transcript.setSuccessConditionText(toolInput.get("success_condition_text").asText());
                    Map<String, String> outputs = new HashMap<>();
                    pendingOutputs.forEach((k, v) -> outputs.put(k, String.valueOf(v)));
                    transcript.setDeclaredOutputs(outputs);
                    break;
                }

                if (toolName.equals("finish_stuck")) {
                    String reason = toolInput.get("reason").asText();
                    String shotPath = screenshotDir + "/" + runId + "_stuck_turn" + turn + ".png";
                    surface.screenshot(shotPath);

                    if (handoffController != null) {
                        ControlState resolved = handoffController.escalate(
                                runId, "discovery", goal, reason, "model_stuck",
                                surface, null, null, shotPath, 600_000);

                        if ("resumed".equals(resolved.getResolution())) {
                            // human fixed the situation -- give the model a fresh look
                            // at wherever the page is now, and let it keep going
                            Snapshot afterHuman = surface.perceive();
                            messages.add(userMessage(
                                    "A human operator just helped resolve the issue. Here is the "
                                            + "current page state -- continue working toward the goal:\n\n"
                                            + afterHuman.compactRepr(30)));
                            continue;
                        }
                        // operator aborted -- fall through to a normal "stuck" ending
                    }

                    transcript.setOutcome("stuck");
                    transcript.setFinalSummary(reason);
                    break;
                }

                TranscriptStep.TranscriptStepBuilder stepBuilder = TranscriptStep.builder()
                        .turn(turn)
                        .action(toolName)
                        .toolInput(mapper.convertValue(toolInput, new TypeReference<Map<String, Object>>() {}))
                        .rationale(rationale.toString().trim())
                        .urlBefore(surface.currentUrl());

                String toolResultContent;
                try {
                    boolean risky = executeTool(surface, toolName, toolInput);
                    stepBuilder.risky(risky);
                    stepBuilder.resolvedLocator(lastResolvedLocator);
                    stepBuilder.urlAfter(surface.currentUrl());
                    consecutiveErrors = 0;
                    toolResultContent = formatObservation(surface);
                } catch (Exception e) {
                    consecutiveErrors++;
                    String err = "ACTION FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    stepBuilder.resultText(err);
                    toolResultContent = err + "\n\n" + formatObservation(surface);
                }

                String shotPath = screenshotDir + "/" + runId + "_turn" + turn + ".png";
                surface.screenshot(shotPath);
                stepBuilder.screenshotPath(shotPath);
                transcript.getSteps().add(stepBuilder.build());

                messages.add(toolResultMessage(toolUseId, toolResultContent));

                if (consecutiveErrors >= maxConsecutiveErrors) {
                    transcript.setOutcome("error");
                    transcript.setFinalSummary("stopped after " + consecutiveErrors + " consecutive action errors");
                    break;
                }
            }

            if ("in_progress".equals(transcript.getOutcome())) {
                transcript.setOutcome("max_steps");
            }

        } finally {
            surface.close();
            transcript.setFinishedAt(Instant.now());
        }

        return transcript;
    }

    // -- Claude API plumbing -----------------------------------------------
    private JsonNode callClaude(ArrayNode messages) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", anthropicProperties.getModel());
        body.put("max_tokens", 1024);
        body.put("system", AgentPrompts.SYSTEM_PROMPT);
        body.set("tools", AgentPrompts.buildTools(mapper));
        body.set("messages", messages);

        return client.sendMessage(body.toString());
    }

    private ObjectNode userMessage(String text) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        msg.put("content", text);
        return msg;
    }

    private ObjectNode assistantMessage(JsonNode content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        msg.set("content", content);
        return msg;
    }

    private ObjectNode toolResultMessage(String toolUseId, String content) {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode contentArray = mapper.createArrayNode();
        ObjectNode resultBlock = mapper.createObjectNode();
        resultBlock.put("type", "tool_result");
        resultBlock.put("tool_use_id", toolUseId);
        resultBlock.put("content", content);
        contentArray.add(resultBlock);
        msg.set("content", contentArray);
        return msg;
    }

    private String formatObservation(PlaywrightSurface surface) {
        Snapshot snap = surface.perceive();
        return "RESULTING PAGE STATE:\n" + snap.compactRepr(30);
    }

    private boolean executeTool(PlaywrightSurface surface, String name, JsonNode input) {
        boolean risky = false;
        switch (name) {
            case "navigate" -> {
                surface.navigate(input.get("url").asText());
                lastResolvedLocator = null;
            }
            case "click", "fill", "select", "extract" -> {
                String role = input.get("role").asText();
                String elName = input.get("name").asText();
                if (name.equals("click")) {
                    risky = allowlist.isRiskyByPolicy("click", role, elName);
                    if (risky && riskPolicy.decideDiscovery(true) == RiskDecision.BLOCK) {
                        throw new RuntimeException("risky action blocked by policy: click name=" + elName);
                    }
                }

                Locator loc;
                if (name.equals("extract") && role.equals("cell")) {
                    loc = buildCellLocator(surface, elName);
                } else {
                    loc = Locator.builder().strategy(LocatorStrategy.ROLE_NAME).role(role).name(elName)
                            .fallbacks(List.of(
                                    Locator.builder().strategy(LocatorStrategy.LABEL_TEXT).name(elName).build()
                            ))
                            .build();
                }
                lastResolvedLocator = loc;

                switch (name) {
                    case "click" -> surface.click(loc, 8000);
                    case "fill" -> surface.fill(loc, input.get("value").asText(), 8000);
                    case "select" -> surface.select(loc, input.get("value").asText(), 8000);
                    case "extract" -> {
                        String value = surface.extract(loc, 8000);
                        pendingOutputs.put(input.get("output_name").asText(), value);
                    }
                }
            }
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        }
        return risky;
    }

    /**
     * Table cells don't have a real browser-computed name like buttons or
     * links do. The name we show the model (e.g. "Savings Balance") is one
     * we made up just so it's readable, so it can't actually be used to
     * find the element. Instead, we look up that same cell in the snapshot
     * and use its real XPath, which Playwright can actually resolve.
     */
    private Locator buildCellLocator(PlaywrightSurface surface, String cellName) {
        Snapshot snap = surface.perceive();
        for (var node : snap.getNodes()) {
            if ("cell".equals(node.getRole()) && node.getName().equals(cellName) && !node.getXpath().isEmpty()) {
                return Locator.builder()
                        .strategy(LocatorStrategy.CSS)
                        .css("xpath=" + node.getXpath())
                        .build();
            }
        }
        return Locator.builder().strategy(LocatorStrategy.ROLE_NAME).role("cell").name(cellName).build();
    }
}