// OnboardingEvent.java
package model;

import java.time.Instant;
import java.util.UUID;

public record OnboardingEvent(
        UUID flowId,
        FlowEventType type,
        String step,
        String result,
        Instant occurredAt) {
}