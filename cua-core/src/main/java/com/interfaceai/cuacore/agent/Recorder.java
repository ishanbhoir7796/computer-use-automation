package com.interfaceai.cuacore.agent;

import com.interfaceai.cuacore.schema.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class Recorder {

    public Capability buildCapability(
            DiscoveryTranscript transcript,
            String capabilityId,
            String name,
            String description,
            String appId,
            String vendorProduct,
            List<OutcomeSpec> knownOutcomes,
            Map<String, String> inputDescriptions
    ) {
        if (!"success".equals(transcript.getOutcome())) {
            throw new IllegalStateException(
                    "cannot build a capability from a non-successful transcript (outcome=" + transcript.getOutcome() + ")");
        }

        Map<String, Object> params = transcript.getInputParamsUsed();
        List<CapabilityStep> steps = new ArrayList<>();

        steps.add(CapabilityStep.builder()
                .stepId("s0_navigate_entry")
                .action(ActionType.NAVIGATE)
                .valueTemplate("{{base_url}}" + transcript.getEntryRoute())
                .checkpointNote("entry point loaded")
                .build());

        int i = 1;
        for (TranscriptStep t : transcript.getSteps()) {
            String stepId = "s" + i + "_" + t.getAction();

            switch (t.getAction()) {
                case "navigate" -> {
                    String url = String.valueOf(t.getToolInput().get("url"));
                    String templated = templatize(url, params);
                    steps.add(CapabilityStep.builder()
                            .stepId(stepId)
                            .action(ActionType.NAVIGATE)
                            .valueTemplate(templated)
                            .checkpointNote(t.getRationale())
                            .build());
                }
                case "click", "fill", "select" -> {
                    // Use the same locator that actually worked during discovery,
                    // instead of building a new one -- this matters most for table
                    // cells, which need a real XPath, not just a role and name.
                    Locator locator = t.getResolvedLocator();

                    String valueTemplate = null;
                    if (!t.getAction().equals("click")) {
                        valueTemplate = templatize(String.valueOf(t.getToolInput().get("value")), params);
                    }

                    steps.add(CapabilityStep.builder()
                            .stepId(stepId)
                            .action(ActionType.valueOf(t.getAction().toUpperCase()))
                            .locator(locator)
                            .valueTemplate(valueTemplate)
                            .checkpointNote(t.getRationale())
                            .riskLevel(t.isRisky() ? RiskLevel.RISKY : RiskLevel.SAFE)
                            .build());
                }
                case "extract" -> {
                    Locator locator = t.getResolvedLocator();
                    steps.add(CapabilityStep.builder()
                            .stepId(stepId)
                            .action(ActionType.EXTRACT)
                            .locator(locator)
                            .extractAs(String.valueOf(t.getToolInput().get("output_name")))
                            .checkpointNote(t.getRationale())
                            .build());
                }
            }
            i++;
        }

        List<InputParamSpec> inputSchema = new ArrayList<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            inputSchema.add(InputParamSpec.builder()
                    .name(e.getKey())
                    .type(inferParamType(e.getValue()))
                    .required(true)
                    .description(inputDescriptions.getOrDefault(e.getKey(), ""))
                    .build());
        }

        List<OutputFieldSpec> outputSchema = new ArrayList<>();
        for (CapabilityStep step : steps) {
            if (step.getAction() == ActionType.EXTRACT) {
                outputSchema.add(OutputFieldSpec.builder()
                        .name(step.getExtractAs())
                        .type(ParamType.STRING)
                        .extractedFromStep(step.getStepId())
                        .build());
            }
        }

        DetectionRule successCondition = DetectionRule.builder()
                .kind(DetectionKind.TEXT_CONTAINS)
                .value(transcript.getSuccessConditionText() != null ? transcript.getSuccessConditionText() : "")
                .build();

        return Capability.builder()
                .capabilityId(capabilityId)
                .name(name)
                .description(description)
                .target(TargetFingerprint.builder()
                        .appId(appId)
                        .vendorProduct(vendorProduct)
                        .entryRoute(transcript.getEntryRoute())
                        .build())
                .inputSchema(inputSchema)
                .outputSchema(outputSchema)
                .steps(steps)
                .successCondition(successCondition)
                .knownOutcomes(knownOutcomes)
                .createdFromRunId(transcript.getRunId())
                .build();
    }

    private String templatize(String value, Map<String, Object> params) {
        String out = value;
        List<Map.Entry<String, Object>> sorted = new ArrayList<>(params.entrySet());
        sorted.sort(Comparator.comparingInt(e -> -String.valueOf(e.getValue()).length()));
        for (Map.Entry<String, Object> e : sorted) {
            String sval = String.valueOf(e.getValue());
            if (!sval.isEmpty() && out.contains(sval)) {
                out = out.replace(sval, "{{" + e.getKey() + "}}");
            }
        }
        return out;
    }

    private ParamType inferParamType(Object value) {
        if (value instanceof Boolean) return ParamType.BOOLEAN;
        try {
            Double.parseDouble(String.valueOf(value));
            return ParamType.NUMBER;
        } catch (NumberFormatException e) {
            return ParamType.STRING;
        }
    }
}