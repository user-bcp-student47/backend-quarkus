// ErrorResponse.java
package dto;

public record ErrorResponse(String code, String message, boolean retryable) {
}