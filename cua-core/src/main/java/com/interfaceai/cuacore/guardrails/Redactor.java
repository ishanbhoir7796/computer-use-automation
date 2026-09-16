package com.interfaceai.cuacore.guardrails;

import com.interfaceai.cuacore.schema.DiscoveryTranscript;
import com.interfaceai.cuacore.schema.TranscriptStep;

import java.util.regex.Pattern;

/**
 * Strips things that look sensitive (currency amounts, account-number
 * shapes, tokens, SSN-shaped strings) out of text before it gets written
 * to a log or artifact. This is a backstop, not a guarantee -- it catches
 * common shapes, not every possible sensitive value.
 */
public class Redactor {

    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9\\-._~+/]+=*");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\b[A-Z]{2,4}-\\d{4,8}\\b");
    private static final Pattern CURRENCY_AMOUNT = Pattern.compile("\\$\\s?\\d[\\d,]*\\.\\d{2}");
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    public static String scrubText(String value) {
        if (value == null) return null;
        String out = value;
        out = BEARER_TOKEN.matcher(out).replaceAll("Bearer [REDACTED]");
        out = ACCOUNT_NUMBER.matcher(out).replaceAll("[ACCOUNT_NUMBER_REDACTED]");
        out = CURRENCY_AMOUNT.matcher(out).replaceAll(java.util.regex.Matcher.quoteReplacement("$[AMOUNT_REDACTED]"));
        out = SSN.matcher(out).replaceAll("[SSN_REDACTED]");
        return out;
    }

    public static Object redactValue(Object value, boolean sensitive) {
        if (sensitive) return "[REDACTED]";
        if (value instanceof String s) return scrubText(s);
        return value;
    }

    /**
     * Scrubs the free-text fields of a transcript (rationale, error text,
     * final summary) before it gets written to disk -- these are the
     * places a model's own words might accidentally include something
     * sensitive it read off the screen.
     */
    public static DiscoveryTranscript scrubTranscript(DiscoveryTranscript transcript) {
        transcript.setFinalSummary(scrubText(transcript.getFinalSummary()));
        for (TranscriptStep step : transcript.getSteps()) {
            step.setRationale(scrubText(step.getRationale()));
            step.setResultText(scrubText(step.getResultText()));
        }
        return transcript;
    }
}