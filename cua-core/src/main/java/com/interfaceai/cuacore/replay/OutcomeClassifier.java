package com.interfaceai.cuacore.replay;

import com.interfaceai.cuacore.schema.OutcomeSpec;
import com.interfaceai.cuacore.surface.Snapshot;
import com.interfaceai.cuacore.surface.Surface;

import java.util.List;
import java.util.Optional;

/**
 * Checks whether the current page matches any of a capability's known
 * outcomes (like "member not found" or "session expired"). Used by replay
 * after every step, to catch these cases before they turn into confusing
 * crashes.
 */
public class OutcomeClassifier {

    public static Optional<OutcomeSpec> classify(List<OutcomeSpec> knownOutcomes, Snapshot snapshot, Surface surface) {
        for (OutcomeSpec outcome : knownOutcomes) {
            if (matches(outcome, snapshot, surface)) {
                return Optional.of(outcome);
            }
        }
        return Optional.empty();
    }

    private static boolean matches(OutcomeSpec outcome, Snapshot snapshot, Surface surface) {
        var detection = outcome.getDetection();
        return switch (detection.getKind()) {
            case TEXT_CONTAINS -> Snapshot.normalizeWhitespace(snapshot.getVisibleText())
                    .contains(Snapshot.normalizeWhitespace(detection.getValue()));
            case HTTP_STATUS -> {
                Integer status = surface.httpStatus();
                yield status != null && String.valueOf(status).equals(detection.getValue());
            }
            case ROLE_NAME_PRESENT -> {
                String[] parts = detection.getValue().split(":", 2);
                if (parts.length != 2) yield false;
                String role = parts[0];
                String name = parts[1];
                yield snapshot.getNodes().stream()
                        .anyMatch(n -> n.getRole().equals(role) && n.getName().contains(name));
            }
        };
    }
}