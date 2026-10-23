// FlowResponse.java
package dto;

import java.time.Instant;
import java.util.List;

public record FlowResponse(
        String flowId,
        String state,
        String currentStep,
        List<String> requiredSteps,
        String kycStatus,
        String walletStatus,
        boolean walletActive,
        boolean retryable,
        Instant submittedAt,
        String closeResult,
        String message) {
}