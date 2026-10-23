// FlowMetrics.java
package model;

import java.time.Instant;

public record FlowMetrics(
        Instant startedAt,
        Instant submittedAt,
        Instant kycStartedAt,
        Instant kycCompletedAt,
        Instant activatedAt) {
}