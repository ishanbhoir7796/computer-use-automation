package com.interfaceai.cuacore.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One known outcome a capability might hit during replay -- like "member
 * not found" or "session expired" -- along with how to detect it and what
 * category it falls into (a normal answer, something to fix automatically
 * and continue, or something that needs a human).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutcomeSpec {

    private String code;                    // e.g. "MEMBER_NOT_FOUND"
    private OutcomeCategory category;
    private DetectionRule detection;
    private String description;

    // only used when category is RECOVERABLE -- what to do before continuing
    private String recoveryAction;          // "dismiss_and_continue" | "wait_and_retry"
    private Locator recoveryLocator;
    private String recoveryResumeStep;

    // if true (typically for HARD_FAILURE), route to a human instead of just failing
    @Builder.Default
    private boolean escalate = false;

    private String checkedAfterStep;        // which step_id to check after; null = check after every step
}