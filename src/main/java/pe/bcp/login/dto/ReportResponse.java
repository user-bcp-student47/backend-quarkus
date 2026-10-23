// ReportResponse.java
package dto;

public record ReportResponse(
        long sampleCount,
        Long medianObservedOnboardingMs,
        Long medianKycMs,
        Long medianActivationMs,
        long productReferenceMs,
        String measurementDefinition) {
}