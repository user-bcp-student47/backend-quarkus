// OnboardingFlow.java
package model;

import java.time.Instant;
import java.util.UUID;

public record OnboardingFlow(
        UUID flowId,
        FlowState state,
        Instant startedAt,
        Instant submittedAt,
        Instant kycStartedAt,
        Instant kycCompletedAt,
        Instant activatedAt,
        String kycVerificationId,
        String closeResult,
        Instant closedAt) {
}