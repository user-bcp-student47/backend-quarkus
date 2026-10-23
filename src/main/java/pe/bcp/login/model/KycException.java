// KycException.java
package service;

public class KycException extends RuntimeException {
    public enum Phase {
        CREATE,
        GET,
        DECISION
    }

    private final Phase phase;
    private final int httpStatus;

    public KycException(Phase phase, int httpStatus, String message) {
        super(message);
        this.phase = phase;
        this.httpStatus = httpStatus;
    }

    public Phase getPhase() {
        return phase;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}