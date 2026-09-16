package com.interfaceai.cuacore.escalation;

import com.interfaceai.cuacore.surface.Snapshot;
import com.interfaceai.cuacore.surface.Surface;

import java.util.UUID;

/**
 * Called when automation hits something it can't safely handle alone.
 * When escalate() runs, it takes a screenshot, writes the situation to a
 * shared file, marks it "waiting for a human", and then waits until an
 * operator writes back "resumed" or "aborted".
 */
public class HandoffController {

    private final ControlStateStore store;

    public HandoffController(String controlStatePath) {
        this.store = new ControlStateStore(controlStatePath);
    }

    public ControlState escalate(
            String runId,
            String runKind,          // "discovery" | "replay"
            String goalOrCapabilityName,
            String reason,
            String reasonCode,
            Surface surface,
            String failedStepId,
            String capabilityId,
            String screenshotPath,
            long timeoutMs
    ) throws InterruptedException {

        if (surface.getCdpEndpoint() == null) {
            throw new IllegalStateException(
                    "surface was not launched with launchExposedForHandoff(); cannot hand off a live session");
        }

        surface.screenshot(screenshotPath);
        Snapshot snapshot = surface.perceive();

        InterventionRequest intervention = InterventionRequest.builder()
                .interventionId("intervention-" + UUID.randomUUID().toString().substring(0, 8))
                .runId(runId)
                .runKind(runKind)
                .capabilityId(capabilityId)
                .goalOrCapabilityName(goalOrCapabilityName)
                .reason(reason)
                .reasonCode(reasonCode)
                .failedStepId(failedStepId)
                .cdpEndpoint(surface.getCdpEndpoint())
                .screenshotPath(screenshotPath)
                .pageUrl(surface.currentUrl())
                .visibleTextExcerpt(snapshot.getVisibleText().length() > 800
                        ? snapshot.getVisibleText().substring(0, 800)
                        : snapshot.getVisibleText())
                .build();

        store.handToHuman(intervention);

        System.out.println("\n=== HUMAN INTERVENTION REQUIRED ===");
        System.out.println("run_id=" + runId + " reason_code=" + reasonCode);
        System.out.println("reason=" + reason);
        System.out.println("page=" + intervention.getPageUrl());
        System.out.println("Attach with the operator console pointing at this control-state file.");
        System.out.println("====================================\n");

        return store.waitForHuman(1000, timeoutMs);
    }
}