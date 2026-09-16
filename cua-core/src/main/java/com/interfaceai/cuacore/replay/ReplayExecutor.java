package com.interfaceai.cuacore.replay;

import com.interfaceai.cuacore.escalation.ControlState;
import com.interfaceai.cuacore.escalation.HandoffController;
import com.interfaceai.cuacore.guardrails.RiskDecision;
import com.interfaceai.cuacore.guardrails.RiskPolicy;
import com.interfaceai.cuacore.schema.*;
import com.interfaceai.cuacore.surface.Snapshot;
import com.interfaceai.cuacore.surface.Surface;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a saved Capability step by step, with no LLM involved anywhere.
 * After each step, it checks the page against the capability's known
 * outcomes -- so a "member not found" page gets recognized as a real
 * answer, not treated as a crash. Returns one of four results: SUCCESS,
 * BUSINESS_OUTCOME (a known, expected answer), HARD_FAILURE (something
 * went wrong and needs debugging), or ESCALATED (needs a human).
 */
public class ReplayExecutor {

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{(\\w+)}}");

    private final RiskPolicy riskPolicy;
    private final HandoffController handoffController;   // null if this executor can't hand off to a human
    private final String evidenceDir;                     // where to save escalation screenshots

    public ReplayExecutor(RiskPolicy riskPolicy) {
        this(riskPolicy, null, null);
    }

    public ReplayExecutor(RiskPolicy riskPolicy, HandoffController handoffController, String evidenceDir) {
        this.riskPolicy = riskPolicy;
        this.handoffController = handoffController;
        this.evidenceDir = evidenceDir;
    }

    public ReplayResult replay(Capability capability, Map<String, Object> inputs, String baseUrl, Surface surface) {
        String runId = "replay-" + UUID.randomUUID().toString().substring(0, 8);
        Instant started = Instant.now();
        Map<String, Object> collectedOutputs = new HashMap<>();

        Map<String, Object> templateContext = new HashMap<>(inputs);
        templateContext.put("base_url", baseUrl);

        try {
            for (CapabilityStep step : capability.getSteps()) {

                RiskDecision decision = riskPolicy.decideReplay(capability, step);
                if (decision == RiskDecision.BLOCK) {
                    return failure(capability, runId, started, step.getStepId(),
                            "step blocked by risk policy", null, null);
                }
                if (decision == RiskDecision.ESCALATE_FOR_CONFIRMATION) {
                    ControlState resolved = tryEscalate(capability, runId, surface, step.getStepId(),
                            "risky step '" + step.getStepId() + "' requires human confirmation "
                                    + "(capability not yet reviewed/approved)",
                            "RISKY_STEP_UNREVIEWED");
                    if (resolved == null || !"resumed".equals(resolved.getResolution())) {
                        return ReplayResult.builder()
                                .status(ReplayStatus.ESCALATED)
                                .capabilityId(capability.getCapabilityId())
                                .capabilityVersion(capability.getVersion())
                                .runId(runId)
                                .outcomeCode("RISKY_STEP_UNREVIEWED")
                                .message(resolved == null
                                        ? "risky step requires human confirmation (no handoff available)"
                                        : "operator aborted -- risky step was not confirmed")
                                .failedStepId(step.getStepId())
                                .startedAt(started)
                                .finishedAt(Instant.now())
                                .build();
                    }
                    // human confirmed -- fall through and execute this step as normal
                }

                // run the step, and save the result if it was an extract
                String extracted = executeStep(step, surface, templateContext);
                if (step.getAction() == ActionType.EXTRACT && step.getExtractAs() != null) {
                    collectedOutputs.put(step.getExtractAs(), extracted);
                }

                // check the page against known outcomes after EVERY step,
                // not just when something throws an error
                Snapshot snapshot = surface.perceive();
                Optional<OutcomeSpec> matched = OutcomeClassifier.classify(
                        capability.getKnownOutcomes(), snapshot, surface);

                if (matched.isPresent()) {
                    OutcomeSpec outcome = matched.get();
                    switch (outcome.getCategory()) {
                        case BUSINESS_OUTCOME -> {
                            return ReplayResult.builder()
                                    .status(ReplayStatus.BUSINESS_OUTCOME)
                                    .capabilityId(capability.getCapabilityId())
                                    .capabilityVersion(capability.getVersion())
                                    .runId(runId)
                                    .outcomeCode(outcome.getCode())
                                    .message(outcome.getDescription())
                                    .startedAt(started)
                                    .finishedAt(Instant.now())
                                    .build();
                        }
                        case HARD_FAILURE -> {
                            if (outcome.isEscalate()) {
                                ControlState resolved = tryEscalate(capability, runId, surface, step.getStepId(),
                                        outcome.getDescription(), outcome.getCode());

                                if (resolved != null && "resumed".equals(resolved.getResolution())) {
                                    // human fixed the situation -- check once more whether we've
                                    // now reached the goal, rather than trying to resume stepping
                                    Snapshot afterHuman = surface.perceive();
                                    String normPage = Snapshot.normalizeWhitespace(afterHuman.getVisibleText());
                                    String normExpected = Snapshot.normalizeWhitespace(
                                            capability.getSuccessCondition().getValue());
                                    if (normPage.contains(normExpected)) {
                                        return ReplayResult.builder()
                                                .status(ReplayStatus.SUCCESS)
                                                .capabilityId(capability.getCapabilityId())
                                                .capabilityVersion(capability.getVersion())
                                                .runId(runId)
                                                .outputs(collectedOutputs)
                                                .message("goal completed after human intervention")
                                                .startedAt(started)
                                                .finishedAt(Instant.now())
                                                .build();
                                    }
                                    return failure(capability, runId, started, step.getStepId(),
                                            "still not resolved after human intervention", null, outcome.getCode());
                                }

                                return ReplayResult.builder()
                                        .status(ReplayStatus.ESCALATED)
                                        .capabilityId(capability.getCapabilityId())
                                        .capabilityVersion(capability.getVersion())
                                        .runId(runId)
                                        .outcomeCode(outcome.getCode())
                                        .message(resolved == null
                                                ? outcome.getDescription() + " (no handoff available)"
                                                : "operator aborted")
                                        .failedStepId(step.getStepId())
                                        .startedAt(started)
                                        .finishedAt(Instant.now())
                                        .build();
                            }
                            return failure(capability, runId, started, step.getStepId(),
                                    outcome.getDescription(), null, outcome.getCode());
                        }
                        case RECOVERABLE -> {
                            if ("dismiss_and_continue".equals(outcome.getRecoveryAction())
                                    && outcome.getRecoveryLocator() != null) {
                                surface.click(outcome.getRecoveryLocator(), step.getTimeoutMs());
                            } else if ("wait_and_retry".equals(outcome.getRecoveryAction())) {
                                Thread.sleep(step.getRetry().getBackoffMs());
                            }
                        }
                    }
                }
            }

            // got through every step with no matching outcome -- now check
            // if we actually reached the success condition
            Snapshot finalSnapshot = surface.perceive();
            String normalizedPage = Snapshot.normalizeWhitespace(finalSnapshot.getVisibleText());
            String normalizedExpected = Snapshot.normalizeWhitespace(capability.getSuccessCondition().getValue());
            boolean success = normalizedPage.contains(normalizedExpected);

            if (!success) {
                return failure(capability, runId, started, null,
                        "success condition not met after all steps executed",
                        finalSnapshot.getVisibleText().length() > 200
                                ? finalSnapshot.getVisibleText().substring(0, 200)
                                : finalSnapshot.getVisibleText(),
                        null);
            }

            return ReplayResult.builder()
                    .status(ReplayStatus.SUCCESS)
                    .capabilityId(capability.getCapabilityId())
                    .capabilityVersion(capability.getVersion())
                    .runId(runId)
                    .outputs(collectedOutputs)
                    .message("goal completed successfully")
                    .startedAt(started)
                    .finishedAt(Instant.now())
                    .build();

        } catch (Exception e) {
            return failure(capability, runId, started, null,
                    "unhandled exception during replay: " + e.getMessage(), null, null);
        }
    }

    /**
     * Pauses and asks a human for help, if this executor has a
     * HandoffController wired up. Returns null if it doesn't (meaning: no
     * handoff was configured, so escalation can't actually pause -- the
     * caller falls back to just reporting ESCALATED immediately).
     */
    private ControlState tryEscalate(Capability capability, String runId, Surface surface,
                                     String failedStepId, String reason, String reasonCode) throws InterruptedException {
        if (handoffController == null) {
            return null;
        }
        String shotPath = (evidenceDir != null ? evidenceDir : ".") + "/escalation_" + failedStepId + ".png";
        return handoffController.escalate(
                runId, "replay", capability.getName(), reason, reasonCode,
                surface, failedStepId, capability.getCapabilityId(), shotPath, 600_000);
    }

    /**
     * Executes one step. Returns the extracted string if action == EXTRACT,
     * otherwise null (not used by the caller for other action types).
     */
    private String executeStep(CapabilityStep step, Surface surface, Map<String, Object> templateContext) throws InterruptedException {
        String resolvedValue = step.getValueTemplate() != null
                ? resolveTemplate(step.getValueTemplate(), templateContext)
                : null;

        switch (step.getAction()) {
            case NAVIGATE -> surface.navigate(resolvedValue);
            case CLICK -> surface.click(step.getLocator(), step.getTimeoutMs());
            case FILL -> surface.fill(step.getLocator(), resolvedValue, step.getTimeoutMs());
            case SELECT -> surface.select(step.getLocator(), resolvedValue, step.getTimeoutMs());
            case EXTRACT -> {
                return surface.extract(step.getLocator(), step.getTimeoutMs());
            }
            case ASSERT_TEXT -> {
                if (!surface.assertText(resolvedValue, true)) {
                    throw new RuntimeException("assert_text failed: expected '" + resolvedValue + "' to be present");
                }
            }
            case WAIT_FOR_TEXT -> {
                if (!surface.waitForText(resolvedValue, step.getTimeoutMs())) {
                    throw new RuntimeException("wait_for_text timed out waiting for '" + resolvedValue + "'");
                }
            }
        }
        return null;
    }

    private String resolveTemplate(String template, Map<String, Object> context) {
        Matcher m = TEMPLATE.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = context.get(key);
            m.appendReplacement(sb, Matcher.quoteReplacement(value != null ? String.valueOf(value) : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private ReplayResult failure(Capability capability, String runId, Instant started,
                                 String failedStepId, String message, String observed, String outcomeCode) {
        return ReplayResult.builder()
                .status(ReplayStatus.HARD_FAILURE)
                .capabilityId(capability.getCapabilityId())
                .capabilityVersion(capability.getVersion())
                .runId(runId)
                .outcomeCode(outcomeCode)
                .message(message)
                .failedStepId(failedStepId)
                .observed(observed)
                .startedAt(started)
                .finishedAt(Instant.now())
                .build();
    }
}