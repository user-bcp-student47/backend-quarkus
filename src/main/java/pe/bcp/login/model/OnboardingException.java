// OnboardingException.java
package service;

public class OnboardingException extends RuntimeException {
    private final int httpStatus;
    private final String code;
    private final boolean retryable;

    public OnboardingException(int httpStatus, String code, String message, boolean retryable) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public static OnboardingException badRequest(String code, String message) {
        return new OnboardingException(400, code, message, false);
    }

    public static OnboardingException notFound() {
        return new OnboardingException(404, "FLOW_NOT_FOUND", "No se encontró el flujo.", false);
    }

    public static OnboardingException invalidState(String message) {
        return new OnboardingException(409, "INVALID_STATE", message, false);
    }

    public static OnboardingException validation(String code, String message) {
        return new OnboardingException(422, code, message, false);
    }
}