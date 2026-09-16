package com.interfaceai.cuacore.agent;

import com.interfaceai.cuacore.schema.*;

import java.util.List;

/**
 * Handwritten outcome list for our demo capability. A single successful
 * discovery run can only see the path it actually took, so it can't
 * discover what "member not found" or "session expired" look like on its
 * own. In production, a human reviewer (or a set of extra test runs
 * designed to trigger these states) would add this list to the artifact.
 * Here, we just write it by hand to match what we know the mock app does.
 */
public class KnownOutcomes {

    public static List<OutcomeSpec> lookupBalanceOutcomes() {
        return List.of(
                OutcomeSpec.builder()
                        .code("MEMBER_NOT_FOUND")
                        .category(OutcomeCategory.BUSINESS_OUTCOME)
                        .detection(DetectionRule.builder()
                                .kind(DetectionKind.TEXT_CONTAINS)
                                .value("No records found")
                                .build())
                        .description("Search returned zero members for the given ID -- a legitimate answer, not a crash.")
                        .build(),
                OutcomeSpec.builder()
                        .code("MEMBER_DETAIL_NOT_FOUND")
                        .category(OutcomeCategory.BUSINESS_OUTCOME)
                        .detection(DetectionRule.builder()
                                .kind(DetectionKind.TEXT_CONTAINS)
                                .value("No member record exists")
                                .build())
                        .description("Direct navigation to a member detail page for an ID that doesn't exist.")
                        .build(),
                OutcomeSpec.builder()
                        .code("SESSION_EXPIRED")
                        .category(OutcomeCategory.HARD_FAILURE)
                        .detection(DetectionRule.builder()
                                .kind(DetectionKind.TEXT_CONTAINS)
                                .value("SESSION EXPIRED")
                                .build())
                        .description("Teller session timed out mid-flow; cannot proceed without a fresh login.")
                        .escalate(true)
                        .build(),
                OutcomeSpec.builder()
                        .code("APP_SERVER_ERROR")
                        .category(OutcomeCategory.HARD_FAILURE)
                        .detection(DetectionRule.builder()
                                .kind(DetectionKind.TEXT_CONTAINS)
                                .value("APPLICATION ERROR")
                                .build())
                        .description("The application itself returned an unexpected server error.")
                        .escalate(true)
                        .build()
        );
    }
}